/*
 * Developed for the OpenBot project (https://openbot.org) by:
 *
 * Ivo Zivkov
 * izivkov@gmail.com
 *
 * Date: Mon Nov 29 2021
 */

export function Commands (sendCommand, sendDriveCmd) {
  const commandHandler = new CommandHandler(sendCommand, sendDriveCmd)

  this.getCommandHandler = () => {
    return commandHandler
  }
}

function DriveValue () {
  const MAX = 1.0
  const MIN = -1.0

  let value = 0.0

  this.reset = () => {
    value = 0
    return value
  }

  this.max = () => {
    value = MAX
    return value
  }

  this.min = () => {
    value = MIN
    return value
  }

  this.write = _value => {
    value = _value
    return value
  }

  this.read = () => {
    return Math.round(value, 3)
  }
}

export function CommandHandler (sendCommand, sendDriveCmd) {
  const left = new DriveValue()
  const right = new DriveValue()
  const commandReducer = new DriveCommandReducer()
  
  // Constants for velocity scaling
  const BASE_UPDATE_RATE = 60 // Base rate in Hz
  const MAX_LINEAR_VELOCITY = 1.0
  const MAX_ANGULAR_VELOCITY = 1.0
  const UPDATE_RATE = 1000 / 16 // Convert from ms to Hz (16ms = ~60Hz)
  
  // Scale factors based on update frequency
  const linearScale = (MAX_LINEAR_VELOCITY * BASE_UPDATE_RATE) / UPDATE_RATE
  const angularScale = (MAX_ANGULAR_VELOCITY * BASE_UPDATE_RATE) / UPDATE_RATE

  this.sendCommand = (cmd) => sendCommand(cmd)

  const sendWheelCommand = (left, right) => {
    sendDriveCmd({ l: left, r: right })
  }

  const sendSteeringCommand = (linear, angular) => {
    // Apply scaling to the velocities
    const scaledLinear = linear * linearScale
    const scaledAngular = angular * angularScale
    sendDriveCmd({ l: scaledLinear, a: scaledAngular })
  }

  this.gamepadCommand = (linear, angular) => {
    sendSteeringCommand(linear, angular)
  }

  this.reset = () => {
    left.reset()
    right.reset()
    sendWheelCommand(0, 0)
  }

  this.forwardLeft = () => {
    sendWheelCommand(left.min() / 2, right.max())
  }

  this.forwardRight = () => {
    sendWheelCommand(left.max(), right.min() / 2)
  }

  this.backwardLeft = () => {
    sendWheelCommand(left.max() / 2, right.min())
  }

  this.backwardRight = () => {
    sendWheelCommand(left.min(), right.max() / 2)
  }

  this.rotateLeft = () => {
    sendWheelCommand(left.min(), right.max())
  }

  this.rotateRight = () => {
    sendWheelCommand(left.max(), right.min())
  }

  this.goForward = () => {
    sendWheelCommand(left.max(), right.max())
  }

  this.goBackward = () => {
    sendWheelCommand(left.min(), right.min())
  }
}

// Utility function to reduce number of commands being sent to the robot
// by not sending duplicate consecutive commands.
function DriveCommandReducer () {
  let lastCommand = null

  this.send = (command, sendToBot) => {
    if (isEqual(command, lastCommand)) {
      return
    }
    lastCommand = command
    sendToBot(command)
  }

  const isEqual = (current, last) => {
    if (!last || !current) {
      return false
    }
    return last.l === current.l && last.r === current.r
  }
}
