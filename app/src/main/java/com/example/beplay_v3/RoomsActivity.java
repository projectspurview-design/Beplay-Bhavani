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

public class RoomsActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";
    public static final String EXTRA_EVENT_ID = "extra_event_id";

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rooms);

        containerButtons = findViewById(R.id.containerButtons);

        // Back button behavior (same as Categoria/Events)
        Button backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> goBack());
        }

        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId)) {
            containerButtons.addView(disabled("(missing codPais/categoryId/eventId)"));
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/"
                + codPais + "/categoria/" + categoryId + "/event/" + eventId + "/rooms";

        fetchAndRender(url);
    }

    // Same back behavior as other screens
    public void goBack() {
        onBackPressed();
    }

    // --------- ORIGINAL LOGIC KEPT AS-IS ----------
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

                RoomsEnvelope env;
                try { env = gson.fromJson(body, RoomsEnvelope.class); }
                catch (Exception ex) { env = null; }

                RoomItem[] rooms = (env != null && env.rooms != null) ? env.rooms : new RoomItem[0];

                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    if (rooms.length == 0) {
                        containerButtons.addView(disabled("(No rooms)"));
                        return;
                    }
                    for (RoomItem room : rooms) {
                        String label = (room != null && room.name != null && !room.name.trim().isEmpty())
                                ? room.name
                                : ("Room " + (room != null && room.id != null ? room.id : "?"));

                        Button btn = makeButton(label);
                        btn.setOnClickListener(v -> {
                            String roomIdStr = (room != null && room.id != null) ? String.valueOf(room.id) : null;
                            if (isEmpty(roomIdStr)) return;

                            Intent i = new Intent(RoomsActivity.this, RegionsActivity.class);
                            i.putExtra(RegionsActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(RegionsActivity.EXTRA_CATEGORY_ID, categoryId);
                            i.putExtra(RegionsActivity.EXTRA_EVENT_ID, eventId);
                            i.putExtra(RegionsActivity.EXTRA_ROOM_ID, roomIdStr);
                            startActivity(i);
                        });

                        containerButtons.addView(btn);
                    }

                    // optional UX: focus first item for DPAD navigation
                    if (containerButtons.getChildCount() > 0) {
                        containerButtons.getChildAt(0).requestFocus();
                    }
                });
            }
        });
    }
    // --------- END ORIGINAL LOGIC ----------

    // --------- UI helpers (ripple/elevation + DPAD focus/keys + focus ripple) ----------
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
            // darker ripple (50% opacity black). Adjust if you want even darker.
            int rippleColor = Color.parseColor("#80000000");

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

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
