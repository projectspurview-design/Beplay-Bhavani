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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient;

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

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends BaseTtsActivity {

    private static final String URL = "https://console.beplay.io/api/idiomas";

    private LinearLayout containerButtons;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .dns(new CustomDns())
            .build();

    private final Gson gson = new GsonBuilder().create();

    // ===== Voice command fields (Vuzix) =====
    private VuzixSpeechClient vuzixSpeechClient;

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

        // === Init shared TTS (same style as RegionsActivity) ===
        initTts("Choose language");

        buildKeycodePool();
        initVuzixSpeechClient();
        registerStaticPhrases();  // "back"

        fetchIdiomasAndBuildUI();
    }

    // When intro TTS finishes, speak whichever item is focused
    @Override
    protected void onTtsIntroFinished() {
        speakCurrentlyFocusedItem();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After coming back, announce focused item again
        speakCurrentlyFocusedItem();
    }

    // ===== Speak the currently focused language button =====
    private void speakCurrentlyFocusedItem() {
        containerButtons.postDelayed(() -> {
            View focused = containerButtons.findFocus();
            if (focused == null && containerButtons.getChildCount() > 0) {
                View v = containerButtons.getChildAt(0);
                v.requestFocus();
                focused = v;
            }

            if (focused != null) {
                speakViewLabel(focused, "Selected item");
            }
        }, 220);
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

    // Static phrase: "back"
    private void registerStaticPhrases() {
        registerPhrase("back", KeyEvent.KEYCODE_BACK);
        registerPhrase("Back", KeyEvent.KEYCODE_BACK); // case variation
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

    // ===== FETCH + UI BUILD (original logic + TTS calls) =====
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
                    speakText("Failed to load languages. Please try again.");
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
                        speakText("Unable to load languages. Server error.");
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
                        speakText("No languages available.");
                        return;
                    }

                    for (Language lang : finalLangs) {
                        String label = (lang != null && lang.nome != null && !lang.nome.trim().isEmpty())
                                ? lang.nome
                                : "(sem nome)";

                        Button btn = makeButton(label);
                        btn.setOnClickListener(v -> {
                            String codPais = (lang != null && lang.codPais != null) ? lang.codPais.trim() : "";
                            if (codPais.isEmpty()) {
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
                    }
                });
            }
        });
    }

    // Creates a button and keeps its existing background (your light gray + shadow + borders).
    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        ((LinearLayout.LayoutParams) b.getLayoutParams()).bottomMargin = dp(8);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Drawable original = b.getBackground();
            if (original != null) {
                ColorStateList rippleColor = ColorStateList.valueOf(Color.parseColor("#33000000"));
                RippleDrawable ripple = new RippleDrawable(rippleColor, original, null);
                b.setBackground(ripple);
            }
            b.setElevation(dp(4));
        }

        b.setFocusable(true);
        b.setFocusableInTouchMode(true);

        // Focus: ripple + TTS label (same behavior as RegionsActivity)
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }
                // Speak the button label when highlighted
                speakViewLabel(v, "Selected item");
            }
        });

        // DPAD navigation (unchanged)
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

        // Dynamic: button phrases
        View v = keyToView.get(keyCode);
        if (v != null) {
            v.performClick();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // Qr code
   /* @Override
    protected void onStart() {
        super.onStart();
        SessionManager s = SessionManager.get(this);
        if (!s.isQRAuthenticated() || s.isQRExpired()) {
            s.clearQRAuthentication();
            startActivity(new Intent(this, QRScannerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }
    }*/

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
        super.onDestroy(); // important: also shuts down TTS via BaseTtsActivity
    }
}
