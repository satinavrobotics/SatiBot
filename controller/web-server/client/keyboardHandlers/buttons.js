/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: Mon Nov 29 2021
 */

export function Buttons (connection, camera_metadata) {
  // MIRROR
  const toggleMirror = () => {
    const video = document.getElementById('video')
    const isMirrored = video.style.cssText !== ''
    this.setMirrored(!isMirrored)
    // 'translateX' changes between -50% and +50% to keep video centered
    video.style.cssText = !isMirrored
      ? '-moz-transform: scale(-1, 1) translateX(50%); -webkit-transform: scale(-1, 1) translateX(50%); -o-transform: scale(-1, 1) translateX(50%); transform: scale(-1, 1) translateX(50%); filter: FlipH;'
      : 'translateX(-50%)'

    document.getElementById('mirror_button').src = !isMirrored ? 'icons/flip_black_24dp-mirrored.svg' : 'icons/flip_black_24dp.svg'
  }
  const mirrorButton = document.getElementById('mirror_button')
  mirrorButton.onclick = toggleMirror

  // SOUND
  // sound button. toggle 'muted' flag on the video control
  const toggleSound = () => {
    const video = document.getElementById('video')
    video.muted = !video.muted
    document.getElementById('sound_button').src = video.muted ? 'icons/volume_off_black_24dp.svg' : 'icons/volume_up_black_24dp.svg'
  }
  const soundButton = document.getElementById('sound_button')
  soundButton.onclick = toggleSound


  this.setMirrored = mirrored => {
  }

  // camera switch
  const switchCamera = () => {
    connection.send(JSON.stringify({ command: 'SWITCH_CAMERA' }));
  }

  const cameraSwitchButton = document.getElementById('camera_switch_button')
  cameraSwitchButton.onclick = switchCamera

  // fullscreen
  const goFullscreen = () => {
    const video = document.getElementById('video')
    if (video.requestFullscreen) {
      video.requestFullscreen()
    } else if (video.webkitRequestFullscreen) { /* Safari */
      video.webkitRequestFullscreen()
    } else if (video.msRequestFullscreen) { /* IE11 */
      video.msRequestFullscreen()
    }
  }

  const fullscreenButton = document.getElementById('fullscreen')
  fullscreenButton.onclick = goFullscreen
}


document.addEventListener('DOMContentLoaded', () => {
    const toggleButton = document.getElementById('toggle-controls');
    const commandContainer = document.getElementById('command-container');
    const toggleMapButton = document.getElementById('toggle-map');
    const mapPanel = document.getElementById('map-panel');
    const toggleMissionButton = document.getElementById('toggle-missions');
    const missionPanel = document.getElementById('mission-panel');


    toggleButton.addEventListener('click', () => {
        if (commandContainer.style.display === 'none') {
            commandContainer.style.display = 'block';
            mapPanel.style.display = 'none';
            missionPanel.style.display = 'none';
        } else {
            commandContainer.style.display = 'none';
        }
    });

    toggleMapButton.addEventListener('click', () => {
        if (mapPanel.style.display === 'none') {
            mapPanel.style.display = 'block';
            commandContainer.style.display = 'none';
            missionPanel.style.display = 'none';
        } else {
            mapPanel.style.display = 'none';
        }
    });

    toggleMissionButton.addEventListener('click', () => {
        if (missionPanel.style.display === 'none') {
            missionPanel.style.display = 'block';
            commandContainer.style.display = 'none';
            mapPanel.style.display = 'none';
        } else {
            missionPanel.style.display = 'none';
        }
    });
});
