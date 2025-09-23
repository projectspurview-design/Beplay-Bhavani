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

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RegionsActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS     = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";
    public static final String EXTRA_EVENT_ID    = "extra_event_id";
    public static final String EXTRA_ROOM_ID     = "extra_room_id";

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_regions);

        containerButtons = findViewById(R.id.containerButtons);

        // Back button behavior (same as other screens)
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());
        }

        codPais     = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId  = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId     = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId      = getIntent().getStringExtra(EXTRA_ROOM_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId)) {
            containerButtons.addView(disabled("(Missing codPais/categoryId/eventId/roomId)"));
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/regions";

        fetchAndRender(url);
    }

    // same back behavior
    public void goBack() {
        onBackPressed();
    }

    // -------- ORIGINAL LOGIC KEPT AS-IS --------
    private void fetchAndRender(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(disabled("Request failed: " + e.getMessage()));
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        containerButtons.addView(disabled("HTTP " + response.code()));
                    });
                    return;
                }

                RegionsEnvelope env;
                try { env = gson.fromJson(body, RegionsEnvelope.class); }
                catch (Exception ex) { env = null; }

                IdiomaItem[] idiomas = (env != null && env.idioma != null) ? env.idioma : new IdiomaItem[0];

                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    if (idiomas.length == 0) {
                        containerButtons.addView(disabled("(No idiomas)"));
                        return;
                    }
                    for (IdiomaItem it : idiomas) {
                        String label = (it != null && it.nome != null && !it.nome.trim().isEmpty())
                                ? it.nome
                                : ("Idioma " + (it != null && it.id != null ? it.id : "?"));

                        Button btn = makeButton(label);
                        btn.setOnClickListener(v -> {
                            String idiomaId = (it != null && it.id != null) ? String.valueOf(it.id) : null;
                            if (isEmpty(idiomaId)) return;

                            Intent i = new Intent(RegionsActivity.this, RegionDetailActivity.class);
                            i.putExtra(RegionDetailActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(RegionDetailActivity.EXTRA_CATEGORY_ID, categoryId);
                            i.putExtra(RegionDetailActivity.EXTRA_EVENT_ID, eventId);
                            i.putExtra(RegionDetailActivity.EXTRA_ROOM_ID, roomId);
                            i.putExtra(RegionDetailActivity.EXTRA_REGION_ID, idiomaId);
                            startActivity(i);
                        });
                        containerButtons.addView(btn);
                    }

                    // optional: focus first item for DPAD navigation
                    if (containerButtons.getChildCount() > 0) {
                        containerButtons.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }
    // -------- END ORIGINAL LOGIC --------

    // -------- UI helpers (darker ripple, elevation, DPAD focus/keys, focus ripple) --------
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
            int rippleColor = Color.parseColor("#80000000"); // 50% opacity black (darker ripple)

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

    private int dp(int v) { float d = getResources().getDisplayMetrics().density; return Math.round(v * d); }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
