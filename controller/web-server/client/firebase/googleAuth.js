/**
 * Google Authentication Module
 * 
 * A reusable module for handling Google authentication in web applications.
 * This module provides a simple API for Google sign-in, sign-out, and auth state management.
 */

import { initializeApp } from 'firebase/app';
import { 
  getAuth, 
  signOut, 
  signInWithPopup, 
  signInWithCustomToken,
  GoogleAuthProvider, 
  onAuthStateChanged 
} from 'firebase/auth';

/**
 * GoogleAuth class for handling Google authentication
 */
export class GoogleAuth {
  /**
   * Create a new GoogleAuth instance
   * @param {Object} config - Firebase configuration object
   * @param {Object} options - Additional options
   * @param {string} options.googleApiClientID - Google API Client ID (optional)
   * @param {Array<string>} options.scopes - Additional scopes to request (optional)
   * @param {Object} options.storageKeys - Custom storage keys (optional)
   * @param {string} options.storageType - Storage type: 'local' or 'session' (default: 'local')
   */
  constructor(config, options = {}) {
    this.config = config;
    this.options = {
      googleApiClientID: options.googleApiClientID || null,
      scopes: options.scopes || [],
      storageKeys: options.storageKeys || {
        isSignIn: 'isSignIn',
        user: 'user',
        accessToken: 'googleAccessToken'
      },
      storageType: options.storageType || 'local'
    };

    // Initialize Firebase
    this.app = initializeApp(config);
    this.auth = getAuth(this.app);
    this.provider = new GoogleAuthProvider();
    
    // Add requested scopes
    this.options.scopes.forEach(scope => {
      this.provider.addScope(scope);
    });

    // Current user state
    this.currentUser = null;
    
    // Storage reference
    this.storage = this.options.storageType === 'session' ? sessionStorage : localStorage;
    
    // Initialize user from storage if available
    const storedUser = this.storage.getItem(this.options.storageKeys.user);
    if (storedUser && storedUser !== 'null') {
      try {
        this.currentUser = JSON.parse(storedUser);
      } catch (e) {
        console.error('Error parsing stored user:', e);
      }
    }
  }

  /**
   * Initialize Google API (for advanced use cases)
   * @returns {Promise<void>}
   */
  async initializeGoogleAPI() {
    if (!this.options.googleApiClientID) {
      throw new Error('Google API Client ID is required for initialization');
    }

    return new Promise((resolve, reject) => {
      if (typeof gapi === 'undefined') {
        reject(new Error('Google API (gapi) not loaded. Include the Google API script in your HTML.'));
        return;
      }

      gapi.load('auth2', () => {
        try {
          gapi.auth2.init({
            client_id: this.options.googleApiClientID,
          }).then(() => {
            console.log('Google Auth initialized');
            resolve();
          }).catch((error) => {
            console.error('Error initializing Google Auth', error);
            reject(error);
          });
        } catch (err) {
          reject(err);
        }
      });
    });
  }

  /**
   * Sign in with Google
   * @returns {Promise<Object>} User object
   */
  signIn() {
    return new Promise((resolve, reject) => {
      signInWithPopup(this.auth, this.provider)
        .then((result) => {
          const credential = GoogleAuthProvider.credentialFromResult(result);
          const accessToken = credential.accessToken;
          
          // Store access token if available
          if (accessToken) {
            this.storage.setItem(this.options.storageKeys.accessToken, accessToken);
          }
          
          // Store user info
          this.currentUser = result.user;
          this.storage.setItem(this.options.storageKeys.user, JSON.stringify(result.user));
          this.storage.setItem(this.options.storageKeys.isSignIn, 'true');
          
          resolve(result.user);
        })
        .catch((error) => {
          reject(error);
        });
    });
  }

  /**
   * Sign out from Google
   * @returns {Promise<void>}
   */
  signOut() {
    return new Promise((resolve, reject) => {
      signOut(this.auth)
        .then(() => {
          // Clear user data
          this.currentUser = null;
          this.storage.setItem(this.options.storageKeys.user, null);
          this.storage.setItem(this.options.storageKeys.isSignIn, 'false');
          this.storage.removeItem(this.options.storageKeys.accessToken);
          
          resolve();
        })
        .catch((error) => {
          console.error('Sign-out error:', error);
          reject(error);
        });
    });
  }

  /**
   * Sign in with a custom token
   * @param {string} token - Custom token
   * @returns {Promise<Object>} User object
   */
  signInWithToken(token) {
    return new Promise((resolve, reject) => {
      signInWithCustomToken(this.auth, token)
        .then((result) => {
          // Store user info
          this.currentUser = result.user;
          this.storage.setItem(this.options.storageKeys.user, JSON.stringify(result.user));
          this.storage.setItem(this.options.storageKeys.isSignIn, 'true');
          
          resolve(result.user);
        })
        .catch((error) => {
          reject(error);
        });
    });
  }

  /**
   * Get the current user
   * @returns {Object|null} User object or null if not signed in
   */
  getUser() {
    return this.currentUser;
  }

  /**
   * Check if user is signed in
   * @returns {boolean} True if signed in
   */
  isSignedIn() {
    return this.storage.getItem(this.options.storageKeys.isSignIn) === 'true';
  }

  /**
   * Get access token if available
   * @returns {string|null} Access token or null
   */
  getAccessToken() {
    return this.storage.getItem(this.options.storageKeys.accessToken);
  }

  /**
   * Listen for auth state changes
   * @param {Function} callback - Callback function(user)
   * @returns {Function} Unsubscribe function
   */
  onAuthStateChanged(callback) {
    return onAuthStateChanged(this.auth, (user) => {
      if (user) {
        this.currentUser = user;
        this.storage.setItem(this.options.storageKeys.user, JSON.stringify(user));
        this.storage.setItem(this.options.storageKeys.isSignIn, 'true');
      } else {
        this.currentUser = null;
      }
      
      callback(user);
    });
  }

  /**
   * Get a cookie value by name
   * @param {string} name - Cookie name
   * @returns {string} Cookie value
   */
  getCookie(name) {
    const cookieName = name + '=';
    const decodedCookie = decodeURIComponent(document.cookie);
    const cookieArray = decodedCookie.split(';');
    
    for (let i = 0; i < cookieArray.length; i++) {
      let cookie = cookieArray[i];
      while (cookie.charAt(0) === ' ') {
        cookie = cookie.substring(1);
      }
      if (cookie.indexOf(cookieName) === 0) {
        return cookie.substring(cookieName.length, cookie.length);
      }
    }
    return '';
  }

  /**
   * Delete a cookie by name
   * @param {string} name - Cookie name
   */
  deleteCookie(name) {
    document.cookie = name + '=;expires=Thu, 01 Jan 1970 00:00:01 GMT;';
  }
}
