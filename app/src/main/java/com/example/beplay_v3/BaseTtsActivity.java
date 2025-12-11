package com.example.beplay_v3;

import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public abstract class BaseTtsActivity extends AppCompatActivity {

    protected TextToSpeech tts;
    protected boolean introFinished = false;

    protected View lastSpokenView = null;
    protected long lastSpeakMillis = 0L;

    private static final String INTRO_UTTERANCE_ID = "tts_intro_msg";

    /**
     * Call this in onCreate() from child activities.
     * @param introText Text to speak once when the screen opens. Pass null for no intro.
     */
    protected void initTts(@Nullable String introText) {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                tts.setPitch(0.9f);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) { }

                    @Override
                    public void onDone(String utteranceId) {
                        if (INTRO_UTTERANCE_ID.equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> onTtsIntroFinished());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                if (introText != null && !introText.trim().isEmpty()) {
                    speakWithId(introText, INTRO_UTTERANCE_ID);
                } else {
                    introFinished = true;
                    onTtsIntroFinished();
                }
            } else {
                introFinished = true;
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Called when the intro phrase is finished.
     * Child activities can override this (e.g., to speak the focused item).
     */
    protected void onTtsIntroFinished() {
        // Default: do nothing
    }

    protected void speakWithId(String text, String id) {
        if (tts == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    protected void speakText(String text) {
        if (tts == null || text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;

        if (tts.isSpeaking()) {
            tts.stop();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "GENERIC_TTS");
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
        }
    }

    /**
     * Generic helper: speak label from any TextView/Button when it gets focus.
     * Uses lastSpokenView + lastSpeakMillis to avoid repeating too often.
     */
    protected void speakViewLabel(View v, @Nullable String fallbackLabel) {
        if (!introFinished || v == null) return;
        if (tts == null) return;

        long now = System.currentTimeMillis();
        if (v == lastSpokenView && (now - lastSpeakMillis) <= 800) {
            return; // avoid spam
        }

        lastSpokenView = v;
        lastSpeakMillis = now;

        String label = null;
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                label = cs.toString().trim();
            }
        }

        if (label == null || label.isEmpty()) {
            label = (fallbackLabel != null) ? fallbackLabel.trim() : null;
        }

        if (label != null && !label.isEmpty()) {
            speakText(label);
        } else {
            speakText("Selected item");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
