// Import the initializeGoogleAPI function from authentication.js
import { initializeGoogleAPI } from '../firebase/authentication';

export function Recorder() {

    window.locationCallbacks.updateRecorder = updateRecorder

    this.init = (commandHandler, accessToken) => {
        this.commandHandler = commandHandler
        const startRecordingButton = document.getElementById('start-recording-btn')
        startRecordingButton.addEventListener('click', this.handleStartRecording)
    }

    /**
     * function to handle start recording button
     */
    this.handleStartRecording = () => {
        console.log("LOGS");
        console.log(this.commandHandler);
        if (this.commandHandler != null) {
            const logMessage = 'LOGS'
            this.commandHandler.sendCommand(logMessage) // Send the command message
        }
    }

    /**
     * function to update the recorder state
     * @param {boolean} isRecording - true if recording is on, false otherwise
     */
    function updateRecorder (status) {
        console.log("update recorder")
        const isRecording = status.recording
        const startRecordingButton = document.getElementById('start-recording-btn')
        if (isRecording) {
            startRecordingButton.textContent = "Stop Recording";
            // Create and display a red circle in the top right corner
        } else {
            startRecordingButton.textContent = "Start Recording";
        }
    }

    this.updateFolderList = (folders) => {
        const folderList = document.getElementById('drive-folder-list');
        folderList.innerHTML = ''; // Clear existing list
        folders.forEach(folder => {
            const listItem = document.createElement('li');
            listItem.textContent = folder.name;
            folderList.appendChild(listItem);
        });
    }

    this.fetchDriveFolders = async () => {
        const accessToken = localStorage.getItem('driveAccessToken');
        console.log(accessToken)
        if (!accessToken) {
            console.error('No access token available for Google Drive.');
            return;
        }
        try {
            // Call the new function to initialize Google API after sign-in
            await initializeGoogleAPI();

            const authInstance = gapi.auth2.getAuthInstance();
            authInstance.setAccessToken(accessToken);

            // Load the Google Drive API client
            await gapi.client.load('drive', 'v3');

            // Fetch the list of folders
            const response = await gapi.client.drive.files.list({
                q: "mimeType='application/vnd.google-apps.folder'", // Get only folders
                fields: 'files(id, name)',
            });

            console.log('User Drive Folders:', response.result.files);
            this.updateFolderList(response.result.files);
        } catch (error) {
            console.error('Error fetching Drive folders:', error);
        }
    }
}



