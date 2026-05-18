package com.puter.unofficial;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> uploadMessage;
    private VoiceManager voiceManager;
    private WebAppInterface webAppInterface;
    private MyWebChromeClient myWebChromeClient; // Use our custom client

    // Receiver to catch results from the Full-Screen Voice Agent Activity
    private BroadcastReceiver voiceReceiver;

    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private final static int PERMISSION_REQUEST_CODE = 100;
    
    // Logic Guard to prevent the UI Blinking Loop
    private boolean isRefreshing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Force White/Light Theme at the Activity Level as per instructions
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        // FIX: Enable Remote Debugging. This allows you to see the console via Chrome DevTools.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // --- WebView Configuration (Thoroughly Maintained) ---
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setSupportMultipleWindows(true); // <-- REQUIRED FOR AUTH POPUP

        // FIX: Allow scripts from local assets to access other content. 
        // This is required for debug_console.js to function correctly.
        webSettings.setAllowFileAccessFromFileURLs(true);
        webSettings.setAllowUniversalAccessFromFileURLs(true);

        // FIX: Bypass SDK initialization hangs (Loading models bug) by removing the WebView identifier ("; wv").
        // This forces the Puter.js SDK to treat the app as a standard mobile browser, 
        // allowing it to fire the puter.ready() event successfully.
        String userAgent = webSettings.getUserAgentString();
        userAgent = userAgent.replace("; wv", "");
        webSettings.setUserAgentString(userAgent);

        // FIX FOR PUTER.JS AUTH: Enable Third Party Cookies for the main WebView.
        // Without this, the session token generated in the popup will not be accessible to the main window.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Ensure the WebView looks right on mobile viewports
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        // --- WebViewClient for Auth Persistence ---
        // Uses the PuterWebViewClient to intercept login redirects and handle AssetLoader routing
        webView.setWebViewClient(new PuterWebViewClient(this));

        // --- WebChromeClient for File Uploads AND AUTH POPUPS ---
        myWebChromeClient = new MyWebChromeClient(this);
        webView.setWebChromeClient(myWebChromeClient);

        // --- Initialize Native Managers ---
        voiceManager = new VoiceManager(this, webView);
        webAppInterface = new WebAppInterface(this, webView);

        // Link the managers to enable Barge-in and Microphone control
        webAppInterface.setVoiceManager(voiceManager);
        voiceManager.setBridge(webAppInterface);

        // --- JavaScript Interface Bridge ---
        // Exposes 'window.AndroidInterface' to index.html
        webView.addJavascriptInterface(webAppInterface, AppConstants.JS_BRIDGE_NAME);

        // Load the Puter Unofficial frontend via the new HTTPS Asset origin
        webView.loadUrl(AppConstants.LOCAL_INDEX_URL);

        // --- Voice Results Setup ---
        setupVoiceReceiver();

        // Request Permissions
        checkAndRequestPermissions();
    }

    /**
     * Listens for the "PUTER_VOICE_INPUT" intent sent from VoiceAgentActivity.
     * When the user finishes speaking in the full-screen mode, this injects 
     * the text back into the WebView.
     */
    private void setupVoiceReceiver() {
        voiceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("PUTER_VOICE_INPUT".equals(intent.getAction())) {
                    String query = intent.getStringExtra("QUERY");
                    if (query != null) {
                        injectSpeechToWebView(query);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("PUTER_VOICE_INPUT");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(voiceReceiver, filter);
        }
    }

    private void injectSpeechToWebView(String text) {
        String safeText = text.replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "if(window.onSpeechResult) { window.onSpeechResult('" + safeText + "'); }",
                null)
        );
    }

    private void checkAndRequestPermissions() {
        List<String> listPermissionsNeeded = new ArrayList<>();
        listPermissionsNeeded.add(Manifest.permission.INTERNET);
        listPermissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        listPermissionsNeeded.add(Manifest.permission.CAMERA);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listPermissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES);
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
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "Voice and Image features require permissions.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // Delegate the result to our custom WebChromeClient
        if (myWebChromeClient != null) {
            myWebChromeClient.onActivityResult(requestCode, resultCode, data);
        }
        // Handle other activity results if needed
    }


    /**
     * Required by SettingsFragment to refresh the UI when the user signs out.
     * UPDATED: Added a reload guard to prevent the "Blinking UI" loop during authentication.
     */
    public void reloadWebView() {
        runOnUiThread(() -> {
            if (webView != null && !isRefreshing) {
                isRefreshing = true;
                
                // Ensure cookies are synced before reload to maintain the session handshake
                CookieManager.getInstance().flush();
                
                webView.reload();
                
                // Release the guard after a delay to allow the page to settle
                webView.postDelayed(() -> isRefreshing = false, 3000);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
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
        super.onDestroy();
    }
}