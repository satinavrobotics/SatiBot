import {initializeApp} from 'firebase/app'
import {getAuth, signOut, signInWithPopup, GoogleAuthProvider} from 'firebase/auth'
import {getFirestore} from '@firebase/firestore'
import {getStorage} from 'firebase/storage'
import {localStorageKeys} from '../utils/constants'

// Your web app's Firebase configuration
const firebaseConfig = {
    apiKey: import.meta.env.SNOWPACK_PUBLIC_FIREBASE_API_KEY,
    authDomain: import.meta.env.SNOWPACK_PUBLIC_AUTH_DOMAIN,
    projectId: import.meta.env.SNOWPACK_PUBLIC_PROJECT_ID,
    storageBucket: import.meta.env.SNOWPACK_PUBLIC_STORAGE_BUCKET,
    messagingSenderId: import.meta.env.SNOWPACK_PUBLIC_MESSAGING_SENDER_ID,
    appId: import.meta.env.SNOWPACK_PUBLIC_APP_ID,
    measurementId: import.meta.env.SNOWPACK_PUBLIC_MEASUREMENT_ID,
}

const googleApiClientID = "154487542187-eca7ghfm2vepoaglvjurdalu3c6tntjr.apps.googleusercontent.com"
console.log(googleApiClientID)

const app = initializeApp(firebaseConfig)
export const auth = getAuth(app)
const provider = new GoogleAuthProvider()
export const FirebaseStorage = getStorage()
export const db = getFirestore(app)

/**
 * Function to initialize Google API
 * @returns {Promise<void>}
 */
export async function initializeGoogleAPI() {
    return new Promise((resolve, reject) => {
        gapi.load('auth2', () => {
            try {
                // Initialize auth2 with your client ID
                gapi.auth2.init({
                    client_id: googleApiClientID,
                }).then(() => {
                    console.log('Google Auth initialized');
                    resolve();  // Resolve after successful initialization
                }).catch((error) => {
                    console.error('Error initializing Google Auth', error);
                    reject(error);  // Reject if there's an error
                });
            } catch (err) {
                reject(err);  // Handle unexpected errors
            }
        });
    });
}

/**
 * Function to google Sign in
 * @returns {Promise<unknown>}
 */
export function googleSigIn() {
    return new Promise((resolve, reject) => {
        provider.addScope('https://www.googleapis.com/auth/drive.metadata.readonly'); // Request Drive API access
        signInWithPopup(auth, provider)
            .then((result) => {
                const credential = GoogleAuthProvider.credentialFromResult(result);
                const accessToken = credential.accessToken; // Get access token
                console.log(accessToken)
                if (accessToken) {
                    localStorage.setItem('driveAccessToken', accessToken); // Store access token
                }

                resolve(result.user);
            })
            .catch((error) => {
                reject(error);
            });
    });
}

/**
 * function to log out user from Google account
 * @returns {Promise<void>}
 */
export function googleSignOut () {
    signOut(auth).then(() => {
        localStorage.setItem(localStorageKeys.isSignIn, 'false')
    }).catch((error) => {
        console.log('Sign-out error ', error)
    })
}
