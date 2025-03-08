import {Commands} from './commands'
import {RemoteKeyboard} from './remote_keyboard'

export function Gamepad(callback) {
    let gamepadIndex = null;
    
    this.start = (onGamePadInput) => {
        let animationFrameId = null;
        
        // Function to continuously poll gamepad state
        const pollGamepads = () => {
            if (gamepadIndex !== null) {
                const gamepad = navigator.getGamepads()[gamepadIndex];
                if (gamepad) {
                    onGamePadInput(gamepad);
                }
            }
            animationFrameId = requestAnimationFrame(pollGamepads);
        };

        window.addEventListener('gamepadconnected', (event) => {
            console.log('Gamepad connected:', event.gamepad);
            gamepadIndex = event.gamepad.index;
            // Start polling when gamepad connects
            if (!animationFrameId) {
                pollGamepads();
            }
        });
      
        window.addEventListener('gamepaddisconnected', (event) => {
            console.log('Gamepad disconnected:', event.gamepad);
            if (gamepadIndex === event.gamepad.index) {
                gamepadIndex = null;
                // Stop polling when gamepad disconnects
                if (animationFrameId) {
                    cancelAnimationFrame(animationFrameId);
                    animationFrameId = null;
                }
            }
        });
        
        // Check if a gamepad is already connected
        const gamepads = navigator.getGamepads();
        for (let i = 0; i < gamepads.length; i++) {
            if (gamepads[i]) {
                console.log('Gamepad already connected:', gamepads[i]);
                gamepadIndex = gamepads[i].index;
                // Start polling for already connected gamepad
                if (!animationFrameId) {
                    pollGamepads();
                }
                break;
            }
        }
    }

  }
