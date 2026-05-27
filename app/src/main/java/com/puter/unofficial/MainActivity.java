package com.puter.unofficial;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity: The primary host for the Puter Unofficial WebView.
 * Handles permissions, hardware-level WebView configuration, 
 * and communication with the native Voice Agent Activity.
 * 
 * UPDATED: Fixed File Picker logic to ensure Bridge-initiated uploads 
 * are converted to Base64 and sent to the stagedFiles UI.
 * UPDATED: Integrated runtime POST_NOTIFICATIONS check and startForegroundService.
 * CRITICAL LIFECYCLE FIXES: Implemented clean onPause microphone release and onResume STT context recreation.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private VoiceManager voiceManager;
    private WebAppInterface webAppInterface;
    private MyWebChromeClient myWebChromeClient; // Custom client for popups/uploads

    // Native Browser Control Panel Views
    private LinearLayout browserToolbar;
    private Button btnBrowserExit;
    private EditText inputBrowserAddress;
    private Button btnBrowserBack;
    private Button btnBrowserForward;
    private Button btnBrowserReload;
    private Button btnBrowserScraper; // Added native scraper button view reference
    private FloatingActionButton fabScrape;

    // Background handler for managing native scraping timers and watchdogs
    private final android.os.Handler scrapeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable scrapeTimeoutRunnable;

    // Receiver to catch results from the Full-Screen Voice Agent Activity
    private BroadcastReceiver voiceReceiver;

    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private final static int PERMISSION_REQUEST_CODE = 100;

    // Logic Guard to prevent the UI Blinking Loop during Auth/Reloads
    private boolean isRefreshing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // REQUIREMENT: Force White/Light Theme at the Activity Level
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar);

        // PERFORMANCE FIX: Pre-warm the WebView class loader context off-screen
        // to mitigate Chromium binder setting stalls during main layout inflation.
        try {
            Class.forName("android.webkit.WebView");
        } catch (Exception ignored) {
            Log.w("MainActivity", "WebView class loader pre-warmup bypassed.");
        }

        setContentView(R.layout.activity_main);

        // =========================================================================
        // NATIVE IDENTITY AUTO-CHECK & INITIALIZATION
        // =========================================================================
        android.content.SharedPreferences prefs = getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(AppConstants.KEY_NOSTR_PRIVATE_KEY) || !prefs.contains(AppConstants.KEY_NOSTR_PUBLIC_KEY)) {
            try {
                String[] keys = NostrKeyManager.generateKeyPair();
                prefs.edit()
                     .putString(AppConstants.KEY_NOSTR_PRIVATE_KEY, keys[0])
                     .putString(AppConstants.KEY_NOSTR_PUBLIC_KEY, keys[1])
                     .apply();
                Log.d("MainActivity", "Identity Handshake: Generated new secure Nostr keypair. PubKey: " + keys[1]);
            } catch (Exception e) {
                Log.e("MainActivity", "Identity Handshake: Cryptographic key generation failed", e);
            }
        }

        webView = findViewById(R.id.webView);

        // Bind Native Browser Controls from activity_main.xml
        browserToolbar = findViewById(R.id.browserToolbar);
        btnBrowserExit = findViewById(R.id.btnBrowserExit);
        inputBrowserAddress = findViewById(R.id.inputBrowserAddress);
        btnBrowserBack = findViewById(R.id.btnBrowserBack);
        btnBrowserForward = findViewById(R.id.btnBrowserForward);
        btnBrowserReload = findViewById(R.id.btnBrowserReload);
        btnBrowserScraper = findViewById(R.id.btnBrowserScraper); // Bound native scraper button
        fabScrape = findViewById(R.id.fabScrape);

        // Setup Native Browser Controls click listeners
        btnBrowserExit.setOnClickListener(v -> loadIndexHtml());
        btnBrowserBack.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
        });
        btnBrowserForward.setOnClickListener(v -> {
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
        });
        btnBrowserReload.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });
        btnBrowserScraper.setOnClickListener(v -> {
            if (webView != null) {
                // Redirects directly to the browser.html receiver inbox since scraper.html is removed
                webView.loadUrl(AppConstants.LOCAL_BROWSER_URL);
            }
        });

        // Handle keyboard actions inside address input field
        inputBrowserAddress.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                String url = inputBrowserAddress.getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://" + url;
                    }
                    webView.loadUrl(url);
                    // Hide virtual keyboard
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(inputBrowserAddress.getWindowToken(), 0);
                    }
                }
                return true;
            }
            return false;
        });

        // Handle clicking the Native Scraping FAB by implementing native color state timers and redirects
        fabScrape.setOnClickListener(v -> startScrapeSequence());

        // DIAGNOSTICS: Enable Remote Debugging via Chrome DevTools (pc)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // --- WebView Configuration: Deep Hardware Settings ---
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // CRITICAL FOR AUTH: Allows Puter SDK to open the Sign-In Popup Window
        webSettings.setSupportMultipleWindows(true); 

        // DIAGNOSTICS: Allow scripts to access local content for debug console
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // FIX: Remove the WebView identifier ("; wv") from the User Agent.
        // This tricks the Puter.js SDK into initializing correctly in an app context.
        String userAgent = webSettings.getUserAgentString();
        userAgent = userAgent.replace("; wv", "");
        webSettings.setUserAgentString(userAgent);

        // FIX FOR PUTER.JS AUTH: Enable and force Third-Party Cookie acceptance.
        // This is necessary to bridge the session between the popup and main window.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true);
        }

        // Standard Mobile Viewport scaling
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // --- Client Assignments ---
        // PuterWebViewClient handles virtual HTTPS routing for session persistence
        webView.setWebViewClient(new PuterWebViewClient(this));

        // MyWebChromeClient handles native file pickers and the Auth Popup Dialog
        myWebChromeClient = new MyWebChromeClient(this);
        webView.setWebChromeClient(myWebChromeClient);

        // --- Native Manager Initialization ---
        voiceManager = new VoiceManager(this, webView);
        webAppInterface = new WebAppInterface(this, webView);

        // Linking Voice and Bridge for Barge-in (Interruption) support
        webAppInterface.setVoiceManager(voiceManager);
        voiceManager.setBridge(webAppInterface);

        // --- JavaScript Bridge Registration ---
        // Exposes 'window.AndroidInterface' to the HTML/JS logic
        webView.addJavascriptInterface(webAppInterface, AppConstants.JS_BRIDGE_NAME);

        // Load the frontend via the Secure Origin Asset Loader
        webView.loadUrl(AppConstants.LOCAL_INDEX_URL);

        // --- Native Voice Loop Setup ---
        setupVoiceReceiver();

        // Check and Request System Permissions
        checkAndRequestPermissions();
    }

    /**
     * Executes the native scraping sequence including Amazon redirects, Same-Origin
     * evaluations, and background color state handler timers.
     */
    private void startScrapeSequence() {
        if (webView == null) return;

        String activeUrl = webView.getUrl();
        if (activeUrl == null || activeUrl.startsWith("about:blank") || activeUrl.startsWith(AppConstants.LOCAL_INDEX_URL)) {
            Toast.makeText(this, "Navigate to a webpage first before scraping.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (activeUrl.contains("amazon.in") || activeUrl.contains("amazon.com")) {
            // E-Commerce Route: Redirect directly to the secure local browser.html receiver inbox
            Log.d("MainActivity", "Native Scraper: Navigating directly to local browser.html for Amazon product.");
            webView.loadUrl(AppConstants.LOCAL_BROWSER_URL);
        } else {
            // Universal Mode: Trigger the scraper and manage color state transitions natively
            scrapeHandler.removeCallbacksAndMessages(null); // Cancel any lingering progress timers

            // 1. Transition instantly to Scraping state (RED)
            fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D93025")));

            // 2. Set timeout to transition to intermediate Processing state (ORANGE) after 1.2 seconds
            scrapeHandler.postDelayed(() -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F2994A")));
            }, 1200);

            // 3. START DIAGNOSTIC WATCHDOG TIMER (8-seconds limit)
            scrapeTimeoutRunnable = () -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D93025"))); // Revert to RED indicating failure
                Toast.makeText(MainActivity.this, "Scraping failed: Iframe or Script Timeout.", Toast.LENGTH_LONG).show();

                // Smoothly return back to original Blue state after 4 seconds
                scrapeHandler.postDelayed(() -> {
                    fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
                }, 4000);
            };
            scrapeHandler.postDelayed(scrapeTimeoutRunnable, 8000);

            // Evaluate standard postMessage to trigger the injected script inside the current same-origin main window context
            webView.evaluateJavascript("window.postMessage('scrape', '*');", null);
        }
    }

    /**
     * Public success callback invoked by WebAppInterface upon successful parsing inside addScrapedProduct.
     */
    public void onScrapeSuccess(String scrapedId) {
        runOnUiThread(() -> {
            // Clear active progress and watchdog timers immediately
            scrapeHandler.removeCallbacksAndMessages(null);

            // Transition to Finished state (GREEN) on success callback
            fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#27AE60")));

            // Trigger native Toast message feedback
            Toast.makeText(MainActivity.this, "JSON sent successfully to Puter Unofficial", Toast.LENGTH_LONG).show();

            // Smoothly revert back to original Blue state after 4 seconds
            scrapeHandler.postDelayed(() -> {
                fabScrape.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1A73E8")));
            }, 4000);
        });
    }

    /**
     * Toggles visibility of the Native browser control toolbar and the Scraping FAB.
     * Triggered automatically by the WebViewClient during page transitions.
     */
    public void handleUrlChange(String url) {
        runOnUiThread(() -> {
            if (url == null) return;

            // Hide native controls if loading ANY local web assets or the main index page
            if (url.startsWith(AppConstants.LOCAL_INDEX_URL) || url.contains("browser.html")) {
                browserToolbar.setVisibility(View.GONE);
                fabScrape.setVisibility(View.GONE);
            } else {
                // If browsing an external page, show native browser controls
                browserToolbar.setVisibility(View.VISIBLE);
                fabScrape.setVisibility(View.VISIBLE);

                // Dynamically update the address field text when not active
                if (!inputBrowserAddress.isFocused()) {
                    inputBrowserAddress.setText(url);
                }
            }
        });
    }

    /**
     * Navigates back to the main local index URL.
     */
    private void loadIndexHtml() {
        if (webView != null) {
            webView.loadUrl(AppConstants.LOCAL_INDEX_URL);
        }
    }

    /**
     * Requirement #3: Voice Agent Communication.
     * Listens for the "PUTER_VOICE_INPUT" broadcast from VoiceAgentActivity.
     * When the full-screen mode finishes a turn, it injects the text here.
     */
    private void setupVoiceReceiver() {
        voiceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("PUTER_VOICE_INPUT".equals(intent.getAction())) {
                    String query = intent.getStringExtra("QUERY");
                    if (query != null) {
                        Log.d("MainActivity", "Voice Input Received: " + query);
                        injectSpeechToWebView(query);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("PUTER_VOICE_INPUT");

        // Android 13+ compatibility for exported receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(voiceReceiver, filter);
        }
    }

    /**
     * Requirement #1: Broadcast AI response text back to VoiceAgentActivity.
     */
    public void broadcastAiResponse(String text) {
        Intent intent = new Intent("PUTER_AI_RESPONSE");
        intent.putExtra("RESPONSE_TEXT", text);
        sendBroadcast(intent);
        Log.d("MainActivity", "AI Response Broadcasted to Native Agent");
    }

    /**
     * Requirement #3: Injects spoken text into the index.html logic.
     */
    private void injectSpeechToWebView(String text) {
        String safeText = text.replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "if(window.onSpeechResult) { window.onSpeechResult('" + safeText + "'); }",
                null)
        );
    }

    /**
     * Launches the QueryWatcherService safely using ContextCompat.
     */
    private void startWatcherService() {
        try {
            Intent serviceIntent = new Intent(this, QueryWatcherService.class);
            ContextCompat.startForegroundService(this, serviceIntent);
            Log.d("MainActivity", "QueryWatcherService background monitor started successfully.");
        } catch (Exception e) {
            Log.e("MainActivity", "Fatal failure starting QueryWatcherService background execution: ", e);
        }
    }

    /**
     * Validates and requests all necessary hardware permissions.
     */
    private void checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        listPermissionsNeeded.add(Manifest.permission.INTERNET);
        listPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        listPermissionsNeeded.add(Manifest.permission.CAMERA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listPermissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
            // REQUIREMENT: Check and Request system notifications permissions on Android 13+
            listPermissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            listPermissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            listPermissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }

        List<String> remainingPermissions = new ArrayList<>();
        for (String permission : listPermissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                remainingPermissions.add(permission);
            }
        }

        if (!remainingPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, remainingPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            // All permission constraints already satisfied; launch the query watcher service immediately
            startWatcherService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            boolean notificationPermissionGranted = true;

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                        notificationPermissionGranted = false;
                    }
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "Voice and Image features require permissions.", Toast.LENGTH_SHORT).show();
            }

            // Start foreground watcher service safely once notification permissions are resolved or on older devices
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionGranted) {
                startWatcherService();
            }
        }
    }

    /**
     * UPDATED: Handles both standard WebChromeClient uploads and Bridge-initiated uploads.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 1. Logic for Bridge-initiated File Upload (Feature Enhancement #1)
        if (requestCode == FILE_CHOOSER_RESULT_CODE && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                Log.d("MainActivity", "File selected via Bridge: " + fileUri.toString());
                
                // Convert to Base64 Data URI
                String base64Data = FileUtils.fileToDataUri(this, fileUri);
                
                if (base64Data != null) {
                    // Inject the Base64 string directly into the JS stagedFiles array
                    webView.post(() -> {
                        webView.evaluateJavascript(
                            "if(window.onImageResult){ window.onImageResult('" + base64Data + "'); }", 
                            null
                        );
                    });
                }
            }
        }

        // 2. Original Requirement #3: Delegate standard file picker result back to ChromeClient
        if (myWebChromeClient != null) {
            myWebChromeClient.onActivityResult(requestCode, resultCode, data);
        }
    }

    /**
     * Requirement #5: Force-reloads the WebView UI.
     * Used after sign-out to clear session state.
     */
    public void reloadWebView() {
        runOnUiThread(() -> {
            if (webView != null && !isRefreshing) {
                isRefreshing = true;

                // Requirement #4: Persistence Fix. Ensure cookies are saved before reload.
                CookieManager.getInstance().flush();

                webView.reload();

                // Release reload guard after 3 seconds to prevent UI flicker
                webView.postDelayed(() -> isRefreshing = false, 3000);
            }
        });
    }

    /**
     * Overridden to intercept back button navigation dynamically.
     * Prevents the app from closing when browsing an external page.
     */
    @Override
    public void onBackPressed() {
        if (webView != null) {
            String currentUrl = webView.getUrl();
            if (currentUrl != null) {
                // If currently inside the active native browser session
                if (browserToolbar.getVisibility() == View.VISIBLE) {
                    if (webView.canGoBack()) {
                        webView.goBack(); // Navigate web history natively
                    } else {
                        loadIndexHtml(); // If no web history remains, gracefully load home index
                    }
                    return;
                }
                // If viewing other external webpages inside the top-level viewport
                else if (!currentUrl.startsWith("https://appassets.androidplatform.net/")) {
                    if (webView.canGoBack()) {
                        webView.goBack();
                        return;
                    }
                } 
                // If inside local sub-panels (like browser.html receiver)
                else if (currentUrl.contains("browser.html")) {
                    webView.loadUrl(AppConstants.LOCAL_INDEX_URL);
                    return;
                }
            }
        }
        // Default fallback if we are on index.html
        super.onBackPressed();
    }

    /**
     * Lifecycle hook called when the Activity is paused.
     * 
     * CRITICAL FIX: Completely destroys and cancels the background standard VoiceManager's 
     * SpeechRecognizer context to release the hardware microphone lock. stopListening() is not enough.
     */
    @Override
    protected void onPause() {
        super.onPause();
        
        // De-register background STT recognizers in MainActivity to prevent hardware conflicts
        if (voiceManager != null) {
            try {
                voiceManager.destroy(); // Completely cancel and destroy the recognizer to release the mic
                Log.d("MainActivity", "onPause: Destroyed background voiceManager to release hardware lock.");
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to destroy standard background VoiceManager: " + e.getMessage());
            }
            voiceManager = null; // Clear the reference to force a clean re-initialization on resume
        }
    }

    /**
     * Lifecycle hook called when the Activity returns to focus.
     * 
     * CRITICAL FIX: Automatically re-initializes the standard background VoiceManager 
     * instance on resume to restore speech dictation capabilities for standard chat mode.
     */
    @Override
    protected void onResume() {
        super.onResume();
        
        // Re-initialize the background VoiceManager when MainActivity returns to focus
        if (voiceManager == null && webView != null) {
            try {
                voiceManager = new VoiceManager(this, webView);
                if (webAppInterface != null) {
                    webAppInterface.setVoiceManager(voiceManager);
                }
                Log.d("MainActivity", "onResume: Re-initialized background voiceManager context.");
            } catch (Exception e) {
                Log.e("MainActivity", "Failed to re-initialize standard background VoiceManager: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        // Cleanup Native Resources
        if (voiceReceiver != null) {
            unregisterReceiver(voiceReceiver);
        }
        if (webView != null) {
            webView.destroy();
        }
        if (voiceManager != null) {
            voiceManager.destroy();
        }
        if (webAppInterface != null) {
            webAppInterface.destroy();
        }
        scrapeHandler.removeCallbacksAndMessages(null); // Purge any remaining scraper handshakes
        super.onDestroy();
    }
}