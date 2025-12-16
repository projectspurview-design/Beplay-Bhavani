package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;

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
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CategoriaActivity extends AppCompatActivity {
    public static final String EXTRA_CODPAIS = "extra_codpais";
    public static volatile boolean REPLAY_INTRO_ON_RESUME = false;

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();
    private String codPais;

    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;
    private boolean introFinished = false;

    // nav buttons
    private Button backButton;
    private Button homeButton;

    // last focused venue (center of ring)
    private View centerView = null;

    // ===== Voice command fields (Vuzix) =====
    private VuzixSpeechClient vuzixSpeechClient;
    private TextToSpeech tts;

    // Map a KEYCODE -> View (button) so onKeyDown can click the right one
    private final SparseArray<View> keyToView = new SparseArray<>();

    // Track phrases so we can remove them
    private final Set<String> registeredPhrases = new HashSet<>();
    private final Set<String> dynamicPhrases = new HashSet<>();

    // Keycode pool for dynamic phrases
    private final List<Integer> keycodePool = new ArrayList<>();
    private int keyPoolIndex = 0;

    // Voice UX: prefix to avoid collisions like "UK" ≈ "OK"
    private static final String VOICE_PREFIX = "select ";

    // Blacklist short/ambiguous words so they’re never used alone
    private static final Set<String> PHRASE_BLACKLIST = new HashSet<>(Arrays.asList(
            "ok", "okay", "o k", "k", "yes", "no", "yeah", "yep"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_categoria);

        containerButtons = findViewById(R.id.containerButtons);
        backButton       = findViewById(R.id.backButton);
        homeButton       = findViewById(R.id.homeButton);

        // ===== TTS =====
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(1.1f);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) { }

                    @Override
                    public void onDone(String utteranceId) {
                        if ("intro2".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                tts.speak("Choose Venues",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");

            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // Vuzix
        initVuzixSpeechClient();
        buildKeycodePool();
        initVuzixSpeechClient();
        registerStaticPhrases();

        // ===== Back button =====
        if (backButton != null) {
            styleNavButton(backButton, "Back");
            backButton.setOnClickListener(v -> {
                MainActivity.REPLAY_INTRO_ON_RESUME = true;
                speakText("Going back");
                onBackPressed();
            });

            backButton.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View next = getRingNext(v);
                    if (next != null) {
                        next.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View prev = getRingPrev(v);
                    if (prev != null) {
                        prev.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }

        // ===== Home button =====
        if (homeButton != null) {
            styleNavButton(homeButton, "Home");
            homeButton.setOnClickListener(v -> {
                speakText("Going home");
                Intent i = new Intent(CategoriaActivity.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
            });

            homeButton.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    View next = getRingNext(v);
                    if (next != null) {
                        next.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    View prev = getRingPrev(v);
                    if (prev != null) {
                        prev.requestFocus();
                        return true;
                    }
                }
                return false;
            });
        }

        // ===== Data fetch =====
        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        if (codPais == null || codPais.trim().isEmpty()) {
            containerButtons.addView(makeDisabledButton("Missing codPais"));
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais + "/categoria/undefined";
        fetchAndRenderCategories(url);
    }

    // ---------- NAV RING HELPERS ----------
    // Ring order: [Home, CenterVenue, Back]
    private View getRingNext(View current) {
        List<View> ring = new ArrayList<>();
        if (homeButton != null)  ring.add(homeButton);
        if (centerView != null)  ring.add(centerView);
        if (backButton != null)  ring.add(backButton);

        if (ring.size() < 2) return null;

        int idx = ring.indexOf(current);
        if (idx == -1) {
            // If current is some other child in containerButtons, default to center or home
            if (current != null && current.getParent() == containerButtons && centerView != null) {
                idx = ring.indexOf(centerView);
            } else {
                idx = 0;
            }
        }

        int nextIdx = (idx + 1) % ring.size();
        return ring.get(nextIdx);
    }

    private View getRingPrev(View current) {
        List<View> ring = new ArrayList<>();
        if (homeButton != null)  ring.add(homeButton);
        if (centerView != null)  ring.add(centerView);
        if (backButton != null)  ring.add(backButton);

        if (ring.size() < 2) return null;

        int idx = ring.indexOf(current);
        if (idx == -1) {
            if (current != null && current.getParent() == containerButtons && centerView != null) {
                idx = ring.indexOf(centerView);
            } else {
                idx = 0;
            }
        }

        int prevIdx = (idx - 1 + ring.size()) % ring.size();
        return ring.get(prevIdx);
    }

    // Back/Home shared styling + TTS on focus
    private void styleNavButton(Button b, String speakLabel) {
        b.setAllCaps(false);
        b.setTextColor(Color.BLACK);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));

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
            if (!hasFocus) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                triggerRipple(v);
            }

            long now = System.currentTimeMillis();
            if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                lastSpokenView = v;
                lastSpeakMillis = now;

                if (!introFinished) return;

                if (speakLabel != null && !speakLabel.trim().isEmpty()) {
                    speakText(speakLabel);
                } else {
                    speakText("Selected item");
                }
            }
        });
    }

    // ---------- BACK NAV ----------
    public void goBack() {
        MainActivity.REPLAY_INTRO_ON_RESUME = true;
        speakText("Going back");
        onBackPressed();
    }

    // ---------- FETCH + RENDER ----------
    private void fetchAndRenderCategories(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(makeDisabledButton("Request failed: " + e.getMessage()));
                    clearDynamicVoice();
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        containerButtons.addView(makeDisabledButton("HTTP " + response.code()));
                        clearDynamicVoice();
                    });
                    return;
                }

                CategoryItem[] items;
                try { items = gson.fromJson(body, CategoryItem[].class); }
                catch (Exception ex) { items = new CategoryItem[0]; }

                CategoryItem[] finalItems = items;
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    clearDynamicVoice();

                    if (finalItems.length == 0) {
                        containerButtons.addView(makeDisabledButton("(No categories)"));
                        return;
                    }

                    for (CategoryItem cat : finalItems) {
                        String label = (cat != null && cat.nome != null && !cat.nome.trim().isEmpty())
                                ? cat.nome : "(sem nome)";

                        Button btn = makeButton(label);

                        // LEFT/RIGHT ring navigation for each venue
                        btn.setOnKeyListener((v, keyCode, event) -> {
                            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                View next = getRingNext(v);
                                if (next != null) {
                                    next.requestFocus();
                                    return true;
                                }
                                return false;
                            }

                            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                                View prev = getRingPrev(v);
                                if (prev != null) {
                                    prev.requestFocus();
                                    return true;
                                }
                                return false;
                            }

                            // UP/DOWN → normal list navigation
                            return false;
                        });

                        btn.setOnClickListener(v -> {
                            String categoryId = (cat != null && cat.id != null) ? cat.id.trim() : "";
                            if (categoryId.isEmpty()) return;

                            speakText("Opening " + label);

                            Intent i = new Intent(CategoriaActivity.this, EventsActivity.class);
                            i.putExtra(EventsActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(EventsActivity.EXTRA_CATEGORY_ID, categoryId);
                            startActivity(i);
                        });

                        containerButtons.addView(btn);

                        // ===== VOICE: dynamic phrase for this visible button =====
                        if (keyPoolIndex < keycodePool.size()) {
                            int keycode = keycodePool.get(keyPoolIndex++);

                            String phrasePrimary = buildVoicePhrase(label);
                            registerPhrase(phrasePrimary, keycode);
                            dynamicPhrases.add(phrasePrimary);

                            String normalizedLabel = normalizeLower(label);
                            String phraseSecondary = buildVoicePhrase(normalizedLabel);
                            if (!phraseSecondary.equalsIgnoreCase(phrasePrimary)) {
                                registerPhrase(phraseSecondary, keycode);
                                dynamicPhrases.add(phraseSecondary);
                            }

                            keyToView.put(keycode, btn);
                        }
                    }

                    // focus first venue initially
                    if (containerButtons.getChildCount() > 0) {
                        View first = containerButtons.getChildAt(0);
                        first.requestFocus();
                        centerView = first;
                        lastSpokenView = first;
                        lastSpeakMillis = System.currentTimeMillis();
                    }
                });
            }
        });
    }

    private void speakText(String text) {
        if (tts != null) {
            if (tts.isSpeaking()) {
                tts.stop();
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // ===== Voice helpers =====
    private void buildKeycodePool() {
        // A-Z
        for (int c = KeyEvent.KEYCODE_A; c <= KeyEvent.KEYCODE_Z; c++) keycodePool.add(c);
        // 0-9
        for (int c = KeyEvent.KEYCODE_0; c <= KeyEvent.KEYCODE_9; c++) keycodePool.add(c);
        // Optional Fn keys
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
        try { vuzixSpeechClient = new VuzixSpeechClient(this); }
        catch (Exception e) { vuzixSpeechClient = null; }
    }

    private void registerStaticPhrases() {
        registerPhrase("back", KeyEvent.KEYCODE_BACK);
        registerPhrase("Back", KeyEvent.KEYCODE_BACK);
        registerPhrase("home", KeyEvent.KEYCODE_MOVE_HOME);
        registerPhrase("Home", KeyEvent.KEYCODE_MOVE_HOME);
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

        lastSpokenView = null;
        lastSpeakMillis = 0L;
        centerView = null;
    }

    private void registerPhrase(String phrase, int keycode) {
        if (vuzixSpeechClient == null || phrase == null || phrase.trim().isEmpty()) return;
        try {
            vuzixSpeechClient.insertKeycodePhrase(phrase, keycode);
            registeredPhrases.add(phrase);
        } catch (Exception ignored) {}
    }

    private static String normalizeLower(String in) {
        if (in == null) return "";
        String norm = Normalizer.normalize(in, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return norm.toLowerCase(Locale.US).trim();
    }

    private String buildVoicePhrase(String rawLabel) {
        String normalized = normalizeLower(rawLabel);
        if (PHRASE_BLACKLIST.contains(normalized)) {
            return VOICE_PREFIX + normalized;
        }
        return VOICE_PREFIX + rawLabel;
    }

    // ===== UI helpers (unchanged look & feel) =====
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

    private Button makeDisabledButton(String text) {
        Button b = makeButton(text);
        b.setEnabled(false);
        b.setAlpha(0.6f);
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

    // Speak whichever view is currently focused inside containerButtons.
    private void speakCurrentlyFocusedItem() {
        containerButtons.postDelayed(() -> {
            View focused = containerButtons.findFocus();
            if (focused == null && containerButtons.getChildCount() > 0) {
                View v = containerButtons.getChildAt(0);
                v.requestFocus();
                focused = v;
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
                    speakText("Selected item");
                }
            }
        }, 220);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    // ===== Voice key handling =====
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Static: "back"
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            MainActivity.REPLAY_INTRO_ON_RESUME = true;
            speakText("Going back");
            onBackPressed();
            return true;
        }

        // Static: "home"
        if (keyCode == KeyEvent.KEYCODE_MOVE_HOME) {
            speakText("Going home");
            Intent i = new Intent(CategoriaActivity.this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
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

        if (REPLAY_INTRO_ON_RESUME) {
            REPLAY_INTRO_ON_RESUME = false;
            introFinished = false;

            if (tts != null) {
                if (tts.isSpeaking()) tts.stop();

                tts.speak("Choose the Venue",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");
            } else {
                introFinished = true;
                speakCurrentlyFocusedItem();
            }
            return;
        }

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
            if (registeredPhrases.contains("home")) {
                try { vuzixSpeechClient.deletePhrase("home"); } catch (Exception ignored) {}
            }
            if (registeredPhrases.contains("Home")) {
                try { vuzixSpeechClient.deletePhrase("Home"); } catch (Exception ignored) {}
            }
        }
        super.onDestroy();
    }
}
