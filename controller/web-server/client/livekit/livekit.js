import { Room, RoomEvent, Track } from 'livekit-client';
import axios from 'axios';
import {localStorageKeys} from '../utils/constants'
import { Buttons } from '../keyboardHandlers/buttons';

/**
 * function to enable webRTC connection
 * @param connection
 * @constructor
 */
export function LiveKitClient() {
  const room = new Room({adaptiveStream: true, dynacast: true,});
  let buttons = null;

  const isTokenExpired = (expirationTime) => {
    const currentTime = Math.floor(Date.now() / 1000); // Current time in seconds
    return currentTime > expirationTime;
  }

  this.start = () => {
    let url = null //localStorage.getItem('livekit_url');
    let token = localStorage.getItem('livekit_token');
    let expiration = localStorage.getItem('livekit_expiration');

    if (!url || !token || !expiration || isTokenExpired(expiration)) {
      const signedInUser = JSON.parse(localStorage.getItem(localStorageKeys.user))
      const userEmail = signedInUser.email;
      console.log("calling /api/createToken")
      const uniqueID = `WEB-${Math.random().toString(36).substring(2, 10)}`;
      axios.post('/api/createToken', { roomName: userEmail, participantName: uniqueID})
        .then(response => {
          url = response.data.server_url;
          token = response.data.token;
          const ttl = response.data.ttl; // Assume the server returns an expiration date
          expiration = Math.floor(Date.now() / 1000) + ttl - 60;

          // Store the URL, token, and expiration in localStorage
          localStorage.setItem('livekit_url', url);
          localStorage.setItem('livekit_token', token);
          localStorage.setItem('livekit_expiration', expiration);

          connectToRoom(url, token);
        })
        .catch(error => {
          console.error('Error fetching token:', error);
        });
    } else {
      connectToRoom(url, token);
    }

    this.setupListeners();

    // setup buttons
  }

  const checkParticipantAvailable = (identity) => {
    for (const [_, participant] of room.remoteParticipants) {
      if (participant.identity === identity) {
        return true;
      }
    }
    return false;
  }

  const connectToRoom = async (url, token) => {
    // pre-warm connection, this can be called as early as your page is loaded
    room.prepareConnection(url, token);
    // Connect to the LiveKit room.
    room.connect(url, token)
    .then(() => {
      console.log('Connected to room:', room.name);
      const tryPerformRpc = () => {
        if (!checkParticipantAvailable('Android')) {
          console.log("Android participant not available, retrying in 10s...");
          setTimeout(tryPerformRpc, 10000);
          return;
        }

        const payload_json = {"command": "CONNECTED"};
        const payload = JSON.stringify(payload_json);
        room.localParticipant.performRpc({
          destinationIdentity: 'Android',
          method: 'client-connected',
          payload: payload,
        }).then(response => {
          const available_cameras = JSON.parse(response);
          buttons = new Buttons(this, available_cameras);
          setInterval(async () => {
                const results = await this.getVehicleStatus();
                for (const callback of Object.values(window.locationCallbacks)) {
                  if (typeof callback === 'function') {
                      callback(results);
                  }
              }
            }, 1000);
        }).catch(error => {
          console.log("Trying to connect to robot: " + error);
          setTimeout(tryPerformRpc, 10000); // Retry after 10 seconds
        });
      };

      tryPerformRpc();
    })
    .catch((error) => {
      console.error('Error connecting to room:', error);
    });

  }

  this.sendDriveCommand = (msg) => {
    if (msg === undefined || msg === null) {
      return
    }

    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot send drive command: Android participant not available");
      return;
    }

    msg = JSON.stringify({driveCmd: msg})
    console.log(msg)
    // publish to topic
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'drive-cmd',
      payload: msg,
    }).then(response => {
    }).catch(error => {
        console.log("Error sending drive command request: " + error );
    });
  }

  this.sendCommand = (msg) => {
    if (msg === undefined || msg === null) {
      return
    }

    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot send command: Android participant not available");
      return;
    }

    msg = JSON.stringify({command: msg})

    // publish to topic
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'cmd',
      payload: msg,
    }).then(response => {
    }).catch(error => {
        console.log("Error sending command request: " + error );
    });
  }

  this.switchCamera = (camera_id) => {
    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot switch camera: Android participant not available");
      return;
    }

    let payload = camera_id.toString();
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'switch-camera',
      payload: payload,
    }).then(response => {
    }).catch(error => {
        console.log("Error sending camera switch request: " + error );
    });
  }


  this.switchFlashlight = () => {
    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot switch flashlight: Android participant not available");
      return Promise.reject("Android participant not available");
    }

    return room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'switch-flashlight',
      payload: "",
    })
  }

  this.getVehicleStatus = async () => {
    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot get vehicle status: Android participant not available");
      return {
        lat: 0,
        lng: 0,
        bearing: 0,
        recording: false
      };
    }

    const response = await room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'status',
      payload: "",
    })
    const res_json = JSON.parse(response)
    const status = res_json.status
    const location = res_json.location
    let update_loc = {
      lat: location.latitude,
      lng: location.longitude,
      bearing: location.bearing,
      recording: status.LOGS
    }
    return update_loc;
  }

  this.sendWaypointCommand = async (waypoints) => {
    if (!checkParticipantAvailable('Android')) {
      console.log("Cannot send waypoint command: Android participant not available");
      return false;
    }

    // payload: {"waypoints": [{}, {}, {}]}
    const response = await room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'waypoint-cmd',
      payload: JSON.stringify(waypoints),
    })
    if (response == "0")
      return true;
    else
      return false;
  }

  // Audio management
  let audioContext = null;
  let currentAudioTrack = null;
  let isAudioMuted = true;

  const initializeAudioContext = async () => {
    if (!audioContext) {
      try {
        audioContext = new (window.AudioContext || window['webkitAudioContext'])();
      } catch (error) {
        console.error('AudioContext not supported:', error);
        return;
      }
    }

    if (audioContext.state === 'suspended') {
      await audioContext.resume();
    }
  };

  const handleAudioTrack = async (track) => {
    currentAudioTrack = track;
    const audioElement = document.getElementById('audio');

    if (audioElement) {
      // Clear existing track
      if (audioElement.srcObject) {
        audioElement.srcObject = null;
      }

      // Attach new track
      track.attach(audioElement);
      audioElement.muted = isAudioMuted;

      // Update sound button icon
      const soundButton = document.getElementById('sound_button');
      if (soundButton) {
        soundButton.src = isAudioMuted ? 'icons/volume_off_black_24dp.svg' : 'icons/volume_up_black_24dp.svg';
      }
    }
  };

  // Expose audio control methods
  this.initializeAudioContext = initializeAudioContext;

  this.toggleAudio = async () => {
    await initializeAudioContext();

    isAudioMuted = !isAudioMuted;

    const audioElement = document.getElementById('audio');
    const videoElement = document.getElementById('video');

    if (audioElement) {
      audioElement.muted = isAudioMuted;
    }

    if (videoElement) {
      videoElement.muted = isAudioMuted;
    }

    return isAudioMuted;
  };

  this.setupListeners = () => {
    room.on(RoomEvent.TrackSubscribed, async (track, _publication, participant) => {
      if (track.kind === Track.Kind.Video) {
        const videoElement = document.getElementById("video");
        track.attach(videoElement);
        videoElement.muted = isAudioMuted;
        console.log("Video track attached");
      }

      if (track.kind === Track.Kind.Audio) {
        console.log(`Audio track from: ${participant.identity}`);
        await handleAudioTrack(track);
      }
    });

    room.on(RoomEvent.TrackUnsubscribed, (track) => {
      if (track.kind === Track.Kind.Audio && track === currentAudioTrack) {
        currentAudioTrack = null;
        const audioElement = document.getElementById('audio');
        if (audioElement) {
          audioElement.srcObject = null;
        }
      }
    });

  }

}
