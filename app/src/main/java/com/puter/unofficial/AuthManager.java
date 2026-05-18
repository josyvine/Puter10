package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Manages the persistent authentication state for Puter Unofficial.
 * This class ensures that once a user signs in via the browser, the app 
 * remembers that state across restarts using SharedPreferences.
 * UPDATED: Added session token persistence to bridge isolation between WebViews.
 */
public class AuthManager {

    private static final String PREF_NAME = "PuterPrefs";
    private static final String KEY_IS_LOGGED_IN = "is_logged_in";
    private static final String KEY_AUTH_TOKEN = "puter_auth_token"; // Key for the extracted SDK token
    private static AuthManager instance;
    private final SharedPreferences prefs;

    /**
     * Private constructor for Singleton pattern.
     * Accesses the shared preference file dedicated to Puter settings.
     */
    private AuthManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Gets the global instance of the AuthManager.
     * Uses synchronized block to ensure thread safety.
     */
    public static synchronized AuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new AuthManager(context);
        }
        return instance;
    }

    /**
     * Saves the current authentication status.
     * This is called by the MainActivity or the Bridge when a successful 
     * login is detected or intercepted from the browser redirect.
     * 
     * @param status true if the user is authenticated, false otherwise.
     */
    public void setLoggedIn(boolean status) {
        prefs.edit().putBoolean(KEY_IS_LOGGED_IN, status).apply();
    }

    /**
     * Checks if the user is currently considered logged in.
     * The SplashActivity uses this to skip the sign-in flow, 
     * and the HTML frontend uses this to show "Sign Out".
     * 
     * @return true if status is saved as logged in.
     */
    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false);
    }

    /**
     * NEW: Persists the authentication token string extracted from the login popup.
     * This allows the main WebView to inject the session into its own localStorage.
     */
    public void setAuthToken(String token) {
        prefs.edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    /**
     * NEW: Retrieves the saved authentication token.
     */
    public String getAuthToken() {
        return prefs.getString(KEY_AUTH_TOKEN, null);
    }

    /**
     * Clears the authentication state.
     * Triggered when the user selects "Sign Out" from the HTML dropdown menu.
     * UPDATED: Now also clears the session token.
     */
    public void logout() {
        prefs.edit()
             .putBoolean(KEY_IS_LOGGED_IN, false)
             .remove(KEY_AUTH_TOKEN) // Ensure token is cleared on logout
             .apply();
    }

    /**
     * Helper to verify if a specific URL is a Puter authentication success callback.
     * This logic is used by the WebViewClient to detect when the user has 
     * finished signing in on the browser and has been redirected back.
     * 
     * @param url The URL being intercepted in the WebView.
     * @return true if the URL indicates a successful authentication.
     */
    public boolean isAuthCallback(String url) {
        if (url == null) return false;

        /* 
         * Puter typically redirects back to the main domain or a custom 
         * callback URL after login. We check for success markers.
         */
        // Use constants for better maintainability. This checks if the URL
        // indicates a successful login via the Puter.js SDK redirect.
        return (url.contains(AppConstants.AUTH_TOKEN_PARAM) || url.contains(AppConstants.AUTH_SUCCESS_MARKER));
    }
}