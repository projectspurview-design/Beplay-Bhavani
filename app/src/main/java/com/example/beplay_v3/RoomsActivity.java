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

public class RoomsActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS      = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID  = "extra_category_id";
    public static final String EXTRA_EVENT_ID     = "extra_event_id";

    private LinearLayout containerButtons;
    private Button backButton;     // bottom: Back
    private Button homeButton;     // bottom: Home

    private TextToSpeech tts;

    private View lastSpokenView = null;
    private long lastSpeakMillis = 0;

    private boolean introFinished = false;

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId;

    // current focused room button (center in ring)
    private View centerView = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rooms);

        containerButtons = findViewById(R.id.containerButtons);
        backButton = findViewById(R.id.backButton);
        homeButton = findViewById(R.id.homeButton);

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

                tts.speak("Choose rooms",
                        TextToSpeech.QUEUE_ADD,
                        null,
                        "intro2");

            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
            }
        });

        // ===== HOME button: style + click + ring nav =====
        if (homeButton != null) {
            styleBackButton(homeButton);

            homeButton.setOnClickListener(v -> {
                speakText("Going home");
                Intent i = new Intent(RoomsActivity.this, MainActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
            });

            homeButton.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // RIGHT → next in ring
                    View next = getRingNext(v);
                    if (next != null) {
                        next.requestFocus();
                        return true;
                    }
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    // LEFT → previous in ring
                    View prev = getRingPrev(v);
                    if (prev != null) {
                        prev.requestFocus();
                        return true;
                    }
                }
                // UP/DOWN → normal system navigation
                return false;
            });
        }

        // ===== BACK button: style + click + ring nav =====
        if (backButton != null) {
            styleBackButton(backButton);

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

        // ===== Intent extras =====
        codPais    = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId    = getIntent().getStringExtra(EXTRA_EVENT_ID);

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

    // ===== RING NAV HELPERS =====
    // Ring order: [homeButton, centerView, backButton]
    // RIGHT → next, LEFT → previous
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

    // Speak whichever view is currently focused inside containerButtons.
    private void speakCurrentlyFocusedItem() {
        // Don't speak during intro
        if (!introFinished) return;

        containerButtons.postDelayed(() -> {
            View focused = containerButtons.findFocus();
            if (focused == null && containerButtons.getChildCount() > 0) {
                View v = containerButtons.getChildAt(0);
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

    // --------- FETCH + RENDER ----------
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

                    for (int index = 0; index < rooms.length; index++) {
                        RoomItem room = rooms[index];

                        String label = (room != null && room.name != null && !room.name.trim().isEmpty())
                                ? room.name
                                : ("Room " + (room != null && room.id != null ? room.id : "?"));

                        Button btn = makeButton(label);

                        // LEFT/RIGHT ring nav for ANY room
                        btn.setOnKeyListener((v, keyCode, event) -> {
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

                            // UP/DOWN: normal vertical navigation between rooms
                            return false;
                        });

                        btn.setOnClickListener(v -> {
                            String roomIdStr = (room != null && room.id != null) ? String.valueOf(room.id) : null;
                            if (isEmpty(roomIdStr)) return;

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

                    // Focus first room initially
                    if (containerButtons.getChildCount() > 0) {
                        View first = containerButtons.getChildAt(0);
                        first.requestFocus();
                        centerView = first;           // first room is initial center
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

    // --------- UI helpers ----------
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


        // DPAD nav: unchanged from yours
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

    // Back / Home button style – speaks its own label
    private void styleBackButton(Button b) {
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
                        speakText(label);   // "Back" or "Home"
                    } else {
                        speakText("Selected item");
                    }
                } else {
                    speakText("Selected item");
                }
            }
        });
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

    @Override
    protected void onResume() {
        super.onResume();
        // after intro, this will speak focused room if any
        speakCurrentlyFocusedItem();
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
