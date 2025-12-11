package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AccessibilityDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS          = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID      = "extra_category_id";
    public static final String EXTRA_EVENT_ID         = "extra_event_id";
    public static final String EXTRA_ROOM_ID          = "extra_room_id";
    public static final String EXTRA_REGION_ID        = "extra_region_id";
    public static final String EXTRA_LANGUAGE_ID      = "extra_language_id";
    public static final String EXTRA_ACCESSIBILITY_ID = "extra_accessibility_id";

    private LinearLayout buttonContainer;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId, languageId, accessibilityId;

    // ===== TTS fields (same style as RegionsActivity) =====
    private TextToSpeech tts;
    private View lastSpokenView = null;
    private long lastSpeakMillis = 0L;
    private boolean introFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_detail);

        buttonContainer = findViewById(R.id.containerButtons);

        // ----- Back button behavior like other screens + TTS on focus -----
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> onBackPressed());

            backButton.setFocusable(true);
            backButton.setFocusableInTouchMode(true);

            backButton.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }
                // Speak "Back" when highlighted
                speakViewLabel(v, "Back");
            });
        }

        // ----- Init TTS (intro: "Choose professional") -----
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
                        if ("intro_professional".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                tts.speak("Choose professional",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intro_professional");
            } else {
                Toast.makeText(this, "Text to Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // ----- Read extras (unchanged) -----
        codPais         = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId      = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId         = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId          = getIntent().getStringExtra(EXTRA_ROOM_ID);
        regionId        = getIntent().getStringExtra(EXTRA_REGION_ID);
        languageId      = getIntent().getStringExtra(EXTRA_LANGUAGE_ID);
        accessibilityId = getIntent().getStringExtra(EXTRA_ACCESSIBILITY_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId)
                || isEmpty(regionId) || isEmpty(languageId) || isEmpty(accessibilityId)) {
            if (buttonContainer != null) {
                buttonContainer.addView(disabled("(Missing one or more required extras)"));
            }
            speakText("Required information is missing. Cannot load professional.");
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idioma/" + languageId
                + "/accessibility/" + accessibilityId;

        fetch(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Announce focused item when coming back
        speakCurrentlyFocusedItem();
    }

    // Speak whichever view is currently focused
    private void speakCurrentlyFocusedItem() {
        if (!introFinished || buttonContainer == null) return;

        buttonContainer.postDelayed(() -> {
            View focused = buttonContainer.findFocus();
            if (focused == null && buttonContainer.getChildCount() > 0) {
                View first = buttonContainer.getChildAt(0);
                first.requestFocus();
                focused = first;
            }

            if (focused != null) {
                speakViewLabel(focused, "Selected item");
            }
        }, 220);
    }

    // ---------- ORIGINAL LOGIC KEPT: fetch + read "professional" and show button ----------
    private void fetch(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    if (buttonContainer == null) return;
                    buttonContainer.removeAllViews();
                    buttonContainer.addView(disabled("Request failed: " + e.getMessage()));
                    speakText("Failed to load professional details. Please try again.");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                JsonObject rootTmp;
                try {
                    rootTmp = gson.fromJson(body, JsonObject.class);
                } catch (Exception ex) {
                    rootTmp = null;
                }

                final JsonObject finalRoot = rootTmp;

                runOnUiThread(() -> {
                    if (buttonContainer == null) return;
                    buttonContainer.removeAllViews();

                    if (finalRoot != null && finalRoot.has("professional") && finalRoot.get("professional").isJsonObject()) {
                        JsonObject prof = finalRoot.getAsJsonObject("professional");
                        String profName = safeString(prof, "name", "Professional");
                        Integer profId = safeInt(prof, "id", null);

                        if (profId != null) {
                            Button btn = makeButton(profName);
                            btn.setOnClickListener(v -> {
                                // Speak on selection
                                speakText("Opening " + profName);

                                Intent next = new Intent(AccessibilityDetailActivity.this, ProfessionalActivity.class);
                                next.putExtra(ProfessionalActivity.EXTRA_CODPAIS, codPais);
                                next.putExtra(ProfessionalActivity.EXTRA_CATEGORY_ID, categoryId);
                                next.putExtra(ProfessionalActivity.EXTRA_EVENT_ID, eventId);
                                next.putExtra(ProfessionalActivity.EXTRA_ROOM_ID, roomId);
                                next.putExtra(ProfessionalActivity.EXTRA_REGION_ID, regionId);
                                next.putExtra(ProfessionalActivity.EXTRA_LANGUAGE_ID, languageId);
                                next.putExtra(ProfessionalActivity.EXTRA_ACCESSIBILITY_ID, accessibilityId);
                                next.putExtra(ProfessionalActivity.EXTRA_PROFESSIONAL_ID, String.valueOf(profId));
                                startActivity(next);
                            });
                            buttonContainer.addView(btn);

                            // DPAD focus first button
                            buttonContainer.getChildAt(0).requestFocus();
                        } else {
                            buttonContainer.addView(disabled("(No professional id)"));
                            speakText("Professional information is incomplete.");
                        }
                    } else {
                        buttonContainer.addView(disabled("(No professional)"));
                        speakText("No professional is available for this option.");
                    }
                });
            }

        });
    }
    // ---------- END ORIGINAL LOGIC ----------

    // ---------- TTS helpers ----------
    private void speakText(String text) {
        if (tts == null || text == null) return;
        if (tts.isSpeaking()) {
            tts.stop();
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void speakViewLabel(View v, String fallback) {
        if (!introFinished || v == null) return;

        long now = System.currentTimeMillis();
        if (v == lastSpokenView && (now - lastSpeakMillis) < 800) {
            return; // avoid spamming same view too fast
        }
        lastSpokenView = v;
        lastSpeakMillis = now;

        CharSequence labelCs = null;
        if (v instanceof Button) {
            labelCs = ((Button) v).getText();
        }
        String label = (labelCs != null) ? labelCs.toString() : null;
        String toSpeak = (label != null && !label.trim().isEmpty()) ? label : fallback;

        if (toSpeak != null && !toSpeak.trim().isEmpty()) {
            speakText(toSpeak);
        }
    }

    // ---------- UI helpers: darker ripple, elevation, DPAD focus/keys, focus ripple + TTS ----------
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
            Drawable original = b.getBackground();
            int rippleColor = Color.parseColor("#80000000"); // 50% opacity black (darker)

            if (original instanceof RippleDrawable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ((RippleDrawable) original).setColor(ColorStateList.valueOf(rippleColor));
                b.setBackground(original);
            } else if (original != null) {
                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(rippleColor),
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
                // TTS when professional button is highlighted
                speakViewLabel(v, "Selected item");
            }
        });

        b.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

            int idx = buttonContainer.indexOfChild(v);
            int count = buttonContainer.getChildCount();
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
                View next = buttonContainer.getChildAt(target);
                if (next != null) next.requestFocus();
                return true;
            }
            return false;
        });

        return b;
    }

    private Button disabled(String text) {
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

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safeString(JsonObject o, String k, String d){
        return (o!=null&&o.has(k)&&!o.get(k).isJsonNull())?o.get(k).getAsString():d;
    }
    private static Integer safeInt(JsonObject o, String k, Integer d){
        try{
            return (o!=null&&o.has(k)&&!o.get(k).isJsonNull())?o.get(k).getAsInt():d;
        }catch(Exception e){
            return d;
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        super.onDestroy();
    }
}
