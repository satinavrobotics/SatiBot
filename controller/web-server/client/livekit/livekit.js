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
          expiration = response.data.expiration_time; // Assume the server returns an expiration date

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

  const connectToRoom = async (url, token) => {
    // pre-warm connection, this can be called as early as your page is loaded
    room.prepareConnection(url, token);
    // Connect to the LiveKit room.
    room.connect(url, token)
    .then(() => {
      console.log('Connected to room:', room.name);
      const tryPerformRpc = () => {
        room.localParticipant.performRpc({
          destinationIdentity: 'Android',
          method: 'client-connected',
          payload:`WEB-${require('os').hostname()}`,
        }).then(response => {
          const available_cameras = JSON.parse(response);
          buttons = new Buttons(this, available_cameras);
          window.startLocationService(this);
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

    msg = JSON.stringify({driveCmd: msg})
    console.log(msg)
    // publish to topic
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'drive-cmd',
      payload: msg,
    }).then(response => {
    }).catch(error => {
        console.log("Error sending camera switch request: " + error );
    });
  }

  this.sendCommand = (msg) => {
    if (msg === undefined || msg === null) {
      return
    }

    msg = JSON.stringify({command: msg})

    // publish to topic
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'cmd',
      payload: msg,
    }).then(response => {
    }).catch(error => {
        console.log("Error sending camera switch request: " + error );
    });
  }

  this.switchCamera = (camera_id) => {
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
    return room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'switch-flashlight',
      payload: "",
    })
  }

  this.getLocation = (update) => {
    room.localParticipant.performRpc({
      destinationIdentity: 'Android',
      method: 'location',
      payload: "",
    }).then(response => {
      let res_json = JSON.parse(response).status
      let update_loc = {
        lat: res_json.latitude,
        lng: res_json.longitude
      }
      update(update_loc)
    }).catch(error => {
        console.log("Error sending location request: " + error );
    });
  }

  

  this.setupListeners = () => {
    room.on(RoomEvent.TrackSubscribed, async (track, publication, participant) => {
      if (track.kind === Track.Kind.Video) {
        const videoElement = document.getElementById("video");
        track.attach(videoElement);
        console.log("found video track")
      }
    });

  }

}
