package com.puter.unofficial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Full-screen Native Voice Agent Activity for Puter Unofficial.
 * This implementation enables a continuous, hands-free conversation loop.
 * UPDATED: Optimized for Barge-in and Always-on listening during AI speech.
 */
public class VoiceAgentActivity extends AppCompatActivity {

    private static final String TAG = "PuterVoiceAgent";

    private TextView tvStatus, tvTranscript;
    private FloatingActionButton fabMic;
    private ImageButton btnClose;

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private Intent recognizerIntent;
    private BroadcastReceiver aiResponseReceiver;

    private boolean isListening = false;
    private boolean isAIspeaking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_agent);

        // Initialize UI Elements
        tvStatus = findViewById(R.id.tvVoiceStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        fabMic = findViewById(R.id.fabMicControl);
        btnClose = findViewById(R.id.btnCloseVoice);

        // 1. Initialize Native TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                setupTtsListener();
                // Start by listening for the user
                startListening();
            }
        });

        // 2. Initialize Native STT
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        // Required for barge-in: allows the recognizer to process sound while speakers are active
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);

        setupSTTListener();

        // 3. Setup Receiver to catch AI responses from MainActivity
        setupAiResponseReceiver();

        // UI Listeners
        fabMic.setOnClickListener(v -> toggleListening());
        btnClose.setOnClickListener(v -> finish());
    }

    private void setupTtsListener() {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                isAIspeaking = true;
                runOnUiThread(() -> tvStatus.setText("Puter is speaking..."));
                
                // REQUIREMENT #2: Start listening IMMEDIATELY when AI starts talking
                // This allows the user to interrupt (Barge-in) at any time.
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onDone(String utteranceId) {
                isAIspeaking = false;
                // CONTINUOUS FLOW: Re-open mic to wait for next user command
                runOnUiThread(() -> startListening());
            }

            @Override
            public void onError(String utteranceId) {
                isAIspeaking = false;
                runOnUiThread(() -> startListening());
            }
        });
    }

    private void setupSTTListener() {
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                tvStatus.setText("Listening...");
                isListening = true;
            }

            @Override
            public void onBeginningOfSpeech() {
                // BARGE-IN: If user talks while AI is speaking, kill the AI speech immediately
                if (tts != null && tts.isSpeaking()) {
                    Log.d(TAG, "Barge-in detected: Stopping TTS");
                    tts.stop();
                    isAIspeaking = false;
                }
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                isListening = false;
            }

            @Override
            public void onError(int error) {
                isListening = false;
                // Auto-restart listening on timeout errors to maintain "Always Listening"
                if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                    startListening();
                } else {
                    tvStatus.setText("Tap mic to try again");
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String userText = matches.get(0);
                    tvTranscript.setText(userText);
                    // Send to MainActivity to hit Puter.js
                    processUserQuery(userText);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    tvTranscript.setText(matches.get(0));
                    
                    // Live Barge-in: Stop TTS as soon as partial speech is recognized
                    if (tts != null && tts.isSpeaking()) {
                        tts.stop();
                        isAIspeaking = false;
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void setupAiResponseReceiver() {
        aiResponseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("PUTER_AI_RESPONSE".equals(intent.getAction())) {
                    String aiText = intent.getStringExtra("RESPONSE_TEXT");
                    if (aiText != null) {
                        // REQUIREMENT #1: AI talks the response lively
                        speakAIResponse(aiText);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter("PUTER_AI_RESPONSE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(aiResponseReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(aiResponseReceiver, filter);
        }
    }

    private void processUserQuery(String text) {
        runOnUiThread(() -> tvStatus.setText("Puter is thinking..."));
        // Send Broadcast to MainActivity
        Intent intent = new Intent("PUTER_VOICE_INPUT");
        intent.putExtra("QUERY", text);
        sendBroadcast(intent);
    }

    public void speakAIResponse(String response) {
        if (tts != null) {
            // Flush ensures modern "Barge-in" interruption logic
            tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "VOICE_AGENT_ID");
        }
    }

    /**
     * Starts listening. Removed the gate that prevented listening during speech.
     */
    private void startListening() {
        if (!isListening) {
            try {
                speechRecognizer.startListening(recognizerIntent);
            } catch (Exception e) {
                Log.e(TAG, "Error starting recognizer: " + e.getMessage());
            }
        }
    }

    private void toggleListening() {
        if (isListening) {
            speechRecognizer.stopListening();
        } else {
            if (tts.isSpeaking()) tts.stop();
            startListening();
        }
    }

    @Override
    protected void onDestroy() {
        if (aiResponseReceiver != null) unregisterReceiver(aiResponseReceiver);
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}