package com.example.beplay_v3;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// Now extends BaseTtsActivity to reuse shared TTS logic (same as RegionsActivity)
public class AccessibilitiesActivity extends BaseTtsActivity {

    public static final String EXTRA_CODPAIS       = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID   = "extra_category_id";
    public static final String EXTRA_EVENT_ID      = "extra_event_id";
    public static final String EXTRA_ROOM_ID       = "extra_room_id";
    public static final String EXTRA_REGION_ID     = "extra_region_id";
    public static final String EXTRA_LANGUAGE_ID   = "extra_language_id";

    private LinearLayout buttonContainer;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId, languageId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibilities);

        buttonContainer = findViewById(R.id.containerButtons);

        // Init TTS intro (same style as RegionsActivity)
        initTts("Choose accessibility");

        // Back button (same behavior as other screens, plus TTS on focus)
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
                // TTS: speak "Back" when highlighted
                speakViewLabel(v, "Back");
            });
        }

        codPais     = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId  = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId     = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId      = getIntent().getStringExtra(EXTRA_ROOM_ID);
        regionId    = getIntent().getStringExtra(EXTRA_REGION_ID);
        languageId  = getIntent().getStringExtra(EXTRA_LANGUAGE_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId)
                || isEmpty(roomId) || isEmpty(regionId) || isEmpty(languageId)) {
            if (buttonContainer != null) {
                buttonContainer.addView(disabled("(Missing codPais/categoryId/eventId/roomId/regionId/languageId)"));
            }
            speakText("Required information is missing. Cannot load accessibilities.");
            return;
        }

        String listUrl = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idioma/" + languageId
                + "/accessibilities";

        fetchAccessibilities(listUrl);
    }

    // When intro TTS finishes, speak whichever accessibility is focused
    @Override
    protected void onTtsIntroFinished() {
        speakCurrentlyFocusedItem();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // When coming back to this screen, read focused item again
        speakCurrentlyFocusedItem();
    }

    private void speakCurrentlyFocusedItem() {
        if (buttonContainer == null) return;

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

    // -------- ORIGINAL LOGIC KEPT AS-IS (with TTS for errors/info/click) --------
    private void fetchAccessibilities(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    if (buttonContainer == null) return;
                    buttonContainer.removeAllViews();
                    buttonContainer.addView(disabled("Request failed: " + e.getMessage()));
                    speakText("Failed to load accessibilities. Please try again.");
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        if (buttonContainer == null) return;
                        buttonContainer.removeAllViews();
                        buttonContainer.addView(disabled("HTTP " + response.code()));
                        speakText("Unable to load accessibilities. Server error.");
                    });
                    return;
                }

                JsonObject root;
                try { root = gson.fromJson(body, JsonObject.class); }
                catch (Exception ex) { root = null; }

                JsonArray accs = (root != null && root.has("accessibility") && root.get("accessibility").isJsonArray())
                        ? root.getAsJsonArray("accessibility") : new JsonArray();

                runOnUiThread(() -> {
                    if (buttonContainer == null) return;

                    buttonContainer.removeAllViews();
                    if (accs.size() == 0) {
                        buttonContainer.addView(disabled("(No accessibilities)"));
                        speakText("No accessibilities available.");
                        return;
                    }

                    for (JsonElement el : accs) {
                        if (!el.isJsonObject()) continue;
                        JsonObject a = el.getAsJsonObject();
                        String name = safeString(a, "name", "Accessibility");
                        Integer id  = safeInt(a, "id", null);

                        Button b = makeButton(name);
                        b.setOnClickListener(v -> {
                            if (id == null) return;

                            // Speak when user selects an accessibility
                            speakText("Opening " + name);

                            Intent next = new Intent(AccessibilitiesActivity.this, AccessibilityDetailActivity.class);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_CODPAIS, codPais);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_CATEGORY_ID, categoryId);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_EVENT_ID, eventId);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_ROOM_ID, roomId);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_REGION_ID, regionId);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_LANGUAGE_ID, languageId);
                            next.putExtra(AccessibilityDetailActivity.EXTRA_ACCESSIBILITY_ID, String.valueOf(id));
                            startActivity(next);
                        });
                        buttonContainer.addView(b);
                    }

                    // DPAD ready
                    if (buttonContainer.getChildCount() > 0) {
                        buttonContainer.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }
    // -------- END ORIGINAL LOGIC --------

    // -------- UI helpers: darker ripple, elevation, DPAD focus/keys, focus ripple + TTS --------
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
            int rippleColor = Color.parseColor("#80000000"); // 50% opacity black

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
                // TTS when an accessibility button is highlighted
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
        return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : d;
    }
    private static Integer safeInt(JsonObject o, String k, Integer d){
        try{
            return (o!=null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : d;
        }catch(Exception e){
            return d;
        }
    }
}
