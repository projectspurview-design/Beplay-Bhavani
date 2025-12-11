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

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RoomsActivity extends BaseTtsActivity {

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

        // Initialize TTS (same pattern as RegionsActivity)
        initTts("Choose room");

        // Back button behavior (same nav, TTS on focus)
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
                // Speak "Back" when highlighted
                speakViewLabel(v, "Back");
            });
        }

        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId)) {
            containerButtons.addView(disabled("(missing codPais/categoryId/eventId)"));
            speakText("Required information is missing. Cannot load rooms.");
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/"
                + codPais + "/categoria/" + categoryId + "/event/" + eventId + "/rooms";

        fetchAndRender(url);
    }

    @Override
    protected void onTtsIntroFinished() {
        // After "Choose room", speak whichever room is focused
        speakCurrentlyFocusedItem();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After coming back, announce focused room again
        speakCurrentlyFocusedItem();
    }

    private void speakCurrentlyFocusedItem() {
        if (containerButtons == null) return;

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

    // Same back behavior as other screens
    public void goBack() {
        onBackPressed();
    }

    // --------- ORIGINAL LOGIC KEPT AS-IS (with TTS added in callbacks) ----------
    private void fetchAndRender(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(disabled("Request failed: " + e.getMessage()));
                    speakText("Failed to load rooms. Please try again.");
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        containerButtons.addView(disabled("HTTP " + response.code()));
                        speakText("Unable to load rooms. Server error.");
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
                        speakText("No rooms available.");
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

                            // TTS when selecting a room
                            speakText("Opening " + label);

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

    // --------- UI helpers (ripple/elevation + DPAD focus/keys + focus ripple + TTS) ----------
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
            // darker ripple (50% opacity black)
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
            if (hasFocus) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    triggerRipple(v);
                }
                // TTS when a room button is highlighted
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

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
