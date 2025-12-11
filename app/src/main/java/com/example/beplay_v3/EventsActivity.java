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

// NOTE: Now extends BaseTtsActivity to reuse shared TTS logic
public class EventsActivity extends BaseTtsActivity {

    public static final String EXTRA_CODPAIS     = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais;
    private String categoryId;

    // ===== Voice command fields (Vuzix) =====
    private VuzixSpeechClient vuzixSpeechClient;

    // Map KEYCODE -> Button view
    private final SparseArray<View> keyToView = new SparseArray<>();

    // Track phrases so we can remove them later
    private final Set<String> registeredPhrases = new HashSet<>();
    private final Set<String> dynamicPhrases = new HashSet<>();

    // Keycode pool for dynamic phrases
    private final List<Integer> keycodePool = new ArrayList<>();
    private int keyPoolIndex = 0;

    // Voice UX: always prefix to avoid collisions like "UK" ≈ "OK"
    private static final String VOICE_PREFIX = "select ";

    // Blacklist short/ambiguous words (never register them bare)
    private static final Set<String> PHRASE_BLACKLIST = new HashSet<>(Arrays.asList(
            "ok", "okay", "o k", "k", "yes", "no", "yeah", "yep"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_events);

        containerButtons = findViewById(R.id.containerButtons);

        // Initialize shared TTS (same style as RegionsActivity)
        initTts("Choose event");

        // Back button
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());

            backButton.setFocusable(true);
            backButton.setFocusableInTouchMode(true);

            backButton.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }
                // TTS: speak "Back" or its label when highlighted
                speakViewLabel(v, "Back");
            });
        }

        // Voice init + static phrase ("back")
        buildKeycodePool();
        initVuzixSpeechClient();
        registerStaticPhrases();

        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);

        if (codPais == null || codPais.trim().isEmpty()
                || categoryId == null || categoryId.trim().isEmpty()) {
            containerButtons.addView(makeDisabledButton("Missing codPais or categoryId"));
            speakText("Required information is missing. Cannot load events.");
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais + "/categoria/" + categoryId + "/events";
        fetchAndRenderEvents(url);
    }

    // same back behavior
    public void goBack() { onBackPressed(); }

    // When intro TTS finishes, speak currently focused item
    @Override
    protected void onTtsIntroFinished() {
        speakCurrentlyFocusedItem();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After returning to this screen, announce focused item again
        speakCurrentlyFocusedItem();
    }

    // Speak currently focused button inside containerButtons
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

    // ---------- ORIGINAL LOGIC KEPT AS-IS (plus voice + TTS where noted) ----------
    private void fetchAndRenderEvents(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(makeDisabledButton("Request failed: " + e.getMessage()));
                    clearDynamicVoice(); // avoid stale phrases
                    speakText("Failed to load events. Please try again.");
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        containerButtons.addView(makeDisabledButton("HTTP " + response.code()));
                        clearDynamicVoice();
                        speakText("Unable to load events. Server error.");
                    });
                    return;
                }

                EventItem[] events;
                try { events = gson.fromJson(body, EventItem[].class); }
                catch (Exception ex) { events = new EventItem[0]; }

                EventItem[] finalEvents = events;

                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    clearDynamicVoice(); // reset voice for fresh list

                    if (finalEvents.length == 0) {
                        containerButtons.addView(makeDisabledButton("(No events)"));
                        speakText("No events available.");
                        return;
                    }

                    for (EventItem ev : finalEvents) {
                        String label = (ev != null && ev.eventName != null && !ev.eventName.trim().isEmpty())
                                ? ev.eventName : "(sem nome)";

                        Button btn = makeButton(label);

                        btn.setOnClickListener(v -> {
                            String eventId = (ev != null && ev.id != null) ? String.valueOf(ev.id) : null;
                            if (eventId == null || eventId.trim().isEmpty()) return;

                            // TTS: speak on click
                            speakText("Opening " + label);

                            Intent i = new Intent(EventsActivity.this, RoomsActivity.class);
                            i.putExtra(RoomsActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(RoomsActivity.EXTRA_CATEGORY_ID, categoryId);
                            i.putExtra(RoomsActivity.EXTRA_EVENT_ID, eventId);
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

                    // focus first button for DPAD nav
                    if (containerButtons.getChildCount() > 0) {
                        containerButtons.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }
    // ---------- END ORIGINAL LOGIC ----------

    // ===== Voice helpers =====
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

    // --- UI helpers copied to match CategoriaActivity look/feel ---
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
            Drawable original = b.getBackground(); // keep theme bg
            if (original instanceof RippleDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ((RippleDrawable) original).setColor(ColorStateList.valueOf(Color.parseColor("#80000000"))); // darker ripple
                b.setBackground(original);
            } else if (original != null) {
                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(Color.parseColor("#E6000000")), // darker ripple
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
            if (hasFocus) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }
                // TTS when event button is highlighted
                speakViewLabel(v, "Selected item");
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

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    // ===== Handle the key events coming from Vuzix speech =====
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Static: "back"
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }

        // Dynamic: event phrases
        View v = keyToView.get(keyCode);
        if (v != null) {
            v.performClick();
            return true;
        }

        return super.onKeyDown(keyCode, event);
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
        super.onDestroy(); // BaseTtsActivity will handle shutting down TTS
    }
}
