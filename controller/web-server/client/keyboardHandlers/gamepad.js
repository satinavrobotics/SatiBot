import {Commands} from './commands'
import {RemoteKeyboard} from './remote_keyboard'

export function Gamepad(callback) {
    let gamepadIndex = null;
    
    this.start = (onGamePadInput) => {

        window.addEventListener('gamepadconnected', (event) => {
            console.log('Gamepad connected:', event.gamepad);
            gamepadIndex = event.gamepad.index;
            if (gamepadIndex !== null) {
                const gamepad = navigator.getGamepads()[gamepadIndex];
                if (gamepad) {
                    console.log(gamepad)
                    onGamePadInput(gamepad)
                }
            }
            requestAnimationFrame(this.start);
        });
      
        window.addEventListener('gamepaddisconnected', (event) => {
            console.log('Gamepad disconnected:', event.gamepad);
            gamepadIndex = null;
        });
    }

  }
