package com.puter.unofficial;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import android.util.Log;

import java.util.Locale;

/**
 * The core bridge class between the HTML JavaScript and Native Android code.
 * Fulfills all requirements for native TTS, barge-in, full-screen voice agent,
 * and authentication persistence.
 * UPDATED: Added Native Logging Bridge to support the Floating Debug Console.
 */
public class WebAppInterface {

    private final Context context;
    private final WebView webView;
    private TextToSpeech tts;
    private final SharedPreferences prefs;
    private VoiceManager voiceManager;
    private boolean isTtsInitialized = false;

    /**
     * Constructor for the interface.
     * @param context Activity context required for launching intents and UI updates.
     * @param webView Reference to the WebView for running JavaScript callbacks.
     */
    public WebAppInterface(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.prefs = context.getSharedPreferences("PuterPrefs", Context.MODE_PRIVATE);

        // Initialize Native Android Text-To-Speech Engine (Requirement #4)
        this.tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true;
                    nativeLog("TTS Engine Initialized Successfully", "native");
                } else {
                    nativeLog("TTS Language not supported", "error");
                }
            } else {
                nativeLog("TTS Initialization Failed", "error");
            }
        });
    }

    /**
     * NEW: Diagnostic method to pipe Java-side logs into the Floating Debug Console.
     * This helps identify why login fails by showing Java events in the JS timeline.
     */
    @JavascriptInterface
    public void nativeLog(String message, String type) {
        if (webView != null) {
            String safeMsg = message.replace("'", "\\'");
            webView.post(() -> {
                webView.evaluateJavascript("if(window.addPuterLog){ window.addPuterLog('[JAVA] " + safeMsg + "', '" + type + "'); }", null);
            });
        }
        // Also keep a record in Android Logcat
        Log.d("PuterNativeBridge", message);
    }

    /**
     * Links the VoiceManager for background STT operations.
     */
    public void setVoiceManager(VoiceManager voiceManager) {
        this.voiceManager = voiceManager;
    }

    // 1. NATIVE TEXT-TO-SPEECH (TTS)
    // Supports Barge-in: stops current speech and starts new text immediately.
    @JavascriptInterface
    public void speak(String text) {
        if (isTtsInitialized && tts != null) {
            nativeLog("AI speaking response...", "info");
            // Barge-in logic: QUEUE_FLUSH clears previous speech and interrupts immediately (Requirement #4)
            tts.stop();
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "PuterTTS_ID");
        }
    }

    // 2. STOP TTS
    // Interrupts the AI speaker immediately.
    @JavascriptInterface
    public void stopSpeaking() {
        if (tts != null) {
            nativeLog("Stopping AI speech (Barge-in triggered)", "native");
            tts.stop();
        }
    }

    // 3. NATIVE SPEECH RECOGNITION (Standard)
    // Triggers background microphone for the search input.
    @JavascriptInterface
    public void startListening() {
        if (voiceManager != null) {
            nativeLog("Opening Native Microphone...", "native");
            stopSpeaking();
            ((Activity) context).runOnUiThread(() -> voiceManager.startListening());
        }
    }

    // 4. FULL-SCREEN VOICE AGENT (Requirement #4)
    // Launches the native full-screen Activity for continuous conversation (Gemini Live style).
    @JavascriptInterface
    public void startVoiceAgent() {
        nativeLog("Launching Full-Screen Voice Agent", "native");
        stopSpeaking();
        Intent intent = new Intent(context, VoiceAgentActivity.class);
        context.startActivity(intent);
    }

    // 5. NATIVE FILE / CAMERA PICKER (Requirement #3)
    // Triggers the system chooser for Gallery, Camera, and Files.
    @JavascriptInterface
    public void openFilePicker() {
        nativeLog("Opening System File Picker", "native");
        ((Activity) context).runOnUiThread(() -> {
            // This is handled via onShowFileChooser in MainActivity's WebChromeClient.
            // We invoke the picker via Intent to ensure compatibility with Puter.js Base64 needs.
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Upload to Puter"), 1);
        });
    }

    // --- NEW BRIDGE METHODS ---

    /**
     * Reads a local asset file and returns its content as a string.
     * This is the FIX for "Error loading models" by bypassing WebView fetch restrictions.
     * @param fileName The name of the file in the assets folder.
     * @return The content of the file as a string.
     */
    @JavascriptInterface
    public String getLocalJson(String fileName) {
        nativeLog("Loading asset: " + fileName, "info");
        return AssetUtils.readFile(context, fileName);
    }

    /**
     * Called from JavaScript to update the native SharedPreferences with the real auth state
     * from the Puter SDK. This keeps the native and web layers in sync.
     * @param isSignedIn The status reported by puter.auth.isSignedIn().
     */
    @JavascriptInterface
    public void onAuthStatusChanged(boolean isSignedIn) {
        nativeLog("Auth Status Changed: " + (isSignedIn ? "Signed In" : "Signed Out"), "native");
        AuthManager.getInstance(context).setLoggedIn(isSignedIn);
    }
    
    /**
     * Returns the auth token saved in native storage.
     * Used by index.html to inject credentials into the main WebView's localStorage.
     */
    @JavascriptInterface
    public String getSavedAuthToken() {
        String token = prefs.getString("puter_auth_token", null);
        if(token != null) nativeLog("Native Token requested by Web View", "native");
        return token;
    }

    /**
     * Saves the auth token string to native storage.
     * This is called by the popup window logic to bridge the token to the main activity.
     */
    @JavascriptInterface
    public void saveAuthToken(String token) {
        if (token != null) {
            nativeLog("Extraction Complete: Token saved to SharedPreferences", "native");
            prefs.edit().putString("puter_auth_token", token).apply();
        }
    }

    // --- LEGACY AUTH METHODS ---

    // 6. SIGN IN
    @JavascriptInterface
    public void signIn() {
        nativeLog("Puter SDK Triggered SignIn Process", "info");
    }

    // 7. SIGN OUT
    @JavascriptInterface
    public void signOut() {
        nativeLog("User requested Sign Out", "native");
        // Clear native token storage on sign out
        prefs.edit().remove("puter_auth_token").apply();
        AuthManager.getInstance(context).logout();
        ((Activity) context).runOnUiThread(() -> {
            Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show();
            webView.reload();
        });
    }

    // 8. LOGIN PERSISTENCE CHECK
    @JavascriptInterface
    public boolean isLoggedIn() {
        return prefs.getBoolean("is_logged_in", false);
    }

    /**
     * Java-side method to update login status after browser redirect.
     */
    public void setLoggedIn(boolean status) {
        prefs.edit().putBoolean("is_logged_in", status).apply();
    }

    /**
     * Cleanup resources when the Activity is destroyed.
     */
    public void destroy() {
        nativeLog("Bridge Destroyed - Cleaning Resources", "native");
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}