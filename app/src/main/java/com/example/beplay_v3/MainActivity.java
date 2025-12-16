package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Typeface;
import android.util.TypedValue;

import android.speech.tts.UtteranceProgressListener;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient;
import android.view.Gravity;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import android.speech.tts.TextToSpeech;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final String URL = "https://console.beplay.io/api/idiomas";

    private LinearLayout containerButtons;

    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;

    public static volatile boolean REPLAY_INTRO_ON_RESUME = false;




    private final OkHttpClient client = new OkHttpClient.Builder()
            .dns(new CustomDns())
            .build();

    private final Gson gson = new GsonBuilder().create();

    // ===== Voice command fields (Vuzix) =====
    private VuzixSpeechClient vuzixSpeechClient;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    private boolean introFinished = false;  // <-- add this



    // Map a KEYCODE -> View (button)
    private final SparseArray<View> keyToView = new SparseArray<>();

    // Track phrases for cleanup
    private final Set<String> registeredPhrases = new HashSet<>();
    private final Set<String> dynamicPhrases = new HashSet<>();

    // Keycode pool for dynamic phrases
    private final List<Integer> keycodePool = new ArrayList<>();
    private int keyPoolIndex = 0;


    // Voice UX: use a prefix to avoid collisions like "UK" ~ "OK"
    private static final String VOICE_PREFIX = "select ";

    // Blacklist ambiguous words so they’re never used alone
    private static final Set<String> PHRASE_BLACKLIST = new HashSet<>(Arrays.asList(
            "ok", "okay", "o k", "k", "yes", "no", "yeah", "yep"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        containerButtons = findViewById(R.id.containerButtons);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(1.1f);

                // Listen for when the intro messages finish
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        // no-op
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        // When second intro finishes, allow button speech
                        if ("intro2".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> {
                                // after intros, speak currently focused country
                                speakCurrentlyFocusedItem();
                            });
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        // If something goes wrong, just allow button speech anyway
                        introFinished = true;
                    }
                });

                // 🔊 Queue the two intro messages
              /*  tts.speak("Welcome to BePlay",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intro1");*/

                tts.speak("Choose the country of your choice",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");

            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
                introFinished = true; // fail-safe: allow button speech
            }
        });

        initVuzixSpeechClient();

        buildKeycodePool();
        registerStaticPhrases();  // "back"

        fetchIdiomasAndBuildUI();
    }

    // Build a pool of distinct keycodes to assign to dynamic phrases.
    private void buildKeycodePool() {
        for (int c = KeyEvent.KEYCODE_A; c <= KeyEvent.KEYCODE_Z; c++) keycodePool.add(c);
        for (int c = KeyEvent.KEYCODE_0; c <= KeyEvent.KEYCODE_9; c++) keycodePool.add(c);
        keycodePool.add(KeyEvent.KEYCODE_F1);
        keycodePool.add(KeyEvent.KEYCODE_F2);
        keycodePool.add(KeyEvent.KEYCODE_F3);
        keycodePool.add(KeyEvent.KEYCODE_F4);
        keycodePool.add(KeyEvent.KEYCODE_F5);
        keycodePool.add(KeyEvent.KEYCODE_F6);
        keycodePool.add(KeyEvent.KEYCODE_F7);
        keycodePool.add(KeyEvent.KEYCODE_F8);
        keycodePool.add(KeyEvent.KEYCODE_F9);
        keycodePool.add(KeyEvent.KEYCODE_F10);
        keycodePool.add(KeyEvent.KEYCODE_F11);
        keycodePool.add(KeyEvent.KEYCODE_F12);
    }

    private void initVuzixSpeechClient() {
        try {
            vuzixSpeechClient = new VuzixSpeechClient(this);
        } catch (Exception e) {
            Toast.makeText(this, "Vuzix speech init failed", Toast.LENGTH_SHORT).show();
            vuzixSpeechClient = null;
        }
    }
    private void speakText(String text) {
        if (tts != null) {
            if (tts.isSpeaking()) {
                tts.stop();
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }



    // Static phrase: "back"
    private void registerStaticPhrases() {
        registerPhrase("back", KeyEvent.KEYCODE_BACK);
        registerPhrase("Back", KeyEvent.KEYCODE_BACK);
        registerPhrase("home", KeyEvent.KEYCODE_HOME);  // Add this
        registerPhrase("Home", KeyEvent.KEYCODE_HOME);  // Add this
    }

    private void clearDynamicVoice() {
        if (vuzixSpeechClient == null) return;
        for (String phrase : dynamicPhrases) {
            try { vuzixSpeechClient.deletePhrase(phrase); } catch (Exception ignored) {}
            registeredPhrases.remove(phrase);
        }
        dynamicPhrases.clear();
        keyToView.clear();
        keyPoolIndex = 0;

        // reset focus-speak state
        lastSpokenView = null;
        lastSpeakMillis = 0L;
    }


    private void registerPhrase(String phrase, int keycode) {
        if (vuzixSpeechClient == null || phrase == null || phrase.trim().isEmpty()) return;
        try {
            vuzixSpeechClient.insertKeycodePhrase(phrase, keycode);
            registeredPhrases.add(phrase);
        } catch (Exception ignored) {}
    }

    // Normalize to a simpler, case/diacritics-insensitive form
    private static String normalizeLower(String in) {
        if (in == null) return "";
        String norm = Normalizer.normalize(in, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return norm.toLowerCase(Locale.US).trim();
    }

    // Build a safe voice phrase:
    // - always prefix with "select "
    // - never register plain ambiguous words (blacklist)
    private String buildVoicePhrase(String rawLabel) {
        String normalized = normalizeLower(rawLabel);
        if (PHRASE_BLACKLIST.contains(normalized)) {
            // Still allow commanding that item, but force the prefix so "ok" alone won't trigger
            return VOICE_PREFIX + normalized;
        }
        // Always prefix to avoid "UK" ~ "OK" collisions
        return VOICE_PREFIX + rawLabel;
    }

    // ===== Your existing logic below (unchanged except for voice hookup) =====
    private void fetchIdiomasAndBuildUI() {
        Request request = new Request.Builder().url(URL).get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    Button error = makeButton("Request failed: " + e.getMessage());
                    error.setEnabled(false);
                    containerButtons.addView(error);
                    clearDynamicVoice();
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = (response.body() != null) ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        Button error = makeButton("HTTP " + response.code());
                        error.setEnabled(false);
                        containerButtons.addView(error);
                        clearDynamicVoice();
                    });
                    return;
                }

                Language[] langs;
                try { langs = gson.fromJson(body, Language[].class); }
                catch (Exception parseErr) { langs = new Language[0]; }

                final Language[] finalLangs = (langs != null ? langs : new Language[0]);

                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    clearDynamicVoice();

                    if (finalLangs.length == 0) {
                        Button empty = makeButton("(No items)");
                        empty.setEnabled(false);
                        containerButtons.addView(empty);
                        return;
                    }

                    for (Language lang : finalLangs) {
                        String label = (lang != null && lang.nome != null && !lang.nome.trim().isEmpty())
                                ? lang.nome
                                : "(sem nome)";

                        Button btn = makeButton(label);
                        btn.setOnClickListener(v -> {
                            speakText("HELLO");
                            String codPais = (lang != null && lang.codPais != null) ? lang.codPais.trim() : "";
                            if (codPais.isEmpty()) {
                                speakText("Country code not available");

                                Toast.makeText(MainActivity.this, "codPais not available", Toast.LENGTH_SHORT).show();
                                return;
                            }

                            speakText("Opening " + label);

                            Intent intent = new Intent(MainActivity.this, CategoriaActivity.class);
                            intent.putExtra(CategoriaActivity.EXTRA_CODPAIS, codPais);
                            startActivity(intent);
                        });
                        containerButtons.addView(btn);

                        // === VOICE: register with a safe, prefixed phrase ===
                        if (keyPoolIndex < keycodePool.size()) {
                            int keycode = keycodePool.get(keyPoolIndex++);

                            // Primary phrase with original casing
                            String phrasePrimary = buildVoicePhrase(label);
                            registerPhrase(phrasePrimary, keycode);
                            dynamicPhrases.add(phrasePrimary);

                            // Also register normalized lowercase variant (for recognition robustness)
                            String normalizedLabel = normalizeLower(label);
                            String phraseSecondary = buildVoicePhrase(normalizedLabel);
                            if (!phraseSecondary.equalsIgnoreCase(phrasePrimary)) {
                                registerPhrase(phraseSecondary, keycode);
                                dynamicPhrases.add(phraseSecondary);
                            }

                            keyToView.put(keycode, btn);
                        }
                    }

                    if (containerButtons.getChildCount() > 0) {
                        containerButtons.getChildAt(0).requestFocus();
                        // treat the first focus as "already spoken" so onResume won't re-announce it immediately
                        lastSpokenView = containerButtons.getChildAt(0);
                        lastSpeakMillis = System.currentTimeMillis();

                    }
                });
            }
        });
    }

    // Creates a button and keeps its existing background (your light gray + shadow + borders).
    // Creates a colorful pill-style button for each language.
    // Creates a smaller, compact pill-style button for each language.
    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);

        // Layout: full width with modest horizontal margins so it fits small screens
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(65),   // width
                dp(35)                                    // height in dp → rectangle shape
        );

        // Use small, device-friendly horizontal margins instead of huge fixed ones
        params.setMarginStart(34);
        params.setMarginEnd(33);
        params.setMargins(0,0,6,6);

        params.gravity = Gravity.CENTER_HORIZONTAL;
        b.setLayoutParams(params);

        // Typography: slightly larger and readable on small screens
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);  // smaller text
        b.setTypeface(b.getTypeface(), Typeface.BOLD); // optional: remove bold for cleaner look
        b.setTextColor(Color.BLACK);

        b.setBackgroundResource(R.drawable.language_button_bg_compact); // New compact drawable


        // Padding + minHeight for touchability and consistent height on small displays


        // Prevents wide text from expanding too much

        // Background / ripple: keep your drawable but wrap with ripple on Lollipop+
        b.setBackgroundResource(R.drawable.language_button_bg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Drawable original = b.getBackground();
            ColorStateList rippleColor = ColorStateList.valueOf(Color.parseColor("#40FFFFFF"));
            RippleDrawable ripple = new RippleDrawable(rippleColor, original, null);
            b.setBackground(ripple);
            b.setElevation(dp(2));
        }

        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(100).start();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }

                // ❗ Only speak button label AFTER the two intro messages are done
                if (introFinished) {
                    long now = System.currentTimeMillis();
                    if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                        lastSpokenView = v;
                        lastSpeakMillis = now;
                        if (v instanceof Button) {
                            String label = ((Button) v).getText().toString();
                            if (label != null && !label.trim().isEmpty()) {
                                speakText(label);
                            }
                        }
                    }
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
        });


        // DPAD nav: unchanged from yours
        b.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

            int idx = containerButtons.indexOfChild(v);
            int count = containerButtons.getChildCount();
            if (idx < 0) return false;

            int target = -1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    target = Math.min(idx + 1, count - 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_UP:
                    target = Math.max(idx - 1, 0);
                    break;
                default:
                    return false;
            }
            if (target != idx) {
                View next = containerButtons.getChildAt(target);
                if (next != null) next.requestFocus();
                return true;
            }
            return false;
        });

        return b;
    }
    private void triggerRipple(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            float cx = v.getWidth() / 2f;
            float cy = v.getHeight() / 2f;
            v.drawableHotspotChanged(cx, cy);
            v.setPressed(true);
            v.postDelayed(() -> v.setPressed(false), 200);
        } else {
            v.animate().alpha(0.9f).setDuration(100)
                    .withEndAction(() -> v.animate().alpha(1f).setDuration(120));
        }
    }
    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    // Call to speak whichever view is currently focused inside containerButtons.
// If nothing is focused, focus the first child then speak.
// Uses the existing lastSpokenView/lastSpeakMillis debounce so it won't repeat.
    private void speakCurrentlyFocusedItem() {
        // run after a short delay so focus has settled after activity resume/transition
        containerButtons.postDelayed(() -> {
            View focused = containerButtons.findFocus(); // may be null
            if (focused == null) {
                // nothing focused — try to focus the first actionable child
                if (containerButtons.getChildCount() > 0) {
                    View v = containerButtons.getChildAt(0);
                    v.requestFocus();
                    focused = v;
                }
            }

            if (focused != null) {
                long now = System.currentTimeMillis();
                if (focused != lastSpokenView || (now - lastSpeakMillis) > 800) {
                    lastSpokenView = focused;
                    lastSpeakMillis = now;

                    if (focused instanceof Button) {
                        String label = ((Button) focused).getText().toString();
                        if (label != null && !label.trim().isEmpty()) {
                            speakText(label);
                            return;
                        }
                    }

                    // fallback: speak a generic description if not a button or empty text
                    speakText("Selected item");
                }
            }
        }, 220); // 220ms gives the system time to restore focus; adjust if necessary
    }

    // Custom DNS retained
    private static class CustomDns implements Dns {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            try {
                return Arrays.asList(InetAddress.getAllByName(hostname));
            } catch (UnknownHostException e) {
                throw new UnknownHostException("Failed to resolve " + hostname + " using custom DNS");
            }
        }
    }

    // ===== Handle the key events coming from Vuzix speech =====
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Static: "back"
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }

        // Static: "home" - return to MainActivity
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            // Clear back stack and return to MainActivity
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            return true;
        }

        // Dynamic: button phrases
        View v = keyToView.get(keyCode);
        if (v != null) {
            v.performClick();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If another activity requested the intro replay, re-queue the two intro messages.
        if (REPLAY_INTRO_ON_RESUME) {
            REPLAY_INTRO_ON_RESUME = false;

            // Ensure we will only speak button labels after intros finish
            introFinished = false;

            if (tts != null) {
                // If currently speaking, stop and reset queue so we start fresh
                if (tts.isSpeaking()) tts.stop();

                // Re-schedule the two intro messages; the existing UtteranceProgressListener
                // will set introFinished=true when "intro2" completes and will call speakCurrentlyFocusedItem()
//                tts.speak("Welcome to BePlay",
//                        TextToSpeech.QUEUE_FLUSH,
//                        null,
//                        "intro1");

                tts.speak("Choose the country of your choice",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");
            } else {
                // If TTS not ready, fallback to immediately allowing button speech
                introFinished = true;
                speakCurrentlyFocusedItem();
            }

            // return early — the intro listener will call speakCurrentlyFocusedItem() after intro2
            return;
        }

        // default behavior: announce the highlighted item
        speakCurrentlyFocusedItem();
    }



    @Override
    protected void onDestroy() {
        if (vuzixSpeechClient != null) {
            for (String phrase : dynamicPhrases) {
                try { vuzixSpeechClient.deletePhrase(phrase); } catch (Exception ignored) {}
            }
            dynamicPhrases.clear();

            if (registeredPhrases.contains("back")) {
                try { vuzixSpeechClient.deletePhrase("back"); } catch (Exception ignored) {}
            }
            if (registeredPhrases.contains("Back")) {
                try { vuzixSpeechClient.deletePhrase("Back"); } catch (Exception ignored) {}
            }
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}

