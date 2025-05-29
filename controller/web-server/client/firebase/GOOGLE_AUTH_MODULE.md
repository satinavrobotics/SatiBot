# Google Authentication Module

A reusable module for handling Google authentication in web applications. This module provides a simple API for Google sign-in, sign-out, and auth state management.

## Features

- Easy integration with Firebase Authentication
- Google Sign-In with popup
- Custom token authentication
- Auth state management
- Access token handling
- Configurable storage options
- Cookie utilities

## Installation

1. Copy the `googleAuth.js` file to your project
2. Install the required dependencies:

```bash
npm install firebase
```

## Basic Usage

```javascript
import { GoogleAuth } from './googleAuth.js';

// Firebase configuration
const firebaseConfig = {
  apiKey: "YOUR_API_KEY",
  authDomain: "YOUR_AUTH_DOMAIN",
  projectId: "YOUR_PROJECT_ID",
  storageBucket: "YOUR_STORAGE_BUCKET",
  messagingSenderId: "YOUR_MESSAGING_SENDER_ID",
  appId: "YOUR_APP_ID"
};

// Create a new GoogleAuth instance
const googleAuth = new GoogleAuth(firebaseConfig);

// Sign in
googleAuth.signIn()
  .then(user => {
    console.log('Signed in:', user);
  })
  .catch(error => {
    console.error('Error signing in:', error);
  });

// Sign out
googleAuth.signOut()
  .then(() => {
    console.log('Signed out');
  });

// Check if user is signed in
if (googleAuth.isSignedIn()) {
  const user = googleAuth.getUser();
  console.log('Current user:', user);
}
```

## Advanced Configuration

You can customize the behavior of the GoogleAuth module by passing options:

```javascript
const options = {
  // Google API Client ID (optional, for advanced Google API usage)
  googleApiClientID: "YOUR_GOOGLE_API_CLIENT_ID",
  
  // Additional scopes to request during sign-in
  scopes: [
    'https://www.googleapis.com/auth/drive.metadata.readonly',
    'https://www.googleapis.com/auth/userinfo.profile'
  ],
  
  // Custom storage keys
  storageKeys: {
    isSignIn: 'myApp_isSignIn',
    user: 'myApp_user',
    accessToken: 'myApp_googleAccessToken'
  },
  
  // Storage type: 'local' (localStorage) or 'session' (sessionStorage)
  storageType: 'local'
};

const googleAuth = new GoogleAuth(firebaseConfig, options);
```

## API Reference

### Constructor

```javascript
new GoogleAuth(config, options)
```

- `config`: Firebase configuration object
- `options`: (Optional) Additional configuration options

### Methods

#### `signIn()`

Sign in with Google using a popup.

```javascript
googleAuth.signIn()
  .then(user => {
    console.log('Signed in:', user);
  })
  .catch(error => {
    console.error('Error signing in:', error);
  });
```

#### `signOut()`

Sign out the current user.

```javascript
googleAuth.signOut()
  .then(() => {
    console.log('Signed out');
  });
```

#### `signInWithToken(token)`

Sign in with a custom token.

```javascript
googleAuth.signInWithToken(token)
  .then(user => {
    console.log('Signed in with token:', user);
  });
```

#### `getUser()`

Get the current user object.

```javascript
const user = googleAuth.getUser();
```

#### `isSignedIn()`

Check if a user is currently signed in.

```javascript
if (googleAuth.isSignedIn()) {
  // User is signed in
}
```

#### `getAccessToken()`

Get the Google access token (if available).

```javascript
const token = googleAuth.getAccessToken();
```

#### `onAuthStateChanged(callback)`

Listen for authentication state changes.

```javascript
const unsubscribe = googleAuth.onAuthStateChanged(user => {
  if (user) {
    console.log('User signed in:', user);
  } else {
    console.log('User signed out');
  }
});

// Later, to stop listening:
unsubscribe();
```

#### `initializeGoogleAPI()`

Initialize the Google API for advanced use cases.

```javascript
googleAuth.initializeGoogleAPI()
  .then(() => {
    console.log('Google API initialized');
  });
```

#### `getCookie(name)` and `deleteCookie(name)`

Utility methods for cookie management.

```javascript
const cookieValue = googleAuth.getCookie('cookieName');
googleAuth.deleteCookie('cookieName');
```

## Example

See `googleAuthExample.js` for a complete example of how to use this module in a web application.

## License

MIT
