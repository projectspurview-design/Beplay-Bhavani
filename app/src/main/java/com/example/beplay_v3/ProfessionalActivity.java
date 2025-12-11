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
import java.text.Normalizer;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProfessionalActivity extends AppCompatActivity {

    // Incoming navigation extras
    public static final String EXTRA_CODPAIS          = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID      = "extra_category_id";
    public static final String EXTRA_EVENT_ID         = "extra_event_id";
    public static final String EXTRA_ROOM_ID          = "extra_room_id";
    public static final String EXTRA_REGION_ID        = "extra_region_id";
    public static final String EXTRA_LANGUAGE_ID      = "extra_language_id";
    public static final String EXTRA_ACCESSIBILITY_ID = "extra_accessibility_id";
    public static final String EXTRA_PROFESSIONAL_ID  = "extra_professional_id";

    // Outgoing extras to AgoraChannelActivity
    public static final String EXTRA_AGORA_APP_ID   = "extra_agora_app_id";
    public static final String EXTRA_AGORA_TOKEN    = "extra_agora_token";
    public static final String EXTRA_AGORA_CHANNEL  = "extra_agora_channel";
    public static final String EXTRA_AGORA_IS_VIDEO = "extra_agora_is_video";

    private LinearLayout containerButtons;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId, languageId, accessibilityId, professionalId;

    // ===== TTS fields (same pattern as RegionsActivity) =====
    private TextToSpeech tts;
    private View lastSpokenView = null;
    private long lastSpeakMillis = 0L;
    private boolean introFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_professional); // see XML

        containerButtons = findViewById(R.id.professionalHeader);

        // Back button like other screens (+ TTS on focus, silent on click)
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

        // ----- Init TTS (intro: "Join channel") -----
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
                        if ("intro_professional_channel".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                tts.speak("Join channel",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intro_professional_channel");
            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // Read extras (original logic)
        codPais         = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId      = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId         = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId          = getIntent().getStringExtra(EXTRA_ROOM_ID);
        regionId        = getIntent().getStringExtra(EXTRA_REGION_ID);
        languageId      = getIntent().getStringExtra(EXTRA_LANGUAGE_ID);
        accessibilityId = getIntent().getStringExtra(EXTRA_ACCESSIBILITY_ID);
        professionalId  = getIntent().getStringExtra(EXTRA_PROFESSIONAL_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId)
                || isEmpty(regionId) || isEmpty(languageId) || isEmpty(accessibilityId) || isEmpty(professionalId)) {
            if (containerButtons != null) containerButtons.addView(disabled("(Missing one or more required extras)"));
            speakText("Required information is missing. Cannot join channel.");
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idioma/" + languageId
                + "/accessibility/" + accessibilityId
                + "/professional/" + professionalId;

        fetch(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        speakCurrentlyFocusedItem();
    }

    // Speak currently focused view inside containerButtons (same pattern)
    private void speakCurrentlyFocusedItem() {
        if (!introFinished || containerButtons == null) return;

        containerButtons.postDelayed(() -> {
            View focused = containerButtons.findFocus();
            if (focused == null && containerButtons.getChildCount() > 0) {
                View first = containerButtons.getChildAt(0);
                first.requestFocus();
                focused = first;
            }
            if (focused != null) {
                speakViewLabel(focused, "Selected item");
            }
        }, 220);
    }

    private void fetch(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    if (containerButtons == null) return;
                    containerButtons.removeAllViews();
                    containerButtons.addView(disabled("Request failed: " + e.getMessage()));
                    speakText("Failed to load channel information. Please try again.");
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                JsonObject rootTmp = null;
                if (response.isSuccessful()) {
                    try { rootTmp = gson.fromJson(body, JsonObject.class); } catch (Exception ignored) {}
                }
                final JsonObject finalRoot = rootTmp;
                final boolean okResponse = response.isSuccessful();
                final int httpCode = response.code();

                runOnUiThread(() -> {
                    if (containerButtons == null) return;
                    containerButtons.removeAllViews();

                    if (!okResponse) {
                        containerButtons.addView(disabled("HTTP " + httpCode));
                        speakText("Unable to load channel information. Server error.");
                        return;
                    }

                    if (finalRoot != null && finalRoot.has("channel") && finalRoot.get("channel").isJsonObject()) {
                        JsonObject ch = finalRoot.getAsJsonObject("channel");

                        String appId   = safeString(ch, "id_app", null);
                        String token   = safeString(ch, "token", null);
                        String channel = safeString(ch, "channel_name", safeString(ch, "id_channel", null));

                        boolean isVideo = false;
                        if (ch.has("accessibility") && ch.get("accessibility").isJsonObject()) {
                            JsonObject acc = ch.getAsJsonObject("accessibility");
                            Integer typeId = safeInt(acc, "type_channel_id", null);
                            // 1 = video, 2 = audio
                            isVideo = (typeId != null && typeId == 1);
                        }

                        if (!isEmpty(appId) && !isEmpty(token) && !isEmpty(channel)) {
                            final String appIdF    = appId;
                            final String tokenF    = token;
                            final String channelF  = channel;
                            final boolean isVideoF = isVideo;

                            Button joinBtn = makeButton(isVideoF ? "Join Channel (Video)" : "Join Channel (Audio)");
                            joinBtn.setOnClickListener(v -> {
                                // Detect if this accessibility implies captions
                                boolean hasCaptions = false;
                                if (ch.has("accessibility") && ch.get("accessibility").isJsonObject()) {
                                    JsonObject acc = ch.getAsJsonObject("accessibility");
                                    String accName = normalizeLower(safeString(acc, "name", ""));
                                    hasCaptions = accName.contains("caption")
                                            || accName.contains("closed caption")
                                            || accName.contains("cc")
                                            || accName.contains("subtitle")
                                            || accName.contains("legend")
                                            || accName.contains("legenda");
                                }

                                // TTS on click
                                speakText("Joining channel");

                                Intent go = new Intent(ProfessionalActivity.this, AgoraChannelActivity.class);
                                go.putExtra(EXTRA_AGORA_APP_ID, appIdF);
                                go.putExtra(EXTRA_AGORA_TOKEN, tokenF);
                                go.putExtra(EXTRA_AGORA_CHANNEL, channelF);
                                go.putExtra(EXTRA_AGORA_IS_VIDEO, isVideoF);
                                // If your AgoraChannelActivity defines this extra:
                                go.putExtra(AgoraChannelActivity.EXTRA_AGORA_HAS_CC, hasCaptions);
                                startActivity(go);
                            });

                            containerButtons.addView(joinBtn);
                            containerButtons.getChildAt(0).requestFocus();
                        } else {
                            containerButtons.addView(disabled("(Missing channel credentials)"));
                            speakText("Channel credentials are missing. Cannot join.");
                        }
                    } else {
                        containerButtons.addView(disabled("(No channel info)"));
                        speakText("No channel information is available.");
                    }
                });
            }
        });
    }

    // ===== TTS helpers =====
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
            return; // avoid repeating too fast on same view
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

    // ----- UI helpers: darker ripple, elevation, DPAD focus/keys, focus ripple + TTS -----
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
            int rippleColor = Color.parseColor("#80000000"); // darker ripple (50%)

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
                // TTS when the join button is highlighted
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

    // ----- utils -----
    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private static String  safeString(JsonObject o, String k, String d) {
        return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : d;
    }

    private static Integer safeInt(JsonObject o, String k, Integer d) {
        try {
            return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : d;
        } catch (Exception e) {
            return d;
        }
    }

    private static String normalizeLower(String in) {
        if (in == null) return "";
        String norm = Normalizer.normalize(in, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return norm.toLowerCase().trim();
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
