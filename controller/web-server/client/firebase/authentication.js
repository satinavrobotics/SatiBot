import {getFirestore} from '@firebase/firestore'
import {getStorage} from 'firebase/storage'
import {localStorageKeys} from '../utils/constants'
import {GoogleAuth} from './googleAuth'

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

// Initialize the GoogleAuth module with Firebase config and options
const googleAuthOptions = {
    googleApiClientID: googleApiClientID,
    scopes: ['https://www.googleapis.com/auth/drive.metadata.readonly'],
    storageKeys: localStorageKeys
};

// Create a new GoogleAuth instance
const googleAuthInstance = new GoogleAuth(firebaseConfig, googleAuthOptions);

// Export the auth object for compatibility with existing code
export const auth = googleAuthInstance.auth;
export const FirebaseStorage = getStorage(googleAuthInstance.app);
export const db = getFirestore(googleAuthInstance.app);

/**
 * Function to initialize Google API
 * @returns {Promise<void>}
 */
export async function initializeGoogleAPI() {
    return googleAuthInstance.initializeGoogleAPI();
}

/**
 * Function to google Sign in
 * @returns {Promise<unknown>}
 */
export function googleSigIn() {
    return googleAuthInstance.signIn();
}

/**
 * function to log out user from Google account
 * @returns {Promise<void>}
 */
export function googleSignOut() {
    return googleAuthInstance.signOut();
}
