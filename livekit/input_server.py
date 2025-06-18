import os
import asyncio
import json
import tkinter as tk
import argparse

from livekit import rtc
from livekit.rtc import DataPacketKind
from livekit.rtc.room import ConnectError

from pynput.keyboard import Controller as KeyboardController, Key
from pynput.mouse import Controller as MouseController, Button

def get_host_screen_size():
    root = tk.Tk()
    root.update_idletasks()
    root.withdraw()
    width = root.winfo_screenwidth()
    height = root.winfo_screenheight()
    root.destroy()
    return width, height

class InputReceiver:
    def __init__(self, host_width: int, host_height: int, debug: bool = False):
        self.host_width = host_width
        self.host_height = host_height
        self.kb = KeyboardController()
        self.mouse = MouseController()
        self.debug = debug

    def handle_message(self, msg: dict):
        # If debug, print a summary of the message
        if self.debug:
            t = msg.get("type")
            subtype = msg.get("subtype")
            # Print a concise log line
            print(f"[DEBUG] Received command: type={t}, subtype={subtype}")
        t = msg.get("type")
        if t == "mouse":
            subtype = msg.get("subtype")
            if subtype == "mousemove":
                x_norm = msg.get("x_norm")
                y_norm = msg.get("y_norm")
                if not isinstance(x_norm, (int, float)) or not isinstance(y_norm, (int, float)):
                    return
                x_norm = max(0.0, min(1.0, x_norm))
                y_norm = max(0.0, min(1.0, y_norm))
                x_abs = int(round(x_norm * self.host_width))
                y_abs = int(round(y_norm * self.host_height))
                x_abs = max(0, min(self.host_width - 1, x_abs))
                y_abs = max(0, min(self.host_height - 1, y_abs))
                try:
                    self.mouse.position = (x_abs, y_abs)
                except Exception as e:
                    print(f"[Error] Mouse move injection failed: {e}")
            elif subtype == "mousebutton":
                btn_name = msg.get("button")
                action = msg.get("action")
                if not isinstance(btn_name, str) or action not in ("down", "up"):
                    return
                btn = None
                if btn_name.lower() == "left":
                    btn = Button.left
                elif btn_name.lower() == "right":
                    btn = Button.right
                elif btn_name.lower() == "middle":
                    btn = Button.middle
                if btn is None:
                    return
                try:
                    if action == "down":
                        self.mouse.press(btn)
                    else:
                        self.mouse.release(btn)
                except Exception as e:
                    print(f"[Error] Mouse button injection failed: {e}")
            elif subtype == "wheel":
                delta = msg.get("delta")
                if not isinstance(delta, (int, float)):
                    return
                try:
                    self.mouse.scroll(0, int(delta))
                except Exception as e:
                    print(f"[Error] Mouse wheel injection failed: {e}")
        elif t == "keyboard":
            subtype = msg.get("subtype")
            key_str = msg.get("key")
            if subtype not in ("keydown", "keyup") or not isinstance(key_str, str):
                return
            # Map key_str to pynput Key or literal char
            key = None
            if len(key_str) == 1:
                key = key_str
            else:
                try:
                    key = getattr(Key, key_str.lower(), None)
                except Exception:
                    key = None
                if key is None:
                    mapping = {
                        "enter": Key.enter,
                        "space": Key.space,
                        "tab": Key.tab,
                        "backspace": Key.backspace,
                        "esc": Key.esc,
                        "escape": Key.esc,
                        "shift": Key.shift,
                        "ctrl": Key.ctrl,
                        "alt": Key.alt,
                        "capslock": Key.caps_lock,
                        "up": Key.up,
                        "down": Key.down,
                        "left": Key.left,
                        "right": Key.right,
                        "delete": Key.delete,
                        "home": Key.home,
                        "end": Key.end,
                        "pageup": Key.page_up,
                        "pagedown": Key.page_down,
                        "f1": Key.f1,
                        "f2": Key.f2,
                        # add more as needed
                    }
                    key = mapping.get(key_str.lower())
            if key is None:
                return
            try:
                if subtype == "keydown":
                    self.kb.press(key)
                else:
                    self.kb.release(key)
            except Exception as e:
                print(f"[Error] Keyboard injection failed: {e}")
        # other types ignored

async def main_host(debug: bool):
    url = os.getenv("LIVEKIT_URL")
    token = os.getenv("LIVEKIT_TOKEN")
    if not url or not token:
        print("Environment variables LIVEKIT_URL and LIVEKIT_TOKEN must be set.")
        return

    room = rtc.Room()
    try:
        await room.connect(url, token)
        print(f"Connected to LiveKit room: {room.name or room.sid}")
    except ConnectError as e:
        print(f"Failed to connect to LiveKit: {e}")
        return

    host_width, host_height = get_host_screen_size()
    print(f"Host screen size: {host_width}×{host_height}")

    receiver = InputReceiver(host_width, host_height, debug=debug)

    @room.on("data_received")
    def on_data_received(data_pkt: rtc.DataPacket):
        try:
            topic = getattr(data_pkt, "topic", None)
            if topic is not None and topic != "input":
                return
            raw = data_pkt.data
            msg = json.loads(raw.decode("utf-8"))
            receiver.handle_message(msg)
        except Exception as e:
            if debug:
                print(f"[DEBUG] Failed to process incoming packet: {e}")

    if debug:
        print("Debug mode ON: will print when commands are received.")
    else:
        print("Debug mode OFF.")

    print("Host ready to receive normalized input events. Press Ctrl+C to exit.")
    try:
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        pass
    finally:
        await room.disconnect()
        print("Disconnected from LiveKit.")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="LiveKit host input receiver")
    parser.add_argument(
        "--debug", action="store_true",
        help="If set, print a message each time a command is received"
    )
    args = parser.parse_args()
    # On macOS: ensure Accessibility permission is granted to your Python process
    try:
        asyncio.run(main_host(debug=args.debug))
    except KeyboardInterrupt:
        print("Host script terminated by user.")

