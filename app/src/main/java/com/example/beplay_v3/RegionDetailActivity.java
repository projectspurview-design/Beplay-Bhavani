package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

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

public class RegionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS      = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID  = "extra_category_id";
    public static final String EXTRA_EVENT_ID     = "extra_event_id";
    public static final String EXTRA_ROOM_ID      = "extra_room_id";
    public static final String EXTRA_REGION_ID    = "extra_region_id";
    public static final String EXTRA_IDIOMA_OLD   = "extra_idioma_id"; // fallback only

    private LinearLayout buttonContainer;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_region_detail);

        // Get references
        buttonContainer = findViewById(R.id.containerButtons);

        // Back button behavior (same as other screens)
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) backButton.setOnClickListener(v -> goBack());

        // Read extras
        codPais    = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId    = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId     = getIntent().getStringExtra(EXTRA_ROOM_ID);

        regionId   = getIntent().getStringExtra(EXTRA_REGION_ID);
        if (isEmpty(regionId)) {
            regionId = getIntent().getStringExtra(EXTRA_IDIOMA_OLD);
        }

        if (buttonContainer == null) {
            // Avoid crash even if layout id mismatched
            LinearLayout fallback = new LinearLayout(this);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setPadding(dp(16), dp(16), dp(16), dp(16));
            setContentView(fallback);
            buttonContainer = fallback;
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
                        b.setOnClickListener(v -> {
                            if (id == null) return;
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

                    // DPAD-ready
                    if (buttonContainer.getChildCount() > 0) {
                        buttonContainer.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }

    // ---------- UI helpers: darker ripple, elevation, DPAD focus/keys, focus ripple ----------
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
            if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                triggerRipple(v);
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

    // ---------- utils ----------
    private int dp(int v) { float d = getResources().getDisplayMetrics().density; return Math.round(v * d); }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private static String safeString(JsonObject o, String key, String def) {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : def;
    }
    private static Integer safeInt(JsonObject o, String key, Integer def) {
        try {
            return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : def;
        } catch (Exception e) { return def; }
    }
}
