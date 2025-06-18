#!/usr/bin/env python3
import sys
import os
import json
import gzip
import base64
import threading
import asyncio
import requests
from requests.auth import HTTPBasicAuth

import rclpy
from rclpy.node import Node

from nav_msgs.msg import OccupancyGrid, Path
from geometry_msgs.msg import PoseStamped
from tf2_msgs.msg import TFMessage

# LiveKit Python SDK imports
from livekit import rtc  # make sure livekit-client is installed: pip install livekit-client

def fetch_livekit_token(participant_name: str) -> str:
    """
    Fetch a LiveKit access token by calling your token-generation endpoint.
    Returns the token string on success, or raises RuntimeError on failure.
    """
    # --- Configuration: adjust as needed or read from env/ROS params instead ---
    TOKEN_URL = 'https://controller.satinavrobotics.com/api/createToken'
    BASIC_AUTH_USER = 'satiadmin'
    BASIC_AUTH_PASS = 'poganyindulo'
    ROOM_NAME = 'admin@satinavrobotics.com'
    # -------------------------------------------------------------------------
    body = {
        "roomName": ROOM_NAME,
        "participantName": participant_name
    }
    try:
        resp = requests.post(
            TOKEN_URL,
            auth=HTTPBasicAuth(BASIC_AUTH_USER, BASIC_AUTH_PASS),
            json=body,
            timeout=5.0
        )
    except Exception as e:
        raise RuntimeError(f"Failed to call token endpoint: {e}")
    if resp.status_code != 200:
        raise RuntimeError(f"Token endpoint returned status {resp.status_code}: {resp.text}")
    # Attempt to parse JSON. Adjust key if API returns differently.
    try:
        data = resp.json()
    except ValueError:
        # Not JSON? Maybe the API returns the token directly as text.
        token = resp.text.strip()
        if not token:
            raise RuntimeError("Empty token received")
        return token
    # Assume the JSON has a field named 'token' or 'accessToken'. Adjust as needed.
    if 'token' in data:
        return data['token']
    elif 'accessToken' in data:
        return data['accessToken']
    else:
        # If structure is different, you may need to inspect data and adjust:
        raise RuntimeError(f"Unexpected token response JSON structure: {data}")


class LiveKitBridgeNode(Node):
    def __init__(self, livekit_url: str, livekit_token: str, identity: str):
        """
        livekit_url: e.g. "wss://your-livekit-server-url"
        livekit_token: the access token string
        identity: participant identity in LiveKit (e.g., robot name)
        """
        super().__init__('livekit_bridge_node')
        self.get_logger().info(f"Initializing LiveKitBridgeNode with identity='{identity}'")

        self.livekit_url = livekit_url
        self.livekit_token = livekit_token
        self.identity = identity

        # LiveKit room & asyncio loop placeholders
        self.lk_room = None
        self.lk_loop = None

        # Start LiveKit connection in a separate thread / asyncio loop
        self.start_livekit_loop()

        # Set up ROS subscriptions
        # QoS depth=10; adjust QoS profiles if needed (e.g., reliable, transient local for /map)
        self.create_subscription(
            OccupancyGrid,
            '/map',
            lambda msg: self.cb_occupancy_grid(msg, topic_name='map'),
            10)
        self.create_subscription(
            OccupancyGrid,
            '/global_costmap/costmap',
            lambda msg: self.cb_occupancy_grid(msg, topic_name='global_costmap'),
            10)
        self.create_subscription(
            OccupancyGrid,
            '/local_costmap/costmap',
            lambda msg: self.cb_occupancy_grid(msg, topic_name='local_costmap'),
            10)
        self.create_subscription(
            TFMessage,
            '/tf',
            self.cb_tf,
            10)
        self.create_subscription(
            Path,
            '/plan',
            self.cb_path,
            10)
        self.create_subscription(
            PoseStamped,
            '/goal_pose',
            self.cb_pose_stamped,
            10)

    def start_livekit_loop(self):
        """Create and run an asyncio loop in a separate thread for LiveKit operations."""
        def run_loop(loop: asyncio.AbstractEventLoop):
            asyncio.set_event_loop(loop)
            # First, connect
            loop.run_until_complete(self.connect_livekit())
            # Then keep running to handle data sends
            loop.run_forever()

        self.lk_loop = asyncio.new_event_loop()
        t = threading.Thread(target=run_loop, args=(self.lk_loop,), daemon=True)
        t.start()

    async def connect_livekit(self):
        """Async: connect to LiveKit room as a participant."""
        try:
            room = rtc.Room(loop=self.lk_loop)
            # Optionally log connection events
            @room.on("connected")
            def on_connected():
                self.get_logger().info(f"Connected to LiveKit room: {room.name}")

            @room.on("disconnected")
            def on_disconnected():
                self.get_logger().info("Disconnected from LiveKit")

            # Connect: auto_subscribe=False since we only send data
            await room.connect(self.livekit_url, self.livekit_token, auto_subscribe=False, participant_identity=self.identity)
            self.lk_room = room
            self.get_logger().info("LiveKit connection established")
        except Exception as e:
            self.get_logger().error(f"Failed to connect LiveKit: {e}")

    def send_data(self, obj: dict):
        """Schedule sending a JSON-serialized dict over LiveKit data channel."""
        if not self.lk_room or not self.lk_room.local_participant:
            self.get_logger().warn("LiveKit room not ready; dropping data")
            return

        try:
            data_str = json.dumps(obj)
        except Exception as e:
            self.get_logger().error(f"JSON serialization failed: {e}")
            return

        async def do_send():
            try:
                # reliable=True for reliable delivery; set False for lower-latency/unreliable if desired
                await self.lk_room.local_participant.publish_data(data_str.encode('utf-8'), reliable=True)
            except Exception as e:
                self.get_logger().error(f"LiveKit publish_data error: {e}")

        # Schedule on the LiveKit asyncio loop
        try:
            asyncio.run_coroutine_threadsafe(do_send(), self.lk_loop)
        except Exception as e:
            self.get_logger().error(f"Failed to schedule LiveKit send: {e}")

    def cb_occupancy_grid(self, msg: OccupancyGrid, topic_name: str):
        """Callback for OccupancyGrid; compress data and send metadata + compressed payload."""
        # Serialize map metadata
        info = {
            'resolution': msg.info.resolution,
            'width': msg.info.width,
            'height': msg.info.height,
            'origin': {
                'position': {
                    'x': msg.info.origin.position.x,
                    'y': msg.info.origin.position.y,
                    'z': msg.info.origin.position.z,
                },
                'orientation': {
                    'x': msg.info.origin.orientation.x,
                    'y': msg.info.origin.orientation.y,
                    'z': msg.info.origin.orientation.z,
                    'w': msg.info.origin.orientation.w,
                }
            }
        }
        # msg.data is a list of int8 values (-1 for unknown, 0-100 occupancy). To pack into bytes:
        # We map each int to unsigned byte: e.g., -1 -> 255, 0-100 -> same. Adjust if needed.
        try:
            # Convert to bytes: map -1 -> 255, 0..100 -> 0..100
            byte_arr = bytearray()
            for v in msg.data:
                if v < 0:
                    byte_arr.append(255)
                else:
                    byte_arr.append(v if v <= 254 else 254)
            compressed = gzip.compress(bytes(byte_arr))
            data_payload = base64.b64encode(compressed).decode('ascii')
            data_obj = {
                'topic': topic_name,
                'msg_type': 'OccupancyGrid',
                'info': info,
                'data_compressed_gzip_base64': data_payload,
                'timestamp': {
                    'sec': msg.header.stamp.sec,
                    'nanosec': msg.header.stamp.nanosec
                },
                'frame_id': msg.header.frame_id
            }
        except Exception as e:
            # On any error compressing, send metadata only
            self.get_logger().warn(f"Failed to compress occupancy grid data: {e}. Sending metadata only.")
            data_obj = {
                'topic': topic_name,
                'msg_type': 'OccupancyGrid',
                'info': info,
                'data_compressed_gzip_base64': None,
                'timestamp': {
                    'sec': msg.header.stamp.sec,
                    'nanosec': msg.header.stamp.nanosec
                },
                'frame_id': msg.header.frame_id
            }
        self.send_data(data_obj)

    def cb_tf(self, msg: TFMessage):
        """Callback for TFMessage: serialize transforms list."""
        transforms = []
        for t in msg.transforms:
            transforms.append({
                'header': {
                    'stamp': {'sec': t.header.stamp.sec, 'nanosec': t.header.stamp.nanosec},
                    'frame_id': t.header.frame_id
                },
                'child_frame_id': t.child_frame_id,
                'transform': {
                    'translation': {
                        'x': t.transform.translation.x,
                        'y': t.transform.translation.y,
                        'z': t.transform.translation.z
                    },
                    'rotation': {
                        'x': t.transform.rotation.x,
                        'y': t.transform.rotation.y,
                        'z': t.transform.rotation.z,
                        'w': t.transform.rotation.w
                    }
                }
            })
        data_obj = {
            'topic': 'tf',
            'msg_type': 'TFMessage',
            'transforms': transforms
        }
        self.send_data(data_obj)

    def cb_path(self, msg: Path):
        """Callback for nav_msgs/Path: serialize sequence of PoseStamped."""
        poses = []
        for ps in msg.poses:
            poses.append({
                'header': {
                    'stamp': {'sec': ps.header.stamp.sec, 'nanosec': ps.header.stamp.nanosec},
                    'frame_id': ps.header.frame_id
                },
                'pose': {
                    'position': {
                        'x': ps.pose.position.x,
                        'y': ps.pose.position.y,
                        'z': ps.pose.position.z
                    },
                    'orientation': {
                        'x': ps.pose.orientation.x,
                        'y': ps.pose.orientation.y,
                        'z': ps.pose.orientation.z,
                        'w': ps.pose.orientation.w
                    }
                }
            })
        data_obj = {
            'topic': 'plan',
            'msg_type': 'Path',
            'poses': poses
        }
        self.send_data(data_obj)

    def cb_pose_stamped(self, msg: PoseStamped):
        """Callback for geometry_msgs/PoseStamped (goal_pose)."""
        data_obj = {
            'topic': 'goal_pose',
            'msg_type': 'PoseStamped',
            'header': {
                'stamp': {'sec': msg.header.stamp.sec, 'nanosec': msg.header.stamp.nanosec},
                'frame_id': msg.header.frame_id
            },
            'pose': {
                'position': {
                    'x': msg.pose.position.x,
                    'y': msg.pose.position.y,
                    'z': msg.pose.position.z
                },
                'orientation': {
                    'x': msg.pose.orientation.x,
                    'y': msg.pose.orientation.y,
                    'z': msg.pose.orientation.z,
                    'w': msg.pose.orientation.w
                }
            }
        }
        self.send_data(data_obj)


