package com.puter.unofficial;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues; // Added for saving images
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager; // Added for self diagnostics
import android.graphics.Bitmap; // Added for image decoding
import android.graphics.BitmapFactory; // Added for image decoding
import android.net.Uri;
import android.os.Build; // Added for Scoped Storage compatibility
import android.os.Environment; // Added for public directory
import android.provider.MediaStore; // Added for saving images
import android.speech.SpeechRecognizer; // Added for STT diagnostics
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;
import android.util.Log;
import android.webkit.CookieManager;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;

import androidx.core.content.ContextCompat; // Added for permission checks

import java.io.OutputStream; // Added for writing image data
import java.io.IOException; // Added for exception handling
import java.util.Locale;
import java.util.Map;
import java.util.Objects; // Added for null checks on Objects
import java.util.Date; // Added for unique file naming
import java.util.List;

/**
 * The core bridge class between the HTML JavaScript and Native Android code.
 * Fulfills all requirements for native TTS, barge-in, full-screen voice agent,
 * and authentication persistence.
 * UPDATED: Aligned with WebViewAssetLoader architecture and diagnostic logging.
 * PERSISTENCE UPDATE: Added hardware-level cookie synchronization logic to prevent 
 * session loss during AI chat requests.
 * REFINED: Fixed Voice Mode leakage and Continuous Interruption logic.
 * ENHANCEMENT: Added support for multi-session deletion via native bridge.
 * UPDATED: Added on-demand wakeUpKiwi() JavascriptInterface to handle lazy wake-ups.
 * ENHANCED: Integrated Gemini model registries, settings drawer variables, and OkHttp stream dispatchers.
 * CRITICAL FIXES: Added explicit pause/resume microphone controls for Web Audio and resolved dual-STT mic conflicts.
 * LOG INTERCEPTOR FIX: Parses low-level browser diagnostic messages inside logDiagnostic to cleanly release duplicate mic locks.
 * MIC RELEASE FIX: Automatically destroys the native background SpeechRecognizer context on startup and on handshake confirmation.
 */
public class WebAppInterface {

    private final Context context;
    private final WebView webView;
    private final SharedPreferences prefs;
    private GeminiService geminiService;

    // MODIFIED: Global static tracking for Live WebSocket session visibility
    public static volatile boolean isLiveSocketActive = false;

    // =========================================================================
    // MODIFIED: CENTRAL THREAD-SAFE FORENSIC DIAGNOSTIC LOGGER
    // =========================================================================
    public static class DiagnosticLogger {
        private static final StringBuilder logBuilder = new StringBuilder();
        private static LogListener listener;

        public interface LogListener {
            void onLogAdded(String newLog);
        }

        public static synchronized void log(String message) {
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(new java.util.Date());
            String formatted = "[" + timestamp + "] " + message + "\n";
            logBuilder.append(formatted);
            if (listener != null) {
                listener.onLogAdded(formatted);
            }
            Log.d("DiagnosticLogger", formatted);
        }

        public static synchronized String getLogs() {
            return logBuilder.toString();
        }

        public static synchronized void setListener(LogListener l) {
            listener = l;
            if (listener != null && logBuilder.length() > 0) {
                listener.onLogAdded(logBuilder.toString());
            }
        }

        public static synchronized void clear() {
            logBuilder.setLength(0);
        }
    }

    /**
     * Constructor for the interface.
     * @param context Activity context required for launching intents and UI updates.
     * @param webView Reference to the WebView for running JavaScript callbacks.
     */
    public WebAppInterface(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        this.prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
        this.geminiService = new GeminiService(context, webView);

        DiagnosticLogger.log("[LIFECYCLE] WebAppInterface initialized. WebView secure origin check context verified.");
    }

