package com.puter.unofficial;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Handles the native file upload functionality (Camera, Gallery, File Picker)
 * for the WebView, enabling Base64 upload support for Puter AI interactions.
 * ADDED: Support for popup windows, auto-closing, and manual fallback required by the Puter.js auth SDK.
 */
public class MyWebChromeClient extends WebChromeClient {

    private ValueCallback<Uri[]> uploadMessage;
    private final Activity activity;
    private String currentPhotoPath;
    private Dialog authDialog; // Dialog to host the login popup WebView

    public MyWebChromeClient(Activity activity) {
        this.activity = activity;
    }

    // --- NEW: SDK AUTH POPUP HANDLER WITH AUTO-CLOSE & FALLBACK BUTTON ---

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;

        // 1. Create a Layout to hold a Close Button + The Popup WebView
        LinearLayout dialogLayout = new LinearLayout(activity);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setBackgroundColor(Color.WHITE);

        // 2. Create a Fail-Safe "Done" Button
        // If the auto-close fails or hangs on "Signing in...", the user can tap this to proceed.
        Button closeButton = new Button(activity);
        closeButton.setText("Close Window (Tap when Signed In)");
        closeButton.setBackgroundColor(Color.parseColor("#1a73e8"));
        closeButton.setTextColor(Color.WHITE);
        closeButton.setOnClickListener(v -> closeAuthAndRefresh());
        
        dialogLayout.addView(closeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 3. Create and configure the new WebView for the popup
        final WebView popupWebView = new WebView(activity);
        dialogLayout.addView(popupWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings webSettings = popupWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setSupportMultipleWindows(true);

        // Bypass Google 403 "disallowed_useragent" using a standard Chrome UA
        String standardChromeUA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
        webSettings.setUserAgentString(standardChromeUA);

        // Enable Third-Party Cookies so the Puter session saves correctly
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true);

        // 4. Inject a Native Bridge specifically for the popup to auto-close itself
        popupWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void notifySuccess() {
                Log.i(AppConstants.TAG_AUTH, "Popup JS Bridge detected auth token! Auto-closing.");
                activity.runOnUiThread(() -> closeAuthAndRefresh());
            }
        }, "AndroidPopupBridge");

        // 5. Monitor the popup for URL changes and inject the auto-close script
        popupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                Log.d(AppConstants.TAG_AUTH, "Popup URL: " + url);
                
                // Active URL monitoring
                if (url.contains(AppConstants.AUTH_TOKEN_PARAM) || url.contains(AppConstants.AUTH_SUCCESS_MARKER) || url.contains("auth_success")) {
                    Log.i(AppConstants.TAG_AUTH, "Auth success detected in URL! Manually closing popup.");
                    closeAuthAndRefresh();
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                CookieManager.getInstance().flush();
                
                // Inject an aggressive background script to check for successful login
                // It looks for tokens in localStorage, which Puter uses when it says "Signing in..."
                view.evaluateJavascript(
                    "(function() {" +
                    "   setInterval(function() {" +
                    "       let success = false;" +
                    "       for (let i = 0; i < localStorage.length; i++) {" +
                    "           if (localStorage.key(i).includes('token') || localStorage.key(i).includes('puter')) {" +
                    "               success = true; break;" +
                    "           }" +
                    "       }" +
                    "       if (success || window.location.href.includes('auth_success')) {" +
                    "           window.AndroidPopupBridge.notifySuccess();" +
                    "       }" +
                    "   }, 1500);" +
                    "})();", null);
            }
        });

        // 6. Handle the official window.close() call from Puter SDK
        popupWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                Log.i(AppConstants.TAG_AUTH, "Popup called window.close(). Completing login.");
                closeAuthAndRefresh();
            }
        });

        // 7. Show the Dialog
        authDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        authDialog.setContentView(dialogLayout);
        authDialog.show();
        
        transport.setWebView(popupWebView);
        resultMsg.sendToTarget();
        
        Log.d(AppConstants.TAG_AUTH, "onCreateWindow: Handled Puter auth popup completely.");
        return true;
    }

    /**
     * Helper method to finalize authentication, dismiss popup, and refresh main UI.
     */
    private void closeAuthAndRefresh() {
        Log.i(AppConstants.TAG_AUTH, "Finalizing Auth and closing dialog.");
        CookieManager.getInstance().flush(); // Save cookies permanently
        AuthManager.getInstance(activity).setLoggedIn(true); // Persist state natively
        
        if (authDialog != null && authDialog.isShowing()) {
            authDialog.dismiss();
            authDialog = null;
        }

        // Refresh the main app screen to apply SDK session
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).reloadWebView();
        }
    }

    @Override
    public void onCloseWindow(WebView window) {
        if (authDialog != null && authDialog.isShowing()) {
            authDialog.dismiss();
            authDialog = null;
        }
        super.onCloseWindow(window);
        Log.d(AppConstants.TAG_AUTH, "onCloseWindow: Main window closed Puter auth popup.");
    }

    // --- EXISTING: FILE UPLOAD LOGIC (UNCHANGED) ---

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
        if (uploadMessage != null) {
            uploadMessage.onReceiveValue(null);
        }
        uploadMessage = filePathCallback;

        // Intent for picking files
        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.setType("*/*"); // Allow all types

        // Intent for Camera
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(activity.getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
                takePictureIntent.putExtra("PhotoPath", currentPhotoPath);
            } catch (IOException ex) {
                // Error occurred
            }
            if (photoFile != null) {
                currentPhotoPath = "file:" + photoFile.getAbsolutePath();
                Uri photoURI = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
            } else {
                takePictureIntent = null;
            }
        }

        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, contentIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "Upload File");
        if (takePictureIntent != null) {
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { takePictureIntent });
        }

        activity.startActivityForResult(chooserIntent, 1);
        return true;
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = activity.getExternalFilesDir(null);
        return File.createTempFile(imageFileName, ".jpg", storageDir);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (uploadMessage == null) return;
        Uri[] results = null;

        if (resultCode == Activity.RESULT_OK) {
            if (data == null || data.getData() == null) {
                if (currentPhotoPath != null) {
                    results = new Uri[]{Uri.parse(currentPhotoPath)};
                }
            } else {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
        }
        uploadMessage.onReceiveValue(results);
        uploadMessage = null;
    }
}