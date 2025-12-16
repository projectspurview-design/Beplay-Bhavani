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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
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
    private Button backButton;
    private Button homeButton;     // ⭐ new Home button

    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;
    private TextToSpeech tts;

    // current focused main button (center in ring)
    private View centerView = null;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId, languageId, accessibilityId, professionalId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_professional);

        containerButtons = findViewById(R.id.containerButtons);
        backButton = findViewById(R.id.backButton);
        homeButton = findViewById(R.id.homeButton); // ⚠️ ensure this exists in XML

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                tts.setPitch(0.9f);
            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // ===== HOME button: style + click + LEFT/RIGHT ring nav =====
        if (homeButton != null) {
            styleNavButton(homeButton);

            homeButton.setOnClickListener(v -> {
                speakText("Going home");
                Intent i = new Intent(ProfessionalActivity.this, MainActivity.class);
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
                // UP/DOWN: let system / vertical nav handle
                return false;
            });
        }

        // ===== BACK button: style + click + LEFT/RIGHT ring nav + UP → last =====
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
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // legacy behavior: UP from Back → last item in container
                    if (containerButtons != null) {
                        int last = containerButtons.getChildCount() - 1;
                        if (last >= 0) {
                            View lastView = containerButtons.getChildAt(last);
                            if (lastView != null) {
                                lastView.requestFocus();
                                return true;
                            }
                        }
                    }
                }
                return false;
            });
        }

        // Read extras
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

    // ===== RING NAV HELPERS =====
    // Ring order: [Home] → [centerView] → [Back] → [Home] → …
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

    private void fetch(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    if (containerButtons == null) return;
                    containerButtons.removeAllViews();
                    containerButtons.addView(disabled("Request failed: " + e.getMessage()));
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                JsonObject rootTmp = null;
                if (response.isSuccessful()) {
                    try { rootTmp = gson.fromJson(body, JsonObject.class); } catch (Exception ignored) {}
                }
                final JsonObject finalRoot = rootTmp;

                runOnUiThread(() -> {
                    if (containerButtons == null) return;
                    containerButtons.removeAllViews();
                    lastSpokenView = null;
                    lastSpeakMillis = 0L;
                    centerView = null;

                    if (!response.isSuccessful()) {
                        containerButtons.addView(disabled("HTTP " + response.code()));
                        return;
                    }

                    if (finalRoot != null && finalRoot.has("channel") && finalRoot.get("channel").isJsonObject()) {
                        JsonObject ch = finalRoot.getAsJsonObject("channel");

                        String appId   = safeString(ch, "id_app", null);
                        String token   = safeString(ch, "token", null);
                        String channel = safeString(ch, "channel_name",
                                safeString(ch, "id_channel", null));

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

                            // LEFT/RIGHT ring navigation from join button
                            joinBtn.setOnKeyListener((v, keyCode, event) -> {
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
                                // UP/DOWN: default behavior
                                return false;
                            });

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

                                speakText("Join channel");

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

                            // Focus main button initially and mark as center of ring
                            View first = containerButtons.getChildAt(0);
                            if (first != null) {
                                first.requestFocus();
                                centerView = first;
                                lastSpokenView = first;
                                lastSpeakMillis = System.currentTimeMillis();
                            }
                        } else {
                            containerButtons.addView(disabled("(Missing channel credentials)"));
                        }
                    } else {
                        containerButtons.addView(disabled("(No channel info)"));
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

    // ----- Glass pill UI: same as other screens -----
    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);

        // Layout: full width with modest horizontal margins so it fits small screens
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(160),   // width
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

            } else {
                v.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
            }
        });

        // Focus → mark as center + speak label
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // main button becomes center in ring
                centerView = v;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }

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
        });

        return b;
    }

    private Button disabled(String text) {
        Button b = makeButton(text);
        b.setEnabled(false);
        b.setAlpha(0.6f);
        return b;
    }

    // Style Home/Back nav buttons – speak their text on focus
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
            // Speak "Home" or "Back" using same debounce
            speakLabelForView(v, false);
        });
    }

    /**
     * Speak label for view (button text or contentDescription) with debounce.
     */
    private void speakLabelForView(View v, boolean force) {
        if (v == null || tts == null) return;

        String label = null;
        if (v instanceof Button) {
            CharSequence t = ((Button) v).getText();
            if (t != null) label = t.toString();
        }
        if ((label == null || label.trim().isEmpty()) && v.getContentDescription() != null) {
            label = v.getContentDescription().toString();
        }
        if (label == null) label = "";

        long now = System.currentTimeMillis();
        if (force || v != lastSpokenView || (now - lastSpeakMillis) > 800) {
            lastSpokenView = v;
            lastSpeakMillis = now;
            if (!label.trim().isEmpty()) {
                speakText(label);
            }
        }
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

    private static String safeString(JsonObject o, String k, String d) {
        return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : d;
    }

    private static Integer safeInt(JsonObject o, String k, Integer d) {
        try {
            return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : d;
        } catch (Exception e) {
            return d;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        View focused = getCurrentFocus();

        if (focused == null && containerButtons != null && containerButtons.getChildCount() > 0) {
            focused = containerButtons.getChildAt(0);
            focused.requestFocus();
        }

        if (focused == null && homeButton != null) {
            focused = homeButton;
            homeButton.requestFocus();
        }

        if (focused == null && backButton != null) {
            focused = backButton;
            backButton.requestFocus();
        }

        if (focused != null) {
            speakLabelForView(focused, true);
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

    private static String normalizeLower(String in) {
        if (in == null) return "";
        String norm = Normalizer.normalize(in, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return norm.toLowerCase().trim();
    }
}
