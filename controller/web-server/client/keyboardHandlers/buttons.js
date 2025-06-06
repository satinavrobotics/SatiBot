/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: Mon Nov 29 2021
 */

export function Buttons (connection, available_cameras) {
  // Show buttons now that camera information has arrived
  const buttonsContainer = document.getElementById('buttons');
  if (buttonsContainer) {
    buttonsContainer.style.display = 'flex';
  }

  // MIRROR
  const toggleMirror = () => {
    const video = document.getElementById('video')
    const isMirrored = video.style.cssText !== ''
    // 'translateX' changes between -50% and +50% to keep video centered
    video.style.cssText = !isMirrored
      ? '-moz-transform: scale(-1, 1) translateX(50%); -webkit-transform: scale(-1, 1) translateX(50%); -o-transform: scale(-1, 1) translateX(50%); transform: scale(-1, 1) translateX(50%); filter: FlipH;'
      : 'translateX(-50%)'

    document.getElementById('mirror_button').src = !isMirrored ? 'icons/flip_black_24dp-mirrored.svg' : 'icons/flip_black_24dp.svg'
  }
  const mirrorButton = document.getElementById('mirror_button')
  mirrorButton.onclick = toggleMirror

  // SOUND
  const toggleSound = async () => {
    const isMuted = await connection.toggleAudio();
    const soundButton = document.getElementById('sound_button');
    if (soundButton) {
      soundButton.src = isMuted ? 'icons/volume_off_black_24dp.svg' : 'icons/volume_up_black_24dp.svg';
    }
  }

  const soundButton = document.getElementById('sound_button')
  soundButton.onclick = toggleSound

  // FLASHLIGHT
  /*
  const toggleFlashlight = () => {

    const flashlightButton = document.getElementById('flashlight_button')
    flashlightButton.classList.add('button-disabled')
    console.log("Flashlight button toggled")

    // Call the switchFlashlight method from LiveKitClient
    connection.switchFlashlight()
      .then(response => {
        const result = JSON.parse(response)
        console.log(result)
        // Handle the result from the switchFlashlight method
        if (result) {
          // Update button icon based on flashlight state
          flashlightButton.src = result.state ? 'icons/flashlight_on.png' : 'icons/flashlight_off.png'
        } else {
          console.error('Failed to toggle flashlight')
        }
      })
      .catch(error => {
        console.error('Flashlight toggle failed:', error)
      })
      .finally(() => {
        flashlightButton.classList.remove('button-disabled')
      })
  }

  const flashlightButton = document.getElementById('flashlight_button')
  if (flashlightButton) {
    flashlightButton.onclick = toggleFlashlight
  }
    */

  // CAMERAS
  const mainCameraButton = document.getElementById('camera_switch_main')
  const wideCameraButton = document.getElementById('camera_switch_wide')
  const telephotoCameraButton = document.getElementById('camera_switch_telephoto')
  const frontCameraButton = document.getElementById('camera_switch_front')
  const externalCameraButton = document.getElementById('camera_switch_external')
  const arCameraButton = document.getElementById('camera_switch_ar')

  arCameraButton.onclick = () => connection.switchCamera("AR_CORE")

  if (available_cameras.hasOwnProperty("main")) {
    mainCameraButton.onclick = () => connection.switchCamera(available_cameras.main)
  } else {
    mainCameraButton.remove();
  }

  if (available_cameras.hasOwnProperty("wide")) {
    wideCameraButton.onclick = () => connection.switchCamera(available_cameras.wide)
  } else {
    wideCameraButton.remove();
  }

  if (available_cameras.hasOwnProperty("telephoto")) {
    telephotoCameraButton.onclick = () => connection.switchCamera(available_cameras.telephoto)
  } else {
    telephotoCameraButton.remove();
  }

  if (available_cameras.hasOwnProperty("front")) {
    frontCameraButton.onclick = () => connection.switchCamera(available_cameras.front)
  } else {
    frontCameraButton.remove();
  }

  if (available_cameras.hasOwnProperty("external")) {
    externalCameraButton.onclick = () => connection.switchCamera(available_cameras.external)
  } else {
    // External camera button always available, uses "EXTERNAL" command
    externalCameraButton.onclick = () => connection.switchCamera("EXTERNAL")
  }

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
    const toggleRecordingButton = document.getElementById('toggle-missions');
    const recordingPanel = document.getElementById('recording-panel');


    toggleButton.addEventListener('click', () => {
        if (commandContainer.style.display === 'none') {
            commandContainer.style.display = 'block';
            mapPanel.style.display = 'none';
            recordingPanel.style.display = 'none';
        } else {
            commandContainer.style.display = 'none';
        }
    });

    toggleMapButton.addEventListener('click', () => {
        if (mapPanel.style.display === 'none') {
            mapPanel.style.display = 'block';
            commandContainer.style.display = 'none';
            recordingPanel.style.display = 'none';
        } else {
            mapPanel.style.display = 'none';
        }
    });

    toggleRecordingButton.addEventListener('click', () => {
        if (recordingPanel.style.display === 'none') {
            recordingPanel.style.display = 'block';
            commandContainer.style.display = 'none';
            mapPanel.style.display = 'none';
        } else {
            recordingPanel.style.display = 'none';
        }
    });
});
