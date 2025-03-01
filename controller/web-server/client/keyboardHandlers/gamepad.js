import {Commands} from './commands'
import {RemoteKeyboard} from './remote_keyboard'

export function initializeGamepad(callback) {
    let gamepadIndex = null;
    console.log('Initializing gamepad...');
  
    window.addEventListener('gamepadconnected', (event) => {
        console.log('Gamepad connected:', event.gamepad);
        gamepadIndex = event.gamepad.index;
        startGamepadPolling();
    });
  
    window.addEventListener('gamepaddisconnected', (event) => {
        console.log('Gamepad disconnected:', event.gamepad);
        gamepadIndex = null;
    });
  
    function startGamepadPolling() {
        if (gamepadIndex !== null) {
            const gamepad = navigator.getGamepads()[gamepadIndex];
            if (gamepad) {
                processGamepadInput(gamepad);
            }
        }
        requestAnimationFrame(startGamepadPolling);
    }
  
    function processGamepadInput(gamepad) {
      const command = new Commands(callback);
      const remoteKeyboard = new RemoteKeyboard(command.getCommandHandler());

      remoteKeyboard.processGamepad(gamepad);
    }
  }
