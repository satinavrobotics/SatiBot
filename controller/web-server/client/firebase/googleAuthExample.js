/**
 * Example usage of the GoogleAuth module
 * 
 * This file demonstrates how to use the GoogleAuth module in a project.
 */

import { GoogleAuth } from './googleAuth.js';

// Firebase configuration
const firebaseConfig = {
  apiKey: "YOUR_API_KEY",
  authDomain: "YOUR_AUTH_DOMAIN",
  projectId: "YOUR_PROJECT_ID",
  storageBucket: "YOUR_STORAGE_BUCKET",
  messagingSenderId: "YOUR_MESSAGING_SENDER_ID",
  appId: "YOUR_APP_ID",
  measurementId: "YOUR_MEASUREMENT_ID"
};

// Optional configuration
const options = {
  googleApiClientID: "YOUR_GOOGLE_API_CLIENT_ID",
  scopes: [
    'https://www.googleapis.com/auth/drive.metadata.readonly', // For Google Drive access
    'https://www.googleapis.com/auth/userinfo.profile'         // For user profile info
  ],
  storageKeys: {
    isSignIn: 'myApp_isSignIn',
    user: 'myApp_user',
    accessToken: 'myApp_googleAccessToken'
  },
  storageType: 'local' // 'local' or 'session'
};

// Create a new GoogleAuth instance
const googleAuth = new GoogleAuth(firebaseConfig, options);

// Example: Sign-in button click handler
function handleSignInButtonClick() {
  if (!googleAuth.isSignedIn()) {
    googleAuth.signIn()
      .then((user) => {
        console.log('Signed in user:', user);
        
        // Update UI
        const signInBtn = document.getElementById('google-sign-in-button');
        signInBtn.innerText = user.displayName;
        
        // Do something with the user data
        handleUserSignedIn(user);
      })
      .catch((error) => {
        console.error('Error signing in:', error);
      });
  } else {
    // User is already signed in, show sign out option
    showSignOutDialog();
  }
}

// Example: Sign-out button click handler
function handleSignOutButtonClick() {
  googleAuth.signOut()
    .then(() => {
      console.log('User signed out');
      
      // Update UI
      const signInBtn = document.getElementById('google-sign-in-button');
      signInBtn.innerText = 'Sign in with Google';
      
      // Do something after sign out
      handleUserSignedOut();
    })
    .catch((error) => {
      console.error('Error signing out:', error);
    });
}

// Example: Handle auth state changes
function setupAuthStateListener() {
  googleAuth.onAuthStateChanged((user) => {
    if (user) {
      console.log('User is signed in:', user);
      
      // Update UI for signed-in state
      const signInBtn = document.getElementById('google-sign-in-button');
      signInBtn.innerText = user.displayName;
      
      // Do something with the user data
      handleUserSignedIn(user);
    } else {
      console.log('User is signed out');
      
      // Update UI for signed-out state
      const signInBtn = document.getElementById('google-sign-in-button');
      signInBtn.innerText = 'Sign in with Google';
    }
  });
}

// Example: Handle user signed in
function handleUserSignedIn(user) {
  // You can access user properties like:
  console.log('User display name:', user.displayName);
  console.log('User email:', user.email);
  console.log('User photo URL:', user.photoURL);
  console.log('User ID:', user.uid);
  
  // You can also get the access token if needed
  const accessToken = googleAuth.getAccessToken();
  console.log('Access token:', accessToken);
  
  // Make API calls or update your UI
}

// Example: Handle user signed out
function handleUserSignedOut() {
  // Clear user-specific data or update UI
}

// Example: Check if user is signed in on page load
function checkAuthOnPageLoad() {
  if (googleAuth.isSignedIn()) {
    const user = googleAuth.getUser();
    console.log('User is already signed in:', user);
    
    // Update UI for signed-in state
    const signInBtn = document.getElementById('google-sign-in-button');
    if (signInBtn && user) {
      signInBtn.innerText = user.displayName;
    }
    
    // Do something with the user data
    handleUserSignedIn(user);
  }
}

// Example: Initialize the app
function initApp() {
  // Set up event listeners
  const signInButton = document.getElementById('google-sign-in-button');
  if (signInButton) {
    signInButton.addEventListener('click', handleSignInButtonClick);
  }
  
  const signOutButton = document.getElementById('google-sign-out-button');
  if (signOutButton) {
    signOutButton.addEventListener('click', handleSignOutButtonClick);
  }
  
  // Check auth state on page load
  checkAuthOnPageLoad();
  
  // Set up auth state listener
  setupAuthStateListener();
}

// Initialize the app when the DOM is loaded
document.addEventListener('DOMContentLoaded', initApp);

// Export for use in other files if needed
export { googleAuth };
