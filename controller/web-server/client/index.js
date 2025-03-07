import {Keyboard} from './keyboardHandlers/keyboard.js'
import {Commands} from './keyboardHandlers/commands'
import {RemoteKeyboard} from './keyboardHandlers/remote_keyboard'
import {signInWithCustomToken} from 'firebase/auth'
import {auth, googleSigIn, googleSignOut} from './firebase/authentication'
import {localStorageKeys} from './utils/constants'
import {Gamepad } from './keyboardHandlers/gamepad.js'
import {LiveKitClient} from './livekit/livekit.js'


const connection = new LiveKitClient();
(async () => {
    const keyboard = new Keyboard()
    const gamepad = new Gamepad()

    // connect to room
    await connection.start()
    const command = new Commands(
        (cmd)=>connection.sendCommand(cmd), 
        (cmd)=>connection.sendDriveCommand(cmd)
    )
    const remoteKeyboard = new RemoteKeyboard(command.getCommandHandler())
    const onKeyPress = (key) => remoteKeyboard.processKey(key)
    const onGamePadInput = (gamepad) => remoteKeyboard.processGamepad(gamepad)

    keyboard.start(onKeyPress, () => connection.stop())
    gamepad.start(onGamePadInput)
})()

export let signedInUser = JSON.parse(localStorage.getItem(localStorageKeys.user))

const signInButton = document.getElementsByClassName('google-sign-in-button')[0]
signInButton.addEventListener('click', handleSignInButtonClick)
const cancelButton = document.getElementById('logout-cancel-button')
const okButton = document.getElementById('logout-ok-button')
cancelButton.addEventListener('click', handleCancelButtonClick)
okButton.addEventListener('click', handleOkButtonClick)
const subscribeButton = document.getElementById('subscribe-button')
subscribeButton.addEventListener('click', handleSubscription)


/**
 * function to handle signIn on home page
 */
function handleSignInButtonClick() {
    if (localStorage.getItem(localStorageKeys.isSignIn) === 'false') {
        googleSigIn()
            .then((user) => {
                // Use the user data or store it in a variable for later use
                signedInUser = user
                const signInBtn = document.getElementsByClassName('google-sign-in-button')[0]
                signInBtn.innerText = user.displayName
                localStorage.setItem(localStorageKeys.user, JSON.stringify(user))
                localStorage.setItem(localStorageKeys.isSignIn, true.toString())
                sendId()
            })
            .catch((error) => {
                // Handle any errors that might occur during sign-in
                console.error('Error signing in:', error)
            })
    } else {
        showLogoutWrapper()
        hideExpirationWrapper()
    }
}

/**
 * function to sendId to remote server
 */
function sendId() {
    const response = {
        roomId: signedInUser.email
    }
    //connection.send(JSON.stringify(response))
}

/**
 * function to handle signOut from google account
 */
function signOut() {
    signedInUser = null
    localStorage.setItem(localStorageKeys.user, null)
    localStorage.setItem(localStorageKeys.isSignIn, false.toString())
    const signInBtn = document.getElementsByClassName('google-sign-in-button')[0]
    signInBtn.innerText = 'Sign in with Google'
    googleSignOut()
}

/**
 * function to handle cancel button on logout popup
 */
function handleCancelButtonClick() {
    hideLogoutWrapper()
}

/**
 * function to hide logout popup
 */
function hideLogoutWrapper() {
    const logout = document.getElementsByClassName('logout-wrapper')[0]
    logout.style.display = 'none'
}

/**
 * function to display logout popup
 */
function showLogoutWrapper() {
    const logout = document.getElementsByClassName('logout-wrapper')[0]
    logout.style.display = 'block'
}

/**
 * function to display expiration popup
 */
function showExpirationWrapper() {
    const expire = document.getElementsByClassName('plan-expiration-model')[0]
    expire.style.display = 'block'
}

/**
 * function to hide logout popup
 */
function hideExpirationWrapper() {
    const expire = document.getElementsByClassName('plan-expiration-model')[0]
    expire.style.display = 'none'
}

/**
 * function to handle "ok" button for logout popup
 */
function handleOkButtonClick() {
    hideLogoutWrapper()
    signOut()
}

/**
 * function to handle subscribe now button
 */
function handleSubscription() {
    console.log('Navigate to subscription page')
}

/**
 * function to get cookie from browser storage
 * @param cname
 * @returns {string}
 */
export function getCookie(cname) {
    const name = cname + '='
    const decodedCookie = decodeURIComponent(document.cookie)
    const ca = decodedCookie.split(';')
    for (let i = 0; i < ca.length; i++) {
        let c = ca[i]
        while (c.charAt(0) === ' ') {
            c = c.substring(1)
        }
        if (c.indexOf(name) === 0) {
            return c.substring(name.length, c.length)
        }
    }
    return ''
}

/**
 * function to delete cookie from browser storage
 * @param name
 */
export const deleteCookie = (name) => {
    document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:01 GMT;'
}


handleServerDetailsOnSSO()
handleAuthChangedOnRefresh()

/**
 * function to handle single sign on from openbot dashboard
 */
function handleSingleSignOn() {
    const cookie = getCookie(localStorageKeys.user)
    if (cookie) {
        const result = cookie
        localStorage.setItem(localStorageKeys.isSignIn, 'true')
        signInWithCustomToken(auth, result).then((res) => {
            // Use the user data or store it in a variable for later use
            signedInUser = res.user
            const signInBtn = document.getElementsByClassName('google-sign-in-button')[0]
            signInBtn.innerText = res.user.displayName
            localStorage.setItem(localStorageKeys.user, JSON.stringify(res.user))
            localStorage.setItem(localStorageKeys.isSignIn, true.toString())
            deleteCookie(localStorageKeys.user)
        })
            .catch((error) => {
                console.log('error::', error)
            })
    }
}


function handleServerDetailsOnSSO() {
    const cookie = getCookie(localStorageKeys.user)
    if (cookie) {
        handleSingleSignOn()
    }
}

/**
 * function to handle access token
 */
function handleAccessToken() {
    const tokenCookie = getCookie('accessToken')
    if (tokenCookie) {
        deleteCookie('accessToken')
    }
}

/**
 * function to handle auth status on refreshing page
 */
function handleAuthChangedOnRefresh() {
    if (localStorage.getItem(localStorageKeys.isSignIn) === 'true') {
        setTimeout(() => {
            auth.onAuthStateChanged((res) => {
                if (res != null) {
                    signedInUser = res
                    const signInBtn = document.getElementsByClassName('google-sign-in-button')[0]
                    signInBtn.innerText = res.displayName
                    localStorage.setItem(localStorageKeys.user, JSON.stringify(res))
                    localStorage.setItem(localStorageKeys.isSignIn, 'true')
                    sendId()
                }
            })
        }, 1000)
    }
}

/**
 * function to check whether user subscription expires or not
 */
export function checkPlanExpiration() {
    if (localStorage.getItem(localStorageKeys.isSignIn) === 'true') {
        if (getCookie(localStorageKeys.subscriptionEndTime)) {
            const endTimeCheckInterval = setInterval(() => {
                const currentTime = new Date()
                // Check if the end time has been reached
                if (currentTime >= new Date(decodeURIComponent(getCookie(localStorageKeys.subscriptionEndTime)))) {
                    clearInterval(endTimeCheckInterval)
                    showExpirationWrapper()
                }
            }, 100) // 1 minute in milliseconds
        }
    }
}

/**
 * function to restrict user for sending room key to remote server
 */
export function restrictUserOnExpiration() {
    if (getCookie(localStorageKeys.subscriptionEndTime)) {
        const currentTime = new Date()
        if (currentTime < new Date(decodeURIComponent(getCookie(localStorageKeys.subscriptionEndTime)))) {
            sendId()
        }
    }
}
