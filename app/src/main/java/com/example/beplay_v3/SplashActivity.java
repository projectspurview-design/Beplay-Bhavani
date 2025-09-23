package com.example.beplay_v3;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 3500; // 3.5s to allow TTS + fade

    private TextToSpeech tts;
    private SessionManager session;

    private Handler handler;
    private final Runnable routeRunnable = this::decideNext;

    private boolean routed = false; // guard against double navigation

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        // Edge-to-edge insets padding (expects root view with id "main" in activity_splash.xml)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        // UI refs
        ImageView logoImageView = findViewById(R.id.splash_logo);
        TextView  welcomeText   = findViewById(R.id.splash_text);

        // Simple fade-ins
        if (logoImageView != null) {
            logoImageView.setAlpha(0f);
            logoImageView.animate().alpha(1f).setDuration(1000).start();
        }
        if (welcomeText != null) {
            welcomeText.setAlpha(0f);
            welcomeText.animate().alpha(1f).setDuration(1000).setStartDelay(500).start();
        }

        // TTS welcome
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(1.0f);
                tts.speak("Welcome to BePlay", TextToSpeech.QUEUE_FLUSH, null, "beplay_welcome");
            }
        });

        // Session manager
        session = SessionManager.get(this);

        // Route after the splash delay
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(routeRunnable, SPLASH_DURATION);
    }

    /** Decide where to go next after splash. */
    private void decideNext() {
        if (routed) return;
        routed = true;

        // If no QR auth or it has expired -> go scan (camera UI)
        if (!session.isQRAuthenticated() || session.isQRExpired()) {
            session.clearQRAuthentication();
            startActivity(new Intent(this, BarcodeScannerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } else {
            // Valid session -> enter the app
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // Stop TTS cleanly
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        // Cancel pending navigation if activity is being destroyed early
        if (handler != null) {
            handler.removeCallbacks(routeRunnable);
        }
        super.onDestroy();
    }
}
