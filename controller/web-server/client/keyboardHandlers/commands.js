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

  this.sendCommand = (cmd) => sendCommand(cmd)

  const sendDriveCommand = (left, right) => {
    commandReducer.send({ l: left, r: right }, sendDriveCmd)
  }

   this.gamepadCommand = (left, right) => {
    sendDriveCommand(left, right)
  }

  this.reset = () => {
    left.reset()
    right.reset()
    sendDriveCommand(0, 0)
  }

  this.forwardLeft = () => {
    sendDriveCommand(left.min() / 2, right.max())
  }

  this.forwardRight = () => {
    sendDriveCommand(left.max(), right.min() / 2)
  }

  this.backwardLeft = () => {
    sendDriveCommand(left.max() / 2, right.min())
  }

  this.backwardRight = () => {
    sendDriveCommand(left.min(), right.max() / 2)
  }

  this.rotateLeft = () => {
    sendDriveCommand(left.min(), right.max())
  }

  this.rotateRight = () => {
    sendDriveCommand(left.max(), right.min())
  }

  this.goForward = () => {
    sendDriveCommand(left.max(), right.max())
  }

  this.goBackward = () => {
    sendDriveCommand(left.min(), right.min())
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
