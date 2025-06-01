package com.satinavrobotics.satibot.googleServices;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.widget.ImageView;

import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.google.gson.reflect.TypeToken;

import com.satinavrobotics.satibot.env.SharedPreferencesManager;
import com.satinavrobotics.satibot.modelManagement.ModelAdapter;
import com.satinavrobotics.satibot.tflite.Model;
import com.satinavrobotics.satibot.utils.FileUtils;

import timber.log.Timber;

/**
 * google signIn and google drive files management service class
 */
public class GoogleServices extends Fragment {

    // Set up logging tag for debugging purposes
    private ModelAdapter adapter;
    private static final String TAG = "GoogleServices";
    private final Activity mActivity;
    private final Context mContext;
    private final GoogleSignInCallback mCallback;
    public final GoogleSignInClient mGoogleSignInClient;
    private final FirebaseAuth firebaseAuth;
    private final SharedPreferencesManager sharedPreferencesManager;

    public static GoogleServices getInstance() {
        return null;
    }
    /**
     * Constructor for the GoogleServices class
     *
     * @param activity
     * @param context
     * @param callback
     */
    public  GoogleServices(Activity activity, Context context, GoogleSignInCallback callback) {
        // Set instance variables
        mActivity = activity;
        mContext = context;
        mCallback = callback;
        firebaseAuth = FirebaseAuth.getInstance();
        // Set up Google Sign-In options
        GoogleSignInOptions gso =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestIdToken(
                                "154487542187-eca7ghfm2vepoaglvjurdalu3c6tntjr.apps.googleusercontent.com")
                        .requestProfile()
                        .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                        .build();
        // Set up Shared Preferences
        // Set up Google Sign-In client
        mGoogleSignInClient = GoogleSignIn.getClient(mActivity, gso);
        // Check if there is a signed-in account already and notify the callback accordingly
        FirebaseUser user = firebaseAuth.getCurrentUser();
        if (user != null) {
            mCallback.onSignInSuccess(user);
        } else {
            mCallback.onSignInFailed(null);
        }
        sharedPreferencesManager = new SharedPreferencesManager(mContext);
    }


    /**
     * login with firebase using google signIn credential.
     *
     * @param credential use for firebase login.
     */
    private void firebaseAuthWithGoogle(AuthCredential credential) {
        firebaseAuth
                .signInWithCredential(credential)
                .addOnCompleteListener(
                        mActivity,
                        task -> {
                            Timber.tag(TAG).d("signInWithCredential:onComplete:%s", task.isSuccessful());
                            if (task.isSuccessful()) {
                                Timber.tag(TAG).d("onComplete: successful");
                                // Get the signed-in account and Notify callback of successful sign-in.
                                mCallback.onSignInSuccess(task.getResult().getUser());
                            } else {
                                Timber.tag(TAG).w("signInWithCredential%s", task.getException().getMessage());
                                task.getException().printStackTrace();
                                // The ApiException status code indicates the detailed failure reason and Notify
                                // callback of
                                // sign-in failure.
                                Timber.tag(TAG).e(task.getException(), "handleSignInResult:failed");
                                mCallback.onSignInFailed(task.getException());
                            }
                        });
    }

    /**
     * Method to sign out of the current Google account.
     */
    public void signOut() {
        mGoogleSignInClient
                .signOut()
                .addOnCompleteListener(
                        mActivity,
                        task -> {
                            // Handle sign-out result.
                            if (task.isSuccessful()) {
                                // firebase user sign-out.
                                firebaseAuth.signOut();
                                // Notify callback of successful sign-out.
                                mCallback.onSignOutSuccess();
                                Timber.tag(TAG).d("signOut:success");
                            } else {
                                // Notify callback of sign-out failure
                                mCallback.onSignOutFailed(task.getException());
                                Timber.tag(TAG).e(task.getException(), "signOut:failed");
                            }
                        });
    }

    /**
     * Method to handle the result of a Google Sign-In attempt
     *
     * @param completedTask
     */
    public void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            // Get the signed-in account and Notify callback of successful sign-in.
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            AuthCredential credential = GoogleAuthProvider.getCredential(account.getIdToken(), null);
            // Set credential for firebase authentication.
            firebaseAuthWithGoogle(credential);
            Timber.tag(TAG).d("handleSignInResult:success - %s", account.getEmail());
        } catch (ApiException e) {
            // The ApiException status code indicates the detailed failure reason and Notify callback of
            // sign-in failure.
            Timber.tag(TAG).e(e, "handleSignInResult:failed");
            mCallback.onSignInFailed(e);
        }
    }

    /**
     * Helper method to get the Drive API service
     *
     * @return
     */
    private Drive getDriveService() {
        if (!isNetworkAvailable()) {
            mCallback.onSignInFailed(new Exception("No network connection available"));
            return null;
        }

        GoogleSignInAccount googleAccount = GoogleSignIn.getLastSignedInAccount(mContext);
        if (googleAccount != null) {
            // Set up Google Account Credential.
            GoogleAccountCredential credential =
                    GoogleAccountCredential.usingOAuth2(
                            mContext, Collections.singletonList(DriveScopes.DRIVE_FILE));
            credential.setSelectedAccount(googleAccount.getAccount());
            // Build and return the Drive API service.
            return new Drive.Builder(
                    new NetHttpTransport(), JacksonFactory.getDefaultInstance(), credential)
                    .setApplicationName("OpenBot")
                    .build();
        }
        return null;
    }



    /**
     * Downloads a file from Google Drive with the given file ID.
     *
     * @param fileId the ID of the file to download
     */
    private void downloadFileFromGDrive(String fileId) {
        Drive googleDriveService = getDriveService();
        if (googleDriveService != null) {
            new Thread(
                    () -> {
                        try {
                            // Get the Google Drive file with the given ID
                            File gDriveFile = googleDriveService.files().get(fileId).execute();

                            Timber.tag("Google Drive").i(String.valueOf(gDriveFile));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    })
                    .start();
        } else {
            Timber.tag("Google Drive").e("SignIn error - not logged in");
        }
    }

    /**
     * Renames a file with the given ID to the given new title.
     *
     * @param fileId   the ID of the file to rename
     * @param newTitle the new title to give the file
     */
    public void renameFile(String fileId, String newTitle) {
        Drive googleDriveService = getDriveService();
        if (googleDriveService != null) {
            try {
                // Create a new File object with the given new title.
                File file = new File();
                file.setName(newTitle);
                // Update the file with the new title.
                googleDriveService.files().update(fileId, file).execute();
                Timber.tag("Google Drive File").d("File renamed successfully");
            } catch (IOException e) {
                // log any errors that occur when renaming the file.
                e.printStackTrace();
                Timber.tag("Google Drive File").e("Error renaming file");
            }
        }
    }





    private String checkPlaygroundFolder() {
        Drive getDriveService = getDriveService();
        String pageToken = null;
        do {
            try {
                if (getDriveService != null) {
                    FileList result =
                            getDriveService
                                    .files()
                                    .list()
                                    .setSpaces("drive")
                                    .setPageToken(pageToken)
                                    .setQ("trashed = false")
                                    .execute();
                    List<File> driveProjectFiles = result.getFiles();
                    for (File driveProjectFile : driveProjectFiles) {
                        if (driveProjectFile.getName().equals("openBot-Playground")) {
                            return driveProjectFile.getId();
                        }
                    }
                    pageToken = result.getNextPageToken();
                }
            } catch (IOException e) {
                e.printStackTrace();
                pageToken = null;
            }
        } while (pageToken != null);
        return null;
    }

    /**
     * Creates and uploads a JSON file containing a list of models to Google Drive.
     *
     * @param modelList List of Model objects to be included in the JSON file.
     */
    public void createAndUploadJsonFile(List<Model> modelList) {
        // Get the Drive service instance and convert the modelList to JSON string using Gson.
        Drive getDriveService = getDriveService();
        Gson gson = new GsonBuilder().create();
        String modelListContent = gson.toJson(modelList);

        // Start a new thread to perform the Drive API operations.
        new Thread(
                () -> {
                    String pageToken = null;
                    do {
                        try {
                            if (getDriveService != null) {
                                FileList result =
                                        getDriveService
                                                .files()
                                                .list()
                                                .setSpaces("drive")
                                                .setPageToken(pageToken)
                                                .setQ("trashed = false")
                                                .execute();
                                List<File> driveProjectFiles = result.getFiles();
                                boolean getOpenBotPlayGroundFile = false;
                                boolean getConfigFIle = false;
                                String configFileId = null;
                                String openBotPlayGroundFileId = null;
                                for (File driveProjectFile : driveProjectFiles) {
                                    if (driveProjectFile.getName().equals("openBot-Playground")) {
                                        getOpenBotPlayGroundFile = true;
                                        openBotPlayGroundFileId = driveProjectFile.getId();
                                    }
                                    if (driveProjectFile.getName().equals("config.json")) {
                                        getConfigFIle = true;
                                        configFileId = driveProjectFile.getId();
                                    }
                                }

                                // Perform necessary actions based on file existence.
                                if (getOpenBotPlayGroundFile && getConfigFIle)
                                    // Update existing config.json with model list.
                                    updateModelListFile(modelListContent, configFileId);
                                if (getOpenBotPlayGroundFile && !getConfigFIle)
                                    // Create config.json and add model list.
                                    createModelListFile(modelListContent, openBotPlayGroundFileId);
                                if (!getOpenBotPlayGroundFile && !getConfigFIle)
                                    // Create 'openBot' folder and add config.json with model list.
                                    createOpenBotFolder(modelListContent, null);
                                pageToken = result.getNextPageToken();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            pageToken = null;
                        }
                    } while (pageToken != null);
                }).start();
    }

    /**
     * Creates an 'openBot' folder on Google Drive and adds a model list JSON file or log data.
     *
     * @param modelListContent JSON content of the model list (null if not applicable).
     * @param zipFile          Zip file containing log data (null if not applicable).
     */
    public void createOpenBotFolder(String modelListContent, java.io.File zipFile) {
        Drive driveService = getDriveService();
        File fileMetadata = new File();
        fileMetadata.setName("openBot-Playground");
        fileMetadata.setMimeType("application/vnd.google-apps.folder");

        if (driveService != null) {
            try {
                File file = driveService.files().create(fileMetadata)
                        .setFields("id")
                        .execute();
                if (modelListContent != null) createModelListFile(modelListContent, file.getId());
                else uploadLogData(zipFile);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Creates a 'config.json' file containing the model list content within the specified folder on Google Drive.
     *
     * @param modelListContent   JSON content of the model list.
     * @param playGroundFolderId ID of the parent folder where the file should be created.
     */
    private void createModelListFile(String modelListContent, String playGroundFolderId) {
        Drive getDriveService = getDriveService();
        // Create metadata for the 'config.json' file within the specified folder and
        // convert the JSON content to a byte array content.
        File fileMetadata = new File();
        fileMetadata.setName("config.json").setParents(Collections.singletonList(playGroundFolderId));
        ByteArrayContent content = ByteArrayContent.fromString("application/json", modelListContent);

        new Thread(() -> {
            try {
                if (getDriveService != null) {
                    File file = getDriveService.files().create(fileMetadata, content).setFields("id").execute();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    /**
     * Updates the content of an existing file with the new model list content on Google Drive.
     *
     * @param modelListContent New JSON content of the model list.
     * @param fileId           ID of the file to be updated.
     */
    public void updateModelListFile(String modelListContent, String fileId) {
        Drive driveService = getDriveService();
        new Thread(() -> {
            if (driveService != null) {
                try {
                    // Convert the JSON content to a byte array content and update the file's content.
                    ByteArrayContent content = ByteArrayContent.fromString("application/json", modelListContent);
                    File updatedFile = driveService.files().update(fileId, null, content).execute();
                } catch (IOException e) {
                    // Handle error: Update operation failed.
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * Uploads a log data zip file to Google Drive, creating a new file or using an existing folder.
     *
     * @param zipFile The log data zip file to be uploaded.
     */
    public void uploadLogData(java.io.File zipFile) {
        Timber.d("Uploading to GOOGLE DRIVE");
        Drive getDriveService = getDriveService();
        String playGroundFolderId = checkPlaygroundFolder();

        Timber.d("Drive service found with playgroundfolder");
        Timber.d(getDriveService.toString(), playGroundFolderId);

        // Check if the 'openBot' folder exists, upload the zip file to it.
        if (playGroundFolderId != null) {
            File fileMetadata = new File();
            String fileName = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            fileMetadata.setName(fileName).setParents(Collections.singletonList(playGroundFolderId));
            FileContent fileContent = new FileContent("application/zip", zipFile);
            new Thread(() -> {
                try {
                    if (getDriveService != null) {
                        File file = getDriveService.files().create(fileMetadata, fileContent).setFields("id").execute();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        } else {
            // If the folder doesn't exist, create it and upload the zip file
            createOpenBotFolder(null, zipFile);
        }
    }

    /**
     * Gets the content of the 'config.json' file from Google Drive and update model list.
     *
     * @param rotation The ObjectAnimator used for rotation animation (to be paused upon completion).
     * @param icon     The ImageView icon (to be reset upon completion).
     */
    public void getConfigFileContent(ObjectAnimator rotation, ImageView icon, ModelAdapter updateModelListAdapter) {
        Drive driveService = getDriveService();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        new Thread(() -> {
            String pageToken = null;
            do {
                try {
                    if (driveService != null) {
                        FileList result =
                                driveService
                                        .files()
                                        .list()
                                        .setSpaces("drive")
                                        .setPageToken(pageToken)
                                        .setQ("trashed = false")
                                        .execute();
                        List<File> driveProjectFiles = result.getFiles();
                        String updatedModelList;
                        for (File driveProjectFile : driveProjectFiles) {
                            if (driveProjectFile.getName().equals("config.json")) {
                                // Read the content of the file
                                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                                driveService
                                        .files()
                                        .get(driveProjectFile.getId())
                                        .executeMediaAndDownloadTo(outputStream);
                                updatedModelList = outputStream.toString();

                                List<Model> modelList = gson.fromJson(updatedModelList, new TypeToken<List<Model>>() {
                                }.getType());

                                for (int i = 0; i < modelList.size(); i++) {
                                    if (modelList.get(i).pathType == Model.PATH_TYPE.FILE && !FileUtils.checkFileExistence(mActivity, modelList.get(i).name)) {
                                        modelList.get(i).setPathType(Model.PATH_TYPE.URL);
                                    }
                                }
                                FileUtils.updateModelConfig(mActivity, mContext, modelList, false);
                                 mActivity.runOnUiThread(() -> {
                                    updateModelListAdapter.setItems(modelList);
                                    updateModelListAdapter.notifyDataSetChanged();
                                        });
                                break;
                            }
                        }
                        // Pause rotation animation and reset icon.
                        rotation.pause();
                        icon.setRotation(0f);

                    } else {
                        rotation.pause();
                        icon.setRotation(0f);

                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    pageToken = null;
                }
            } while (pageToken != null);
        }).start();
    }

    // Add a network connectivity check before making API calls
    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }
}