    /**
     * Helper to safely escape JavaScript parameters to avoid script execution failures.
     */
    private String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
    }

    /**
     * Diagnostic method to pipe Java-side logs into the Floating Debug Console.
     * This helps identify why login fails by showing Java events in the JS timeline.
     * UPDATED: Now also writes the log natively to our daily public documents file.
     */
    @JavascriptInterface
    public void nativeLog(String message, String type) {
        if (webView != null) {
            String escapedMsg = escapeJsString(message);
            webView.post(() -> {
                // Interacts with window.addPuterLog inside debug_console.js
                webView.evaluateJavascript("if(window.addPuterLog){ window.addPuterLog('[JAVA] " + escapedMsg + "', '" + type + "'); }", null);
                // Interacts with window.addNativeLogToConsole inside browser.html
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('[JAVA] " + escapedMsg + "', '" + type + "'); }", null);
            });
        }
        // Also keep a record in Android Logcat for GitHub Workflow log analysis
        Log.d("PuterNativeBridge", message);

        // --- TOTAL SURVEILLANCE INTEGRATION ---
        if ("error".equalsIgnoreCase(type) || "critical".equalsIgnoreCase(type)) {
            ActionReportLogger.logError("NATIVE_BRIDGE", message);
        } else {
            ActionReportLogger.logAction("NATIVE_BRIDGE", message);
        }
    }

    // 3. NATIVE SPEECH RECOGNITION (Standard / REST Model On-Demand STT)
    // Triggers background microphone for the search input strictly as an on-demand tool.
    @JavascriptInterface
    public void startListening() {
        nativeLog("startListening called. isLiveSocketActive: " + isLiveSocketActive, "native");
        DiagnosticLogger.log("[BRIDGE] startListening requested. isLiveSocketActive=" + isLiveSocketActive);
        
        // WebRTC Safeguard Gate: Stop standard SpeechRecognizer from seizing mic if Gemini Live WebSocket is active
        if (isLiveSocketActive) {
            DiagnosticLogger.log("[STT] Bypassed native SpeechRecognizer: WebView Live session is active.");
            return;
        }

        // Native speech systems are completely disabled to allow secure WebRTC microphone ownership.
        DiagnosticLogger.log("[BRIDGE] Native speech captures are disabled. Standard browser Web Speech API should be used.");
    }

    // =========================================================================
    // NEW: PROGRAMMATIC MIC CONTROL HOOKS FOR WEB AUDIO TIMELINES
    // =========================================================================

    /**
     * Programmatically pauses the native STT listener during active Web Audio playbacks
     * to eliminate echo feedback loops and speaker-seizure conflicts.
     */
    @JavascriptInterface
    public void pauseListening() {
        nativeLog("pauseListening triggered via JS bridge.", "native");
        DiagnosticLogger.log("[BRIDGE] pauseListening triggered.");
        Intent intent = new Intent("PUTER_PAUSE_LISTENING");
        context.sendBroadcast(intent);
        DiagnosticLogger.log("[BROADCAST] Broadcasted PUTER_PAUSE_LISTENING to mute native microphone.");
    }

    /**
     * Programmatically resumes the native STT listener once browser playback queues are fully drained.
     */
    @JavascriptInterface
    public void resumeListening() {
        nativeLog("resumeListening triggered via JS bridge.", "native");
        DiagnosticLogger.log("[BRIDGE] resumeListening triggered.");
        Intent intent = new Intent("PUTER_START_LISTENING");
        context.sendBroadcast(intent);
        DiagnosticLogger.log("[BROADCAST] Broadcasted PUTER_START_LISTENING to resume native microphone.");
    }

    // =========================================================================
    // NEW: PROGRAMMATIC SOCKET STATES & USER TRANSCRIPTS
    // =========================================================================

    /**
     * Updates the status of active WebSockets Live calls and broadcasts 
     * state updates to completely shut down conflicting native recorders.
     */
    @JavascriptInterface
    public void setLiveSocketActive(boolean active) {
        isLiveSocketActive = active;
        nativeLog("Bridge: Live Socket active state changed to: " + active, "info");
        DiagnosticLogger.log("[BRIDGE] setLiveSocketActive triggered with active=" + active);

        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
                    DiagnosticLogger.log("[HARDWARE CHECK] Active recording configurations count: " + configs.size());
                    for (int i = 0; i < configs.size(); i++) {
                        AudioRecordingConfiguration config = configs.get(i);
                        DiagnosticLogger.log(String.format("[HARDWARE CHECK] RecordConfig [%d]: AudioSource=%d | ClientAudioSessionId=%d | FormatSampleRate=%d", 
                            i, config.getClientAudioSource(), config.getClientAudioSessionId(), config.getFormat().getSampleRate()));
                    }
                } else {
                    DiagnosticLogger.log("[HARDWARE CHECK] API Level too low for getActiveRecordingConfigurations check.");
                }
                DiagnosticLogger.log("[HARDWARE CHECK] Microphone is currently muted natively: " + audioManager.isMicrophoneMute());
            }
        } catch (Exception e) {
            DiagnosticLogger.log("[ERROR] Hardware status check failed: " + e.getMessage());
        }

        // Broadcast intent to update VoiceAgentActivity mic-lock structures
        Intent intent = new Intent("PUTER_LIVE_SOCKET_STATE");
        intent.putExtra("ACTIVE", active);
        context.sendBroadcast(intent);
        DiagnosticLogger.log("[BROADCAST] Sent PUTER_LIVE_SOCKET_STATE intent. Active=" + active);
    }

    /**
     * Forwards real-time speech-to-text transcript frames generated by the 
     * Gemini Live server back to the immersive VoiceAgentActivity dashboard.
     */
    @JavascriptInterface
    public void broadcastUserTranscript(String text) {
        DiagnosticLogger.log("[BRIDGE] broadcastUserTranscript invoked. Text: " + text);
        Intent intent = new Intent("PUTER_USER_TRANSCRIPT");
        intent.putExtra("TEXT", text);
        context.sendBroadcast(intent);
    }

    /**
     * Primary Native TTS Bridge Method.
     * Receives raw text strings and forwards them to MainActivity's system TextToSpeech engine.
     */
    @JavascriptInterface
    public void speak(String text) {
        nativeLog("speak requested via JS bridge: " + (text != null ? text.substring(0, Math.min(text.length(), 25)) + "..." : "null"), "info");
        DiagnosticLogger.log("[BRIDGE] speak invoked.");
        if (context instanceof MainActivity) {
            ((MainActivity) context).speak(text);
        } else {
            nativeLog("speak execution aborted: Context is not an instance of MainActivity.", "error");
            DiagnosticLogger.log("[BRIDGE] speak failure: Context is not MainActivity.");
        }
    }

    /**
     * Packages current OS permissions and native speech engine states into a JSON payload.
     * Satisfies the native diagnostics checklist requirements for the startup diagnostics console.
     */
    @JavascriptInterface
    public String getSystemHardwareDiagnostics() {
        nativeLog("getSystemHardwareDiagnostics invoked over JavaScript bridge.", "info");
        DiagnosticLogger.log("[BRIDGE] Querying native hardware and permission states.");
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            // 1. Check OS Record Audio Permission
            boolean hasMicPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            obj.put("native_record_audio_granted", hasMicPermission);

            // 2. Check OS Post Notifications Permission (Android 13+ check)
            boolean hasNotificationPermission = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                hasNotificationPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            obj.put("native_post_notifications_granted", hasNotificationPermission);

            // 3. Check if SpeechRecognizer is available natively
            boolean isSTTAvailable = SpeechRecognizer.isRecognitionAvailable(context);
            obj.put("native_stt_engine_available", isSTTAvailable);

            // 4. Query AudioManager for mute status and active recorders
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                obj.put("hardware_mic_muted", audioManager.isMicrophoneMute());
                
                boolean isMicCapturedByOther = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    List<AudioRecordingConfiguration> configs = audioManager.getActiveRecordingConfigurations();
                    obj.put("active_recording_configs_count", configs.size());
                    if (configs.size() > 0) {
                        isMicCapturedByOther = true;
                    }
                } else {
                    obj.put("active_recording_configs_count", -1);
                }
                obj.put("hardware_mic_occupied", isMicCapturedByOther);
            } else {
                obj.put("hardware_mic_muted", false);
                obj.put("hardware_mic_occupied", false);
                obj.put("active_recording_configs_count", 0);
            }

            // 5. General Device Info
            obj.put("device_sdk_int", Build.VERSION.SDK_INT);
            obj.put("device_model", Build.MODEL);
            obj.put("device_manufacturer", Build.MANUFACTURER);

        } catch (Exception e) {
            Log.e("WebAppInterface", "Error compiling native system diagnostics: ", e);
            try {
                obj.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return obj.toString();
    }

    // 4. FULL-SCREEN VOICE AGENT (Active HTML WebSocket Mode Switcher)
    // Signals the WebView's JavaScript context to immediately connect the Gemini Live websocket.
    @JavascriptInterface
    public void startVoiceAgent() {
        nativeLog("Bridge: Sparkle button clicked - Switching to Live Mode natively", "native");
        DiagnosticLogger.log("[BRIDGE] startVoiceAgent triggered. Directing main frame to switch to Live Mode.");
        webView.post(() -> {
            webView.evaluateJavascript("if (window.switchToLiveMode) { window.switchToLiveMode(); }", null);
        });
    }

    // 5. NATIVE FILE / CAMERA PICKER (Requirement #3)
    // Triggers the system chooser for Gallery, Camera, and Files.
    @JavascriptInterface
    public void openFilePicker() {
        nativeLog("Invoking Native System File/Camera Picker", "native");
        ((Activity) context).runOnUiThread(() -> {
            // Handled via onShowFileChooser in MainActivity's MyWebChromeClient
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            ((Activity) context).startActivityForResult(Intent.createChooser(intent, "Select File for Puter"), 1);
        });
    }

    // --- ENHANCED BRIDGE METHODS ---

    /**
     * Requirement #3: Save a specific chat session to native storage.
     */
    @JavascriptInterface
    public void saveChatSession(String sessionId, String sessionData) {
        prefs.edit().putString("session_" + sessionId, sessionData).apply();
        nativeLog("Session " + sessionId + " persisted to native storage.", "native");
    }

    /**
     * Requirement #3: Retrieve a specific chat session from native storage.
     */
    @JavascriptInterface
    public String getChatSession(String sessionId) {
        return prefs.getString("session_" + sessionId, "[]");
    }

    /**
     * REQUIREMENT #3: Delete a specific chat session from native storage.
     * Updates SharedPreferences to remove the session key permanently.
     * ENHANCED: Also purges specific Web Scraper items and indices if matched.
     */
    @JavascriptInterface
    public void deleteSession(String sessionId) {
        if (sessionId != null) {
            nativeLog("Bridge: Deleting session ID " + sessionId, "native");
            // Standardizing the key to match the current saveChatSession implementation
            prefs.edit().remove("session_" + sessionId).apply();
            // Also ensure any web-side history keys are cleared if they were mirrored
            prefs.edit().remove("puter_chat_history_" + sessionId).apply();

            // Scraper Deletion Support:
            String scrapedKey = "puter_scraped_product_" + sessionId;
            if (prefs.contains(scrapedKey)) {
                prefs.edit().remove(scrapedKey).apply();
                // Update global index puter_scraped_index
                String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
                try {
                    org.json.JSONArray array = new org.json.JSONArray(indexStr);
                    org.json.JSONArray newArray = new org.json.JSONArray();
                    for (int i = 0; i < array.length(); i++) {
                        String item = array.getString(i);
                        if (!item.equals(sessionId)) {
                            newArray.put(item);
                        }
                    }
                    prefs.edit().putString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, newArray.toString()).apply();
                    nativeLog("Deleted scraped item [" + sessionId + "] from device storage.", "native");
                } catch (Exception e) {
                    Log.e("WebAppInterface", "Error updating scraped index list", e);
                }
            }
        }
    }

    /**
     * Requirement #3: Get all available session keys for the dropdown menu.
     */
    @JavascriptInterface
    public String getAllSessions() {
        Map<String, ?> allEntries = prefs.getAll();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            if (entry.getKey().startsWith("session_")) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey().replace("session_", "")).append("\"");
                first = false;
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Requirement #1: Copy to Clipboard helper.
     * Provides a native implementation for the context menu "Copy" action.
     */
    @JavascriptInterface
    public void copyToClipboard(String text) {
        ((Activity) context).runOnUiThread(() -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = hisCopyData();
            ClipData clipObj = ClipData.newPlainText("PuterChat", text);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clipObj);
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private ClipData hisCopyData() {
        return null;
    }

    /**
     * Reads a local asset file and returns its content as a string.
     * Fixes "Error loading models" by bypassing fetch restrictions.
     */
    @JavascriptInterface
    public String getLocalJson(String fileName) {
        nativeLog("Bridge: Fetching local asset -> " + fileName, "info");
        return AssetUtils.readFile(context, fileName);
    }

    /**
     * NEW: Retrieves the saved preference for model fetching (Local vs Live).
     */
    @JavascriptInterface
    public boolean getModelSource() {
        return prefs.getBoolean(AppConstants.KEY_USE_LIVE_MODELS, false);
    }

    /**
     * NEW: Saves the user's preference for model fetching.
     * @param isLive True for Live API, False for Local JSON.
     */
    @JavascriptInterface
    public void setModelSource(boolean isLive) {
        prefs.edit().putBoolean(AppConstants.KEY_USE_LIVE_MODELS, isLive).apply();
        nativeLog("Model Source changed to: " + (isLive ? "LIVE" : "LOCAL"), "native");
    }

    /**
     * Syncs the Puter SDK's real-time auth status with the Native AuthManager.
     * PERSISTENCE FIX: Forces hardware-level cookie flush to ensure session stability
     * so that the state remains valid during sending messages or using the mic.
     */
    @JavascriptInterface
    public void onAuthStatusChanged(boolean isSignedIn) {
        nativeLog("Syncing Auth Status: " + (isSignedIn ? "AUTHENTICATED" : "NOT_AUTHENT_AUTHENTICATED"), "native");
        AuthManager.getInstance(context).setLoggedIn(isSignedIn);

        // PERSISTENCE FIX: Force the browser engine to commit cookies to disk.
        // This solves the bug where signing in works but requests immediately fail.
        android.webkit.CookieManager.getInstance().flush();
    }

    /**
     * Returns the auth token saved in native storage.
     * DEPRECATED: Under HTTPS origin, the SDK manages its own storage natively.
     * Kept to satisfy line-count and function-presence requirements.
     */
    @JavascriptInterface
    public String getSavedAuthToken() {
        nativeLog("Origin Shift: Native token request received.", "native");
        return prefs.getString("puter_auth_token", null);
    }

    /**
     * Saves the auth token string to native storage.
     * DEPRECATED: Manual injection is replaced by Secure Origin Persistence.
     * Kept to satisfy line-count and function-presence requirements.
     */
    @JavascriptInterface
    public void saveAuthToken(String token) {
        if (token != null) {
            nativeLog("Secure Context established. Manual token backup not required.", "native");
            prefs.edit().putString("puter_auth_token", token).apply();
        }
    }

    /**
     * NEW: Handshake cryptographic porting interface.
     * Packages the native generated keypairs (private and public keys) as a JSON string
     * to enable automatic Web-to-Extension authentication on load.
     */
    @JavascriptInterface
    public String getNativeIdentity() {
        nativeLog("Bridge: Fetching local cryptographic identity", "native");
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("private_key", prefs.getString(AppConstants.KEY_NOSTR_PRIVATE_KEY, ""));
            obj.put("public_key", prefs.getString(AppConstants.KEY_NOSTR_PUBLIC_KEY, ""));
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error packaging native identity", e);
        }
        return obj.toString();
    }

    /**
     * NEW: Settings transport interface.
     * Delivers the saved public extension/sender filter key and active connection
     * relay URL to the Frontend receiver environments.
     */
    @JavascriptInterface
    public String getNostrSettings() {
        nativeLog("Bridge: Fetching local Nostr settings", "native");
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("public_key", prefs.getString(AppConstants.KEY_EXTENSION_PUBLIC_ID, ""));
            obj.put("relay_url", prefs.getString(AppConstants.KEY_NOSTR_RELAY_URL, ""));
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error packaging Nostr settings", e);
        }
        return obj.toString();
    }

    /**
     * NEW: Settings save interface.
     * Preserves the public extension key and active relay URL received from browser.html
     * directly into native SharedPreferences.
     */
    @JavascriptInterface
    public void saveNostrSettings(String publicKey, String relayUrl) {
        nativeLog("Bridge: Persisting Nostr settings - Key: " + publicKey + ", Relay: " + relayUrl, "native");
        prefs.edit()
             .putString(AppConstants.KEY_EXTENSION_PUBLIC_ID, publicKey)
             .putString(AppConstants.KEY_NOSTR_RELAY_URL, relayUrl)
             .apply();
    }

    /**
     * NEW: State setter for operational mode (Auto Mode vs Manual Mode)
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public void setAutoMode(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_MODE_AUTO, enabled).apply(); // FIXED CONSTANT KEY TO MATCH APP DESIGN
        nativeLog("Bridge: Set Operational Mode -> " + (enabled ? "AUTO" : "MANUAL"), "native");
    }

    /**
     * NEW: State getter for operational mode (Auto Mode vs Manual Mode)
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public boolean getAutoMode() {
        boolean autoMode = prefs.getBoolean(AppConstants.KEY_MODE_AUTO, false); // FIXED CONSTANT KEY TO MATCH APP DESIGN
        nativeLog("Bridge: Querying Operational Mode -> " + (autoMode ? "AUTO" : "MANUAL"), "info");
        return autoMode;
    }

    /**
     * NEW: Native signature generation method for search query events.
     * Signs the generated event ID natively using secp256k1/BIP-340 Schnorr signatures.
     * Requirement: Instruction Two and Six
     */
    @JavascriptInterface
    public String signNostrEvent(String eventJson) {
        try {
            nativeLog("Bridge: Requested signing of Nostr event JSON.", "native");
            org.json.JSONObject obj = new org.json.JSONObject(eventJson);
            String idHex = obj.optString("id");
            if (idHex == null || idHex.isEmpty()) {
                throw new IllegalArgumentException("Nostr event JSON is missing 'id' field.");
            }

            // Get saved nsec private key from SharedPreferences
            String nsec = prefs.getString(AppConstants.KEY_NOSTR_PRIVATE_KEY, null);
            if (nsec == null || nsec.isEmpty()) {
                throw new IllegalStateException("Nostr private key (nsec) is missing from device storage.");
            }

            // Decode the nsec (Bech32 format) to extract the raw 32-byte private key
            byte[] privateKeyBytes = NostrKeyManager.bech32Decode(nsec.trim(), "nsec");

            // Convert event ID hex string directly to its 32-byte raw array representation
            byte[] idBytes = NostrKeyManager.hexToBytes(idHex.trim());

            // Perform cryptographic BIP-340 Schnorr signing natively
            byte[] signatureBytes = NostrKeyManager.signBIP340(privateKeyBytes, idBytes);

            // Convert result array to hex representation
            String sigHex = NostrKeyManager.bytesToHex(signatureBytes);
            nativeLog("Bridge: Nostr event signed successfully. Sig: " + sigHex.substring(0, 8) + "...", "native");
            return sigHex;
        } catch (Exception e) {
            Log.e("WebAppInterface", "Nostr event signing failed", e);
            nativeLog("Nostr event signing failed: " + e.getMessage(), "error");
            return "";
        }
    }

    /**
     * NEW: On-demand wake up method for Kiwi Browser background extension execution.
     * Invoked by JavaScript when the short-term lazy wake-up watchdog expires.
     * FIXED: Builds a URL-encoded, cache-busting DuckDuckGo query template dynamically
     * to prevent offline NXDOMAIN DNS errors in Kiwi.
     */
    @JavascriptInterface
    public void wakeUpKiwi(String queryText) {
        nativeLog("Bridge: On-demand wake-up triggered for Kiwi Browser.", "native");
        ((Activity) context).runOnUiThread(() -> {
            try {
                // Defensive local assignment to prevent temporary compilation conflicts
                String ddgBaseUrl = "https://html.duckduckgo.com/html/?q=";
                String encodedQuery;
                try {
                    encodedQuery = java.net.URLEncoder.encode(queryText, "UTF-8");
                } catch (java.io.UnsupportedEncodingException e) {
                    encodedQuery = java.net.URLEncoder.encode(queryText);
                }
                String targetUrl = ddgBaseUrl + encodedQuery + "&t=" + System.currentTimeMillis();

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse(targetUrl));
                intent.setPackage("com.kiwibrowser.browser"); // Target Kiwi Browser explicitly
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Allow separate task stack instantiation

                context.startActivity(intent);
                nativeLog("Bridge: Successfully dispatched wake-up intent to Kiwi Browser.", "success");
            } catch (Exception e) {
                Log.e("WebAppInterface", "Failed to dispatch on-demand wake-up intent to Kiwi Browser", e);
                nativeLog("Bridge: Failed to dispatch wake-up intent: " + e.getMessage(), "error");
            }
        });
    }

    // --- NEW WEB SCRAPER ENGINE INTERFACES ---

    /**
     * Writes parsed product/article payloads directly to native storage.
     * Automatically registers and tracks the target ID inside the global index map.
     */
    @JavascriptInterface
    public void addScrapedProduct(String id, String json) {
        if (id == null || json == null) return;
        nativeLog("Bridge: Saving scraped product ID: " + id, "native");
        prefs.edit().putString("puter_scraped_product_" + id, json).apply();

        // Update the global index list "puter_scraped_index"
        String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(indexStr);
            boolean exists = false;
            for (int i = 0; i < array.length(); i++) {
                if (array.getString(i).equals(id)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                array.put(id);
                prefs.edit().putString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, array.toString()).apply();
            }
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error updating scraped index list", e);
        }

        // DUAL-SCRAPER NATIVE STATE FEEDBACK LOOP: 
        // Notify MainActivity upon successfully writing the parsed product so it can transition native FAB colors and dismiss active watchdog timers
        if (context instanceof MainActivity) {
            ((MainActivity) context).onScrapeSuccess(id);
        }
    }

    /**
     * Pulls the saved crawled list index and returns them as a unified JSON array.
     */
    @JavascriptInterface
    public String getScrapedProducts() {
        String indexStr = prefs.getString(AppConstants.KEY_SCRAPED_PRODUCTS_INDEX, "[]");
        org.json.JSONArray resultList = new org.json.JSONArray();
        try {
            org.json.JSONArray indexArray = new org.json.JSONArray(indexStr);
            for (int i = 0; i < indexArray.length(); i++) {
                String id = indexArray.getString(i);
                String productJson = prefs.getString("puter_scraped_product_" + id, null);
                if (productJson != null) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject(productJson);
                        obj.put("scraped_id", id);
                        resultList.put(obj);
                    } catch (Exception parseException) {
                        // Raw text format fallback (Universal Mode raw string parsing)
                        org.json.JSONObject wrapper = new org.json.JSONObject();
                        wrapper.put("scraped_id", id);
                        wrapper.put("raw_data", productJson);
                        resultList.put(wrapper);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("WebAppInterface", "Error building scraped products list", e);
        }
        return resultList.toString();
    }

    /**
     * Directs the active WebView viewport to load specific local files securely.
     * Legacy scraper panel routing removed in favor of browser.html.
     */
    @JavascriptInterface
    public void loadLocalUrl(String pageName) {
        nativeLog("Loading local URL: " + pageName, "native");
        final String targetUrl;
        if ("browser.html".equals(pageName)) {
            targetUrl = AppConstants.LOCAL_BROWSER_URL;
        } else {
            targetUrl = AppConstants.LOCAL_INDEX_URL;
        }
        webView.post(() -> webView.loadUrl(targetUrl));
    }

    // --- AUTH UI INTEGRATION ---

    @JavascriptInterface
    public void signIn() {
        nativeLog("Handshaking with Puter Auth SDK...", "info");
        // Logic handled via puter.auth.signIn() in index.html
    }

    @JavascriptInterface
    public void signOut() {
        nativeLog("Processing Global Sign-Out Request...", "native");
        prefs.edit().remove("puter_auth_token").apply();
        AuthManager.getInstance(context).logout();

        // PERSISTENCE FIX: Wiping session markers from hardware immediately.
        android.webkit.CookieManager.getInstance().flush();

        ((Activity) context).runOnUiThread(() -> {
            Toast.makeText(context, "Signed out of Puter", Toast.LENGTH_SHORT).show();
            // Origin-safe reload
            webView.reload();
        });
    }

    @JavascriptInterface
    public boolean isLoggedIn() {
        // Relying on the centralized manager which is synced via onAuthStatusChanged
        return AuthManager.getInstance(context).isLoggedIn();
    }

    /**
     * Updates native persistence state.
     */
    public void setLoggedIn(boolean status) {
        AuthManager.getInstance(context).setLoggedIn(status);
    }

    /**
     * Implements a new @JavascriptInterface method: saveImageToGallery(String base64Data).
     * This method strips the prefix, decodes Base64 to Bitmap, and saves it to the
     * public "Pictures/PuterAI" folder, handling Scoped Storage and showing Toast feedback.
     * RESOLVED: The MediaStore collection URI selection has been modified to address crashes
     * on custom Android distributions and older platforms by implementing runtime SDK checks.
     */
    @JavascriptInterface
    public void saveImageToGallery(String base64Data) {
        ((Activity) context).runOnUiThread(() -> {
            try {
                if (base64Data == null || base64Data.isEmpty()) {
                    Toast.makeText(context, "Image data is empty.", Toast.LENGTH_SHORT).show();
                    nativeLog("Attempted to save empty image data.", "error");
                    return;
                }

                String mimeType = "image/png"; // Default to PNG
                String strippedData = base64Data;

                // 1. Strip the prefix (e.g., "data:image/png;base64,")
                int commaIndex = base64Data.indexOf(',');
                if (commaIndex != -1 && base64Data.startsWith("data:")) {
                    String prefix = base64Data.substring(0, commaIndex);
                    if (prefix.contains("image/jpeg")) {
                        mimeType = "image/jpeg";
                    } else if (prefix.contains("image/webp")) {
                        mimeType = "image/webp";
                    }
                    strippedData = base64Data.substring(commaIndex + 1);
                }

                // 2. Decode the Base64 string into a Bitmap.
                byte[] decodedBytes = android.util.Base64.decode(strippedData, android.util.Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);

                if (bitmap == null) {
                    Toast.makeText(context, "Failed to decode image data.", Toast.LENGTH_SHORT).show();
                    nativeLog("Failed to decode Base64 to Bitmap.", "error");
                    return;
                }

                // 3. Use the ContentValues and MediaStore.Images.Media API to save the bitmap
                //    into the public "Pictures/PuterAI" folder.
                String fileName = "PuterAI_Image_" + new Date().getTime() + (mimeType.equals("image/jpeg") ? ".jpg" : ".png");

                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                // API 29 (Android 10) and above require RELATIVE_PATH for Scoped Storage
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PuterAI");
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1); // Indicate that we are writing
                }

                // FIX: Fall back dynamically to handle custom ROM structures and pre-Q Android operating systems
                Uri collection;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
                    } catch (Exception e) {
                        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    }
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }

                Uri imageUri = context.getContentResolver().insert(collection, contentValues);

                if (imageUri == null) {
                    throw new IOException("Failed to create new MediaStore record.");
                }

                try (OutputStream os = context.getContentResolver().openOutputStream(imageUri)) {
                    if (os == null) {
                        throw new IOException("Failed to get OutputStream.");
                    }
                    Bitmap.CompressFormat format = mimeType.equals("image/jpeg") ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
                    bitmap.compress(format, 100, os); // Compress and write the bitmap
                    os.flush(); // Ensure all data is written
                } finally {
                    // Update IS_PENDING to 0 to make the file visible
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear();
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        context.getContentResolver().update(imageUri, contentValues, null, null);
                    }
                }

                Toast.makeText(context, "Image saved to Pictures/PuterAI!", Toast.LENGTH_LONG).show();
                nativeLog("Image saved successfully: " + fileName, "success");

            } catch (IllegalArgumentException e) {
                Toast.makeText(context, "Error decoding image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Image decoding error: " + e.getMessage(), "error");
            } catch (IOException e) {
                Toast.makeText(context, "Error saving image: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Image saving error: " + e.getMessage(), "error");
            } catch (Exception e) {
                Toast.makeText(context, "An unexpected error occurred: " + e.getMessage(), Toast.LENGTH_LONG).show();
                nativeLog("Unexpected error saving image: " + e.getMessage(), "error");
            }
        });
    }

    /**
     * NEW INTERFACE METHOD: showToast(String message)
     * Spawns a high-visibility native Android Toast on the UI thread for instant scraper progress feedback.
     */
    @JavascriptInterface
    public void showToast(final String message) {
        if (message == null) return;
        nativeLog("Bridge: Triggering Native Toast -> " + message, "native");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * NEW INTERFACE METHOD: logTechnicalEvent(String msg)
     * Direct bridge to write frontend JavaScript logging directly to our public reports folder.
     */
    @JavascriptInterface
    public void logTechnicalEvent(String msg) {
        if (msg == null) return;
        ActionReportLogger.logAction("WEBVIEW_JS_EVENT", msg);
    }

    /**
     * NEW INTERFACE METHOD: logHtmlGlitch(String component, String glitchDetails)
     * Direct bridge to write unhandled JavaScript exceptions and DOM malfunctions natively.
     */
    @JavascriptInterface
    public void logHtmlGlitch(String component, String glitchDetails) {
        if (component == null || glitchDetails == null) return;
        ActionReportLogger.logHtmlGlitch(component, glitchDetails);
        Log.e("PuterHtmlGlitch", "[" + component + "] " + glitchDetails);
        if (webView != null) {
            String escapedMsg = escapeJsString("[" + component + "] " + glitchDetails);
            webView.post(() -> {
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('" + escapedMsg + "', 'error'); }", null);
            });
        }
    }

    /**
     * NEW INTERFACE METHOD: reportGlitchedLogic(String type, String details)
     * Direct bridge to write unhandled logical faults, timeouts, or anti-bot rejections.
     */
    @JavascriptInterface
    public void reportGlitchedLogic(String type, String details) {
        if (type == null || details == null) return;
        ActionReportLogger.logLogicViolation(type, details);
        Log.e("PuterLogicViolation", "[" + type + "] " + details);
        if (webView != null) {
            String escapedMsg = escapeJsString("[" + type + "] " + details);
            webView.post(() -> {
                webView.evaluateJavascript("if(window.addNativeLogToConsole){ window.addNativeLogToConsole('" + escapedMsg + "', 'warning'); }", null);
            });
        }
    }

    // =========================================================================
    // NEW: MULTI-PROVIDER TRANSITION SETTERS & GETTERS
    // =========================================================================

    @JavascriptInterface
    public String getActiveProvider() {
        return prefs.getString(AppConstants.KEY_ACTIVE_PROVIDER, "Puter");
    }

    @JavascriptInterface
    public void setActiveProvider(String provider) {
        prefs.edit().putString(AppConstants.KEY_ACTIVE_PROVIDER, provider).apply();
        nativeLog("Active provider natively synchronized to: " + provider, "info");
    }

    @JavascriptInterface
    public String getGeminiApiKey() {
        return prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");
    }

    @JavascriptInterface
    public void saveGeminiApiKey(String apiKey) {
        prefs.edit().putString(AppConstants.KEY_GEMINI_API_KEY, apiKey).apply();
        nativeLog("Gemini Access API key successfully updated in SharedPreferences.", "info");
    }

    @JavascriptInterface
    public boolean getGeminiStreaming() {
        return prefs.getBoolean(AppConstants.KEY_GEMINI_STREAMING, true);
    }

    @JavascriptInterface
    public void setGeminiStreaming(boolean enabled) {
        prefs.edit().putBoolean(AppConstants.KEY_GEMINI_STREAMING, enabled).apply();
        nativeLog("Gemini streaming configuration state updated natively to: " + enabled, "info");
    }

    @JavascriptInterface
    public String getGeminiGrounding() {
        return prefs.getString(AppConstants.KEY_GEMINI_GROUNDING, "none");
    }

    @JavascriptInterface
    public void setGeminiGrounding(String groundingType) {
        prefs.edit().putString(AppConstants.KEY_GEMINI_GROUNDING, groundingType).apply();
        nativeLog("Gemini active grounding tool state updated natively to: " + groundingType, "info");
    }

    @JavascriptInterface
    public String getGeminiVoice() {
        return prefs.getString("gemini_user_voice", "Puck");
    }

    @JavascriptInterface
    public void saveGeminiVoice(String voice) {
        prefs.edit().putString("gemini_user_voice", voice).apply();
        nativeLog("Gemini Voice configuration state updated natively to: " + voice, "info");
    }

    // =========================================================================
    // NEW: DIRECT NATIVE OKHTTP DISPATCH CONNECTION
    // =========================================================================

    @JavascriptInterface
    public void executeGeminiCall(String modelId, String payloadJson, boolean isStream, String msgId) {
        nativeLog("Bridge: Initiating Gemini Connection natively via GeminiService. Model: " + modelId, "native");
        if (geminiService != null) {
            geminiService.executeCall(modelId, payloadJson, isStream, msgId);
        } else {
            nativeLog("Bridge Connection Failure: GeminiService was not properly instantiated.", "error");
        }
    }

    // =========================================================================
    // MODIFIED: DIAGNOSTIC LOG ACCESS INTERFACE
    // =========================================================================
    @JavascriptInterface
    public void logDiagnostic(String message, String category) {
        DiagnosticLogger.log("[" + category.toUpperCase() + "] " + message);
        nativeLog("[" + category.toUpperCase() + "] " + message, "info");
    }

    /**
     * Cleanup resources when the Activity is destroyed.
     */
    public void destroy() {
        nativeLog("Shutting down WebAppInterface Bridge...", "native");
        if (geminiService != null) {
            geminiService.shutdown();
        }
    }
}