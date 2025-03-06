import { Room, RoomEvent, Track } from 'livekit-client';
import axios from 'axios';
import {localStorageKeys} from '../utils/constants'

/**
 * function to enable webRTC connection
 * @param connection
 * @constructor
 */
export function LiveKitClient() {
  const room = new Room({adaptiveStream: true, dynacast: true,});

  this.start = () => {
    let url = localStorage.getItem('livekit_url');
    let token = localStorage.getItem('livekit_token');
    let expiration = localStorage.getItem('livekit_expiration');

    if (!url || !token || !expiration || new Date() > new Date(expiration)) {
      const signedInUser = JSON.parse(localStorage.getItem(localStorageKeys.user))
      const userEmail = signedInUser.email;
      console.log("calling /api/createToken")
      axios.post('/api/createToken', { roomName: userEmail, participantName: "Web" })
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

  const connectToRoom = (url, token) => {
    // pre-warm connection, this can be called as early as your page is loaded
    room.prepareConnection(url, token);
    // Connect to the LiveKit room.
    room.connect(url, token)
    .then(() => {
      console.log('Connected to room:', room.name);
    })
    .catch((error) => {
      console.error('Error connecting to room:', error);
    });
  }

  this.sendToBot = () => {
    if (msg === undefined || msg === null) {
      return
    }

    // publish to topic
  }

  

  this.setupListeners = () => {
    room.on(RoomEvent.TrackSubscribed, (track, publication, participant) => {
      if (track.kind === Track.Kind.Video) {
        const streamElement = track.attach();
        const videoElement = document.getElementById('video')
        videoElement.srcObject = streamElement.srcObject;
      }
    });
  }
}
