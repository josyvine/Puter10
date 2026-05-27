package com.puter.unofficial;

import android.content.Context;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebView;

/**
 * A customized WebView component for Puter Unofficial.
 * This class handles specialized touch interactions and focus management 
 * to ensure that the search input and dropdown menus in the HTML frontend 
 * respond correctly to mobile keyboard events and screen resizing.
 */
public class PuterWebView extends WebView {

    public PuterWebView(Context context) {
        super(context);
    }

    public PuterWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PuterWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Overridden to improve the connection between the software keyboard 
     * and the HTML input fields. This helps prevent the "Enter" key from 
     * behaving unexpectedly in some Android versions.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection connection = super.onCreateInputConnection(outAttrs);
        if (connection != null) {
            // Ensure the keyboard "Done" or "Send" button triggers correctly
            outAttrs.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }
        return connection;
    }

    /**
     * Helper method to scroll the chat to the absolute bottom.
     * This can be called from Java after a new message is injected 
     * to ensure the user always sees the latest AI response.
     */
    public void scrollToBottom() {
        this.post(new Runnable() {
            @Override
            public void run() {
                // Invokes the JavaScript scroll logic within index.html
                loadUrl("javascript:window.scrollTo(0,document.body.scrollHeight);");
            }
        });
    }

    /**
     * Logic to handle window focus changes, ensuring that the 
     * Puter.js SDK maintains its session connection when the 
     * app is brought back to the foreground.
     * 
     * CRITICAL FIX: Tricks the native WebKit engine by forcing VISIBLE status 
     * to prevent background throttling of JS and Web Audio when VoiceAgentActivity is active.
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        if (isVoiceModeActive()) {
            // Force the internal WebView/Chromium provider to believe the window is still VISIBLE.
            // This prevents background rendering suspension and keeps the Web Audio Context active.
            super.onWindowVisibilityChanged(VISIBLE);
            this.onResume();
            this.resumeTimers();
        } else {
            super.onWindowVisibilityChanged(visibility);
            if (visibility == VISIBLE) {
                this.onResume();
                this.resumeTimers();
            } else {
                this.onPause();
                this.pauseTimers();
            }
        }
    }

    /**
     * Helper method to check the static voice mode state from WebAppInterface.
     */
    private boolean isVoiceModeActive() {
        try {
            return WebAppInterface.isVoiceModeActiveStatic;
        } catch (Exception e) {
            return false;
        }
    }
}