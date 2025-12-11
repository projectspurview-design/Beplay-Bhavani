package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

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

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();
    private String codPais;

    // ===== Voice command fields (Vuzix) =====
    private VuzixSpeechClient vuzixSpeechClient;

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

    // ===== TTS fields (same pattern as RegionsActivity) =====
    private TextToSpeech tts;
    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;
    private boolean introFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_categoria);

        containerButtons = findViewById(R.id.containerButtons);

        // Back button click
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());

            // Make it focusable and speak label when focused (like RegionsActivity nav buttons)
            backButton.setFocusable(true);
            backButton.setFocusableInTouchMode(true);
            backButton.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;
                if (!introFinished) return;

                long now = System.currentTimeMillis();
                if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                    lastSpokenView = v;
                    lastSpeakMillis = now;

                    if (v instanceof Button) {
                        String label = ((Button) v).getText().toString();
                        if (label != null && !label.trim().isEmpty()) {
                            speakText(label);
                        } else {
                            speakText("Back");
                        }
                    } else {
                        speakText("Back");
                    }
                }
            });
        }

        // ===== TTS init (same style as RegionsActivity) =====
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
                        if ("intro_categoria".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                // Intro prompt for this screen
                tts.speak("Choose category",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intro_categoria");
            } else {
                Toast.makeText(this, "Text to speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // Voice: init + static phrase ("back")
        buildKeycodePool();
        initVuzixSpeechClient();
        registerStaticPhrases();

        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        if (codPais == null || codPais.trim().isEmpty()) {
            containerButtons.addView(makeDisabledButton("Missing codPais"));
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais + "/categoria/undefined";
        fetchAndRenderCategories(url);
    }

    // Handle back button click
    public void goBack() { onBackPressed(); }

    private void fetchAndRenderCategories(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(makeDisabledButton("Request failed: " + e.getMessage()));
                    clearDynamicVoice(); // avoid stale phrases
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
                    clearDynamicVoice(); // reset voice for fresh list

                    if (finalItems.length == 0) {
                        containerButtons.addView(makeDisabledButton("(No categories)"));
                        return;
                    }
                    for (CategoryItem cat : finalItems) {
                        String label = (cat != null && cat.nome != null && !cat.nome.trim().isEmpty())
                                ? cat.nome : "(sem nome)";

                        Button btn = makeButton(label);
                        btn.setOnClickListener(v -> {
                            String categoryId = (cat != null && cat.id != null) ? cat.id.trim() : "";
                            if (categoryId.isEmpty()) return;

                            Intent i = new Intent(CategoriaActivity.this, EventsActivity.class);
                            i.putExtra(EventsActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(EventsActivity.EXTRA_CATEGORY_ID, categoryId);
                            startActivity(i);
                        });
                        containerButtons.addView(btn);

                        // ===== VOICE: dynamic phrase for this visible button =====
                        if (keyPoolIndex < keycodePool.size()) {
                            int keycode = keycodePool.get(keyPoolIndex++);

                            // Primary phrase with original casing
                            String phrasePrimary = buildVoicePhrase(label);
                            registerPhrase(phrasePrimary, keycode);
                            dynamicPhrases.add(phrasePrimary);

                            // Also register normalized lowercase variant
                            String normalizedLabel = normalizeLower(label);
                            String phraseSecondary = buildVoicePhrase(normalizedLabel);
                            if (!phraseSecondary.equalsIgnoreCase(phrasePrimary)) {
                                registerPhrase(phraseSecondary, keycode);
                                dynamicPhrases.add(phraseSecondary);
                            }

                            keyToView.put(keycode, btn);
                        }
                    }

                    // Optional: focus the first button so DPAD navigation + TTS works immediately
                    if (containerButtons.getChildCount() > 0) {
                        containerButtons.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }

    // ===== TTS helpers (same logic as RegionsActivity) =====
    private void speakText(String text) {
        if (tts != null && text != null && !text.trim().isEmpty()) {
            if (tts.isSpeaking()) {
                tts.stop();
            }
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    // Speak whichever view is currently focused inside containerButtons
    private void speakCurrentlyFocusedItem() {
        if (!introFinished) return;
        if (containerButtons == null) return;

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

    @Override
    protected void onResume() {
        super.onResume();
        // When returning to this screen, announce the currently focused item
        speakCurrentlyFocusedItem();
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

    // Always prefix, and never allow plain ambiguous words
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

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Drawable original = b.getBackground(); // keep your light gray + shadow/borders
            if (original instanceof RippleDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ((RippleDrawable) original).setColor(ColorStateList.valueOf(Color.parseColor("#33000000")));
                b.setBackground(original);
            } else if (original != null) {
                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(Color.parseColor("#33000000")), // light gray
                        original,
                        null
                );
                b.setBackground(ripple);
            }
            b.setElevation(dp(4));
        }

        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                triggerRipple(v);
            }

            if (!introFinished) return;

            long now = System.currentTimeMillis();
            if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                lastSpokenView = v;
                lastSpeakMillis = now;

                if (v instanceof Button) {
                    String label = ((Button) v).getText().toString();
                    if (label != null && !label.trim().isEmpty()) {
                        speakText(label);
                    } else {
                        speakText("Selected item");
                    }
                } else {
                    speakText("Selected item");
                }
            }
        });

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
        b.setAlpha(0.6f); // subtle visual cue for disabled state
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

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    // ===== Voice key handling =====
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Static: "back"
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
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
    protected void onDestroy() {
        // TTS cleanup
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        // Vuzix speech cleanup
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
        super.onDestroy();
    }
}
