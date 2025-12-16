package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.speech.tts.UtteranceProgressListener;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RegionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS      = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID  = "extra_category_id";
    public static final String EXTRA_EVENT_ID     = "extra_event_id";
    public static final String EXTRA_ROOM_ID      = "extra_room_id";
    public static final String EXTRA_REGION_ID    = "extra_region_id";
    public static final String EXTRA_IDIOMA_OLD   = "extra_idioma_id"; // fallback only

    private LinearLayout buttonContainer;
    private Button backButton;     // bottom: Back
    private Button homeButton;     // bottom: Home

    private TextToSpeech tts;

    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;
    private boolean introFinished = false;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId;

    // current focused language button (center in ring)
    private View centerView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_region_detail);

        buttonContainer = findViewById(R.id.containerButtons);
        backButton = findViewById(R.id.backButton);
        homeButton = findViewById(R.id.homeButton); // ⚠️ make sure this exists in XML

        // ===== TTS init =====
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

                tts.speak("Choose language",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");
            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // ===== HOME button: style + click + ring nav =====
        if (homeButton != null) {
            styleNavButton(homeButton);

            homeButton.setOnClickListener(v -> {
                speakText("Going home");
                Intent i = new Intent(RegionDetailActivity.this, MainActivity.class);
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
                // UP/DOWN normal behaviour
                return false;
            });
        }

        // ===== BACK button: style + click + ring nav =====
        if (backButton != null) {
            styleNavButton(backButton);

            backButton.setOnClickListener(v -> {
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

        // Read extras
        codPais    = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId    = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId     = getIntent().getStringExtra(EXTRA_ROOM_ID);

        regionId   = getIntent().getStringExtra(EXTRA_REGION_ID);
        if (isEmpty(regionId)) {
            // fallback for older flow
            regionId = getIntent().getStringExtra(EXTRA_IDIOMA_OLD);
        }

        // Fallback in case layout mismatch happens
        if (buttonContainer == null) {
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setPadding(dp(16), dp(16), dp(16), dp(16));
            setContentView(fallback);
            buttonContainer = fallback;
            // backButton / homeButton will be null in this fallback layout
        }

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId) || isEmpty(regionId)) {
            buttonContainer.addView(disabled("(Missing codPais/categoryId/eventId/roomId/regionId)"));
            return;
        }

        String idiomasUrl = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idiomas";

        fetchIdiomas(idiomasUrl);
    }

    public void goBack() {
        onBackPressed();
    }

    // ===== RING NAV HELPERS =====
    // Ring: [homeButton, centerView, backButton]
    private View getRingNext(View current) {
        List<View> ring = new ArrayList<>();
        if (homeButton != null) ring.add(homeButton);
        if (centerView != null) ring.add(centerView);
        if (backButton != null) ring.add(backButton);

        if (ring.size() < 2) return null;

        int idx = ring.indexOf(current);
        if (idx == -1) {
            return ring.get(0);
        }
        int nextIdx = (idx + 1) % ring.size();
        return ring.get(nextIdx);
    }

    private View getRingPrev(View current) {
        List<View> ring = new ArrayList<>();
        if (homeButton != null) ring.add(homeButton);
        if (centerView != null) ring.add(centerView);
        if (backButton != null) ring.add(backButton);

        if (ring.size() < 2) return null;

        int idx = ring.indexOf(current);
        if (idx == -1) {
            return ring.get(0);
        }
        int prevIdx = (idx - 1 + ring.size()) % ring.size();
        return ring.get(prevIdx);
    }

    /** GET /region/{regionId}/idiomas and render buttons from "language" array */
    private void fetchIdiomas(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    if (buttonContainer == null) return;
                    buttonContainer.removeAllViews();
                    buttonContainer.addView(disabled("Request failed: " + e.getMessage()));
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        if (buttonContainer == null) return;
                        buttonContainer.removeAllViews();
                        buttonContainer.addView(disabled("HTTP " + response.code()));
                    });
                    return;
                }

                JsonObject root;
                try { root = gson.fromJson(body, JsonObject.class); }
                catch (Exception ex) { root = null; }

                JsonArray langs = (root != null && root.has("language") && root.get("language").isJsonArray())
                        ? root.getAsJsonArray("language") : new JsonArray();

                runOnUiThread(() -> {
                    if (buttonContainer == null) return;

                    buttonContainer.removeAllViews();

                    lastSpokenView = null;
                    lastSpeakMillis = 0L;
                    centerView = null;

                    if (langs.size() == 0) {
                        buttonContainer.addView(disabled("(No languages)"));
                        return;
                    }

                    for (JsonElement el : langs) {
                        if (!el.isJsonObject()) continue;
                        JsonObject lang = el.getAsJsonObject();

                        String name = safeString(lang, "name", "Language");
                        Integer id  = safeInt(lang, "id", null);

                        Button b = makeButton(name);

                        // LEFT/RIGHT ring nav for ANY language item
                        b.setOnKeyListener((v, keyCode, event) -> {
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

                            // UP/DOWN – normal scrolling between languages
                            return false;
                        });

                        b.setOnClickListener(v -> {
                            if (id == null) return;

                            speakText("Opening " + name);

                            Intent next = new Intent(RegionDetailActivity.this, AccessibilitiesActivity.class);
                            next.putExtra(AccessibilitiesActivity.EXTRA_CODPAIS, codPais);
                            next.putExtra(AccessibilitiesActivity.EXTRA_CATEGORY_ID, categoryId);
                            next.putExtra(AccessibilitiesActivity.EXTRA_EVENT_ID, eventId);
                            next.putExtra(AccessibilitiesActivity.EXTRA_ROOM_ID, roomId);
                            next.putExtra(AccessibilitiesActivity.EXTRA_REGION_ID, regionId);
                            next.putExtra(AccessibilitiesActivity.EXTRA_LANGUAGE_ID, String.valueOf(id));
                            startActivity(next);
                        });
                        buttonContainer.addView(b);
                    }

                    // DPAD-ready: focus first language initially
                    if (buttonContainer.getChildCount() > 0) {
                        View first = buttonContainer.getChildAt(0);
                        first.requestFocus();
                        centerView = first;   // first language is initial center
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

    // ---------- UI helpers: glass-style, languages ----------

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);

        // Layout: full width with modest horizontal margins so it fits small screens
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(80),   // width
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

        // Focus animation + TTS
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // this language becomes center in the ring
                centerView = v;

                v.animate().scaleX(1.015f).scaleY(1.015f).setDuration(80).start();

                if (introFinished) {
                    long now = System.currentTimeMillis();
                    if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                        lastSpokenView = v;
                        lastSpeakMillis = now;
                        if (v instanceof Button) {
                            String labelText = ((Button) v).getText().toString();
                            if (labelText != null && !labelText.trim().isEmpty()) {
                                speakText(labelText);
                            }
                        }
                    }
                }
            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
            }
        });

        return b;
    }

    private Button disabled(String text) {
        Button b = makeButton(text);
        b.setEnabled(false);
        b.setAlpha(0.6f);
        return b;
    }

    // Common style for Back / Home – speaks its own label
    private void styleNavButton(Button b) {
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

            if (!introFinished) return;

            long now = System.currentTimeMillis();
            if (v != lastSpokenView || (now - lastSpeakMillis) > 800) {
                lastSpokenView = v;
                lastSpeakMillis = now;

                if (v instanceof Button) {
                    String label = ((Button) v).getText().toString();
                    if (label != null && !label.trim().isEmpty()) {
                        // Will say "Home" or "Back"
                        speakText(label);
                    } else {
                        speakText("Selected item");
                    }
                } else {
                    speakText("Selected item");
                }
            }
        });
    }

    // Speak whichever view is currently focused inside buttonContainer.
    private void speakCurrentlyFocusedItem() {
        if (!introFinished || buttonContainer == null) return;

        buttonContainer.postDelayed(() -> {
            View focused = buttonContainer.findFocus();
            if (focused == null && buttonContainer.getChildCount() > 0) {
                View v = buttonContainer.getChildAt(0);
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

    // ---------- utils ----------
    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private static String safeString(JsonObject o, String key, String def) {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
    }

    private static Integer safeInt(JsonObject o, String key, Integer def) {
        try {
            return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : def;
        } catch (Exception e) { return def; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // speak focused language (if any)
        speakCurrentlyFocusedItem();
    }
}
