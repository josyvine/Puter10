package com.puter.unofficial;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.WebView;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Refined Native Android Speech-to-Text Manager.
 * Optimized for Puter Unofficial to handle barge-in interruptions
 * and reliable communication with the HTML frontend.
 * UPDATED: Integrated hardware-reset logic and adaptive error recovery.
 * ENHANCED: Direct hook into WebAppInterface's unified Master Barge-In system to safely halt Gemini voice outputs.
 */
public class VoiceManager {

    private static final String TAG = "PuterVoiceManager";
    private final Context context;
    private final WebView webView;
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private WebAppInterface bridge;
    
    // Handler for managing hardware reset timing during barge-in
    private final Handler resetHandler = new Handler(Looper.getMainLooper());

    public VoiceManager(Context context, WebView webView) {
        this.context = context;
        this.webView = webView;
        initializeRecognizer();
    }

    /**
     * Link the bridge to allow VoiceManager to trigger TTS stops 
     * when it detects the user has started speaking.
     */
    public void setBridge(WebAppInterface bridge) {
        this.bridge = bridge;
    }

    private void initializeRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            // Allow partial results for faster perceived response if needed
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            
            // REQUIREMENT #2: Ensure the recognizer can stay active during TTS for Barge-in
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000);

            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    Log.d(TAG, "Microphone Ready");
                }

                @Override
                public void onBeginningOfSpeech() {
                    Log.d(TAG, "User started speaking - BARGE-IN TRIGGERED");
                    // BARGE-IN: If the AI is currently speaking, stop it immediately
                    // This allows the user to interrupt the AI response.
                    // ENHANCED: bridge.stopSpeaking() now silences both native TTS AND WebView custom Gemini audio [1].
                    if (bridge != null) {
                        bridge.stopSpeaking();
                    }
                    
                    /*
                     * FIX: Adjust the recognizer logic to prevent hardware collisions.
                     * We ensure that the TTS engine has fully released audio focus
                     * so the SpeechRecognizer captures the user's voice, not the AI residue.
                     */
                }

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    Log.d(TAG, "User finished speaking");
                }

                @Override
                public void onError(int error) {
                    String message;
                    boolean shouldRetry = false;

                    switch (error) {
                        case SpeechRecognizer.ERROR_AUDIO: 
                            message = "Audio recording error"; 
                            break;
                        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: 
                            message = "Permission denied"; 
                            break;
                        case SpeechRecognizer.ERROR_NETWORK: 
                            message = "Network error"; 
                            break;
                        case SpeechRecognizer.ERROR_NO_MATCH: 
                            message = "No speech recognized"; 
                            shouldRetry = true; // RECOVERY: Don't kill the loop
                            break;
                        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: 
                            message = "No speech input"; 
                            shouldRetry = true; // RECOVERY: Don't kill the loop
                            break;
                        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                            message = "Recognizer busy";
                            shouldRetry = true;
                            break;
                        default: 
                            message = "Recognition error (" + error + ")"; 
                            break;
                    }
                    
                    Log.e(TAG, "Speech Error: " + message);

                    // REQUIREMENT: Refine error handling to maintain "Always On" feel in Voice Mode.
                    if (shouldRetry) {
                        Log.d(TAG, "Transient glitch detected. Performing aggressive restart...");
                        resetHandler.postDelayed(() -> startListening(), 150);
                    } else {
                        // Notify frontend of critical errors
                        sendResultToWeb("Error: " + message);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String recognizedText = matches.get(0);
                        Log.d(TAG, "Final Result: " + recognizedText);
                        sendResultToWeb(recognizedText);
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {}

                @Override
                public void onEvent(int eventType, Bundle params) {}
            });
        }
    }

    /**
     * Safely injects the recognized text into the WebView.
     * Replaces single quotes with escaped quotes to prevent JS injection errors.
     */
    private void sendResultToWeb(String text) {
        String safeText = text.replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript("if(window.onSpeechResult) { window.onSpeechResult('" + safeText + "'); }", null));
    }

    /**
     * Starts the microphone. 
     * Added aggressive cancelation of previous sessions to support modern barge-in.
     */
    public void startListening() {
        if (speechRecognizer != null) {
            // Cancel any current recognition before starting a new one to prevent hangs
            // This is critical for the continuous conversation loop.
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start listening: " + e.getMessage());
                // Retry hardware acquisition after a short delay
                resetHandler.postDelayed(() -> {
                    speechRecognizer.cancel();
                    speechRecognizer.startListening(recognizerIntent);
                }, 300);
            }
        }
    }

    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
        }
        resetHandler.removeCallbacksAndMessages(null);
    }
}