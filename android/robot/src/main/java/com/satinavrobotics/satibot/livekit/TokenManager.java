package com.satinavrobotics.satibot.livekit;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

public class TokenManager {
    private static final String PREF_NAME = "secure_prefs";
    private static final String TOKEN_KEY = "jwt_token";
    private static final String EXPIRY_KEY = "jwt_expiry";
    private static final String SERVER_ADDRESS_KEY = "server_address";

    private static TokenManager instance;
    private final SharedPreferences sharedPreferences;

    private TokenManager(Context context) throws Exception {
        String masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        sharedPreferences = EncryptedSharedPreferences.create(
                PREF_NAME, masterKey, context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    public static synchronized TokenManager getInstance(Context context) throws Exception {
        if (instance == null) {
            instance = new TokenManager(context);
        }
        return instance;
    }

    /** Save JWT token with expiration time **/
    public void saveToken(String token, long expiryTimeMillis) {
        sharedPreferences.edit()
                .putString(TOKEN_KEY, token)
                .putLong(EXPIRY_KEY, System.currentTimeMillis() + expiryTimeMillis)
                .apply();
    }

    /** Retrieve the stored JWT token **/
    public String getToken() {
        return isTokenExpired() ? null : sharedPreferences.getString(TOKEN_KEY, null);
    }

    /** Check if the token is expired **/
    public boolean isTokenExpired() {
        long expiryTime = sharedPreferences.getLong(EXPIRY_KEY, 0);
        return System.currentTimeMillis() > expiryTime;
    }

    /** Clear token from storage **/
    public void clearToken() {
        sharedPreferences.edit().remove(TOKEN_KEY).remove(EXPIRY_KEY).apply();
    }

    /** Save the server address **/
    public void saveServerAddress(String address) {
        sharedPreferences.edit().putString(SERVER_ADDRESS_KEY, address).apply();
    }

    /** Retrieve the stored server address **/
    public String getServerAddress() {
        return sharedPreferences.getString(SERVER_ADDRESS_KEY, null);
    }

    /** Clear the server address **/
    public void clearServerAddress() {
        sharedPreferences.edit().remove(SERVER_ADDRESS_KEY).apply();
    }

    /** Get the remaining TTL in seconds **/
    public long getRemainingTTLSeconds() {
        if (isTokenExpired()) {
            return 0;
        }
        long expiryTime = sharedPreferences.getLong(EXPIRY_KEY, 0);
        long remainingMillis = expiryTime - System.currentTimeMillis();
        return Math.max(0, remainingMillis / 1000);
    }

    /** Get the expiration time in milliseconds **/
    public long getExpirationTime() {
        return sharedPreferences.getLong(EXPIRY_KEY, 0);
    }
}
