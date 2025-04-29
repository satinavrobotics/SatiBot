import {Commands} from './commands'
import {RemoteKeyboard} from './remote_keyboard'

export function Gamepad() {
    let gamepadIndex = null;
    let connectionStateCallback = null;
    
    this.setConnectionStateCallback = (callback) => {
        connectionStateCallback = callback;
        // Immediately call callback with current state if one is connected
        const gamepads = navigator.getGamepads();
        for (let i = 0; i < gamepads.length; i++) {
            if (gamepads[i] && gamepads[i].connected) {
                callback(true);
                break;
            }
        }
    };
    
    this.start = (onGamePadInput) => {
        let animationFrameId = null;
        
        const pollGamepads = () => {
            if (gamepadIndex !== null) {
                const gamepad = navigator.getGamepads()[gamepadIndex];
                if (gamepad && gamepad.connected) {
                    onGamePadInput(gamepad);
                } else {
                    // Gamepad was disconnected
                    handleGamepadDisconnected(gamepadIndex);
                }
            }
            animationFrameId = requestAnimationFrame(pollGamepads);
        };

        const handleGamepadDisconnected = (index) => {
            if (gamepadIndex === index) {
                gamepadIndex = null;
                showMessage('Gamepad Disconnected');
                if (animationFrameId) {
                    cancelAnimationFrame(animationFrameId);
                    animationFrameId = null;
                }
                if (connectionStateCallback) {
                    connectionStateCallback(false);
                }
            }
        };

        const showMessage = (message) => {
            const messageElement = document.getElementById('input-mode-message');
            if (messageElement) {
                messageElement.textContent = message;
                messageElement.style.display = 'block';
                setTimeout(() => {
                    messageElement.style.display = 'none';
                }, 1000);
            }
        };

        window.addEventListener('gamepadconnected', (event) => {
            console.log('Gamepad connected:', event.gamepad);
            gamepadIndex = event.gamepad.index;
            showMessage('Gamepad Connected');
            if (!animationFrameId) {
                pollGamepads();
            }
            if (connectionStateCallback) {
                connectionStateCallback(true);
            }
        });

        window.addEventListener('gamepaddisconnected', (event) => {
            console.log('Gamepad disconnected:', event.gamepad);
            handleGamepadDisconnected(event.gamepad.index);
        });
        
        // Check if a gamepad is already connected
        const gamepads = navigator.getGamepads();
        for (let i = 0; i < gamepads.length; i++) {
            if (gamepads[i] && gamepads[i].connected) {
                console.log('Gamepad already connected:', gamepads[i]);
                gamepadIndex = gamepads[i].index;
                if (!animationFrameId) {
                    pollGamepads();
                }
                if (connectionStateCallback) {
                    connectionStateCallback(true);
                }
                break;
            }
        }
    };

    this.isGamepadConnected = () => {
        if (gamepadIndex === null) return false;
        const gamepads = navigator.getGamepads();
        return gamepads[gamepadIndex] && gamepads[gamepadIndex].connected;
    };
}