def main(args=None):
    # Expect participant name as first CLI argument
    if len(sys.argv) < 2:
        print("Usage: ros2 run <your_pkg> livekit_bridge_node.py <participantName>")
        sys.exit(1)
    participant_name = sys.argv[1]

    # Fetch LiveKit token
    try:
        token = fetch_livekit_token(participant_name)
    except RuntimeError as e:
        print(f"[ERROR] Could not fetch LiveKit token: {e}")
        sys.exit(1)

    # LiveKit URL: require as environment variable, e.g. export LIVEKIT_URL="wss://your-livekit-server"
    livekit_url = os.getenv('LIVEKIT_URL', None)
    if not livekit_url:
        print("[ERROR] LIVEKIT_URL environment variable not set (e.g., 'wss://your-livekit-url').")
        sys.exit(1)

    # Initialize ROS
    rclpy.init(args=args)
    node = LiveKitBridgeNode(livekit_url=livekit_url, livekit_token=token, identity=participant_name)
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.get_logger().info("Shutting down LiveKitBridgeNode")
        # Disconnect LiveKit cleanly if possible
        if node.lk_room and node.lk_loop:
            async def disconnect_room():
                try:
                    await node.lk_room.disconnect()
                except Exception:
                    pass
            try:
                asyncio.run_coroutine_threadsafe(disconnect_room(), node.lk_loop)
            except Exception:
                pass
        rclpy.shutdown()


if __name__ == '__main__':
    main()

