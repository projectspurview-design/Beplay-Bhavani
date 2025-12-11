package com.example.beplay_v3;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import io.agora.rtc2.ChannelMediaOptions;
import io.agora.rtc2.Constants;
import io.agora.rtc2.IRtcEngineEventHandler;
import io.agora.rtc2.RtcEngine;
import io.agora.rtc2.RtcEngineConfig;
import io.agora.rtc2.video.VideoCanvas;
import io.agora.rtc2.video.VideoEncoderConfiguration;

public class AgoraChannelActivity extends AppCompatActivity {

    public static final String EXTRA_AGORA_APP_ID    = "extra_agora_app_id";
    public static final String EXTRA_AGORA_TOKEN     = "extra_agora_token";
    public static final String EXTRA_AGORA_CHANNEL   = "extra_agora_channel";
    public static final String EXTRA_AGORA_IS_VIDEO  = "extra_agora_is_video";
    public static final String EXTRA_AGORA_HAS_CC    = "extra_agora_has_cc";

    private static final int REQ_PERM = 33;

    // Keep false (you’re not using protobuf here)
    private static final boolean USE_PROTOBUF_DECODER = false;

    private String appId, token, channel;
    private boolean isVideo;
    private boolean hasCc;

    private RtcEngine engine;
    private boolean joined = false;
    private boolean muted  = false;

    private TextView tvLog;
    private TextView tvCaptions;
    private Button btnMute, btnLeave, btnClearCC, btnLock;
    private FrameLayout remoteContainer;

    private Integer currentRemoteUid = null;

    // ===== TTS fields (same style as RegionsActivity) =====
    private TextToSpeech tts;
    private View lastSpokenView = null;
    private long lastSpeakMillis = 0L;
    private boolean introFinished = false;

    // ===== Lock state =====
    private boolean isLocked = false;

    // ===== Vuzix voice client for lock/unlock =====
    private VuzixSpeechClient vuzixSpeechClient;
    private final Set<String> registeredPhrases = new HashSet<>();

    private final IRtcEngineEventHandler handler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String ch, int uid, int elapsed) {
            runOnUiThread(() -> {
                joined = true;
                append("Joined: " + ch + " (uid=" + uid + ")");
                btnLeave.setEnabled(true);
                btnMute.setEnabled(true);
                if (hasCc) btnClearCC.setEnabled(true);

                // TTS: announce joined
                speakText("Joined channel");
            });
        }

        @Override
        public void onUserJoined(int uid, int elapsed) {
            runOnUiThread(() -> {
                append("Remote user joined: " + uid);
                if (isVideo && currentRemoteUid == null) {
                    setupRemoteVideo(uid);
                }
            });
        }

        @Override
        public void onUserOffline(int uid, int reason) {
            runOnUiThread(() -> {
                append("Remote user left: " + uid);
                if (currentRemoteUid != null && currentRemoteUid == uid) {
                    clearRemoteVideo();
                    currentRemoteUid = null;
                }
            });
        }

        // Receive Agora data stream messages from the web (captions)
        @Override
        public void onStreamMessage(int uid, int streamId, byte[] data) {
            String rawText = tryDecodeCaption(data);
            Log.d("Raw Caption", rawText);

            if (!hasCc) return;

            String sanitizedCaption = sanitizeCaption(rawText);
            Log.d("Sanitized Caption", sanitizedCaption);

            if (TextUtils.isEmpty(sanitizedCaption)) return;

            // NOTE: only visual, no TTS here
            runOnUiThread(() -> showCaption(sanitizedCaption));
        }

        @Override
        public void onStreamMessageError(int uid, int streamId, int error, int missed, int cached) {
            append("Stream msg error uid=" + uid + " streamId=" + streamId + " err=" + error + " missed=" + missed + " cached=" + cached);
        }

        @Override
        public void onError(int err) {
            runOnUiThread(() -> append("Agora error: " + err));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agora_channel);

        tvLog           = findViewById(R.id.tvCallLog);
        tvCaptions      = findViewById(R.id.tvCaptions);
        btnMute         = findViewById(R.id.btnMute);
        btnClearCC      = findViewById(R.id.btnClearCC);
        btnLeave        = findViewById(R.id.btnLeave);
        btnLock         = findViewById(R.id.btnLock);
        remoteContainer = findViewById(R.id.remoteVideoContainer);

        appId   = getIntent().getStringExtra(EXTRA_AGORA_APP_ID);
        token   = getIntent().getStringExtra(EXTRA_AGORA_TOKEN);
        channel = getIntent().getStringExtra(EXTRA_AGORA_CHANNEL);
        isVideo = getIntent().getBooleanExtra(EXTRA_AGORA_IS_VIDEO, false);
        hasCc   = getIntent().getBooleanExtra(EXTRA_AGORA_HAS_CC, false);

        remoteContainer.setVisibility(isVideo ? View.VISIBLE : View.GONE);

        tvCaptions.setVisibility(View.GONE);
        btnClearCC.setVisibility(hasCc ? View.GONE: View.GONE);
        btnClearCC.setEnabled(false);

        // Init TTS early
        initTts();

        // Init Vuzix voice and register lock/unlock phrases
        initVuzixSpeechClient();
        registerLockVoiceCommands();

        if (isEmpty(appId) || isEmpty(token) || isEmpty(channel)) {
            append("Missing appId/token/channel");
            btnLeave.setEnabled(false);
            btnMute.setEnabled(false);
            btnClearCC.setEnabled(false);
            if (btnLock != null) btnLock.setEnabled(false);
            speakText("Required call information is missing. Cannot join channel.");
            return;
        }

        // Focus + TTS for controls
        if (btnMute != null) {
            btnMute.setFocusable(true);
            btnMute.setFocusableInTouchMode(true);
            btnMute.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    triggerRipple(v);
                    speakViewLabel(v, "Mute");
                }
            });
        }

        if (btnLeave != null) {
            btnLeave.setFocusable(true);
            btnLeave.setFocusableInTouchMode(true);
            btnLeave.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    triggerRipple(v);
                    speakViewLabel(v, "Leave call");
                }
            });
        }

        if (btnClearCC != null) {
            btnClearCC.setFocusable(true);
            btnClearCC.setFocusableInTouchMode(true);
            btnClearCC.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    triggerRipple(v);
                    speakViewLabel(v, "Clear captions");
                }
            });
        }

        if (btnLock != null) {
            btnLock.setFocusable(true);
            btnLock.setFocusableInTouchMode(true);
            btnLock.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    triggerRipple(v);
                    speakViewLabel(v, isLocked ? "Unlock screen" : "Lock screen");
                }
            });

            btnLock.setOnClickListener(v -> {
                setLocked(!isLocked);
            });
        }

        if (isVideo) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA }, REQ_PERM);
        } else {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        }

        btnMute.setOnClickListener(v -> {
            if (isLocked) {
                speakText("Screen is locked. Unlock to change mute.");
                return;
            }
            muted = !muted;
            if (engine != null) engine.muteLocalAudioStream(muted);
            btnMute.setText(muted ? "Unmute" : "Mute");
            speakText(muted ? "Muted" : "Unmuted");
        });

        btnLeave.setOnClickListener(v -> {
            if (isLocked) {
                speakText("Screen is locked. Unlock to leave the call.");
                return;
            }
            speakText("Leaving call");
            leaveAndFinish();
        });

        btnClearCC.setOnClickListener(v -> {
            if (isLocked) {
                speakText("Screen is locked. Unlock to clear captions.");
                return;
            }
            tvCaptions.setText("");
            tvCaptions.setVisibility(View.GONE);
            btnClearCC.setEnabled(false);
            speakText("Captions cleared");
        });

        btnLeave.setEnabled(false);
        btnMute.setEnabled(false);
    }

    // ===== Vuzix helpers =====
    private void initVuzixSpeechClient() {
        try {
            vuzixSpeechClient = new VuzixSpeechClient(this);
        } catch (Exception e) {
            vuzixSpeechClient = null;
        }
    }

    private void registerLockVoiceCommands() {
        if (vuzixSpeechClient == null) return;

        try {
            vuzixSpeechClient.insertKeycodePhrase("lock screen", KeyEvent.KEYCODE_F5);
            vuzixSpeechClient.insertKeycodePhrase("Lock screen", KeyEvent.KEYCODE_F5);
            registeredPhrases.add("lock screen");
            registeredPhrases.add("Lock screen");
        } catch (Exception ignored) {}

        try {
            vuzixSpeechClient.insertKeycodePhrase("unlock screen", KeyEvent.KEYCODE_F6);
            vuzixSpeechClient.insertKeycodePhrase("Unlock screen", KeyEvent.KEYCODE_F6);
            registeredPhrases.add("unlock screen");
            registeredPhrases.add("Unlock screen");
        } catch (Exception ignored) {}
    }

    // ===== Lock/unlock core logic (used by button & voice) =====
    private void setLocked(boolean lock) {
        isLocked = lock;
        if (btnLock != null) {
            btnLock.setText(lock ? "Unlock" : "Lock");
            if (lock) {
                btnLock.requestFocus();
            }
        }
        if (lock) {
            speakText("Screen locked. Use unlock button or voice command to exit or change controls.");
        } else {
            speakText("Screen unlocked.");
        }
    }

    // ===== TTS helpers =====
    private void initTts() {
        if (tts != null) return;
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
                        if ("intro_agora_call".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        introFinished = true;
                    }
                });

                // Intro sentence like RegionsActivity
                tts.speak("Call screen",
                        TextToSpeech.QUEUE_FLUSH,
                        null,
                        "intro_agora_call");
            } else {
                Toast.makeText(this, "Text-to-Speech initialization failed", Toast.LENGTH_SHORT).show();
                introFinished = true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        speakCurrentlyFocusedItem();
    }

    private void speakCurrentlyFocusedItem() {
        if (!introFinished) return;

        View root = getWindow().getDecorView();
        root.postDelayed(() -> {
            View focused = root.findFocus();

            if (focused == null) {
                // Default focus order
                if (btnLeave != null && btnLeave.isShown() && btnLeave.isEnabled()) {
                    btnLeave.requestFocus();
                    focused = btnLeave;
                } else if (btnMute != null && btnMute.isShown() && btnMute.isEnabled()) {
                    btnMute.requestFocus();
                    focused = btnMute;
                } else if (btnLock != null && btnLock.isShown() && btnLock.isEnabled()) {
                    btnLock.requestFocus();
                    focused = btnLock;
                } else if (btnClearCC != null && btnClearCC.isShown() && btnClearCC.isEnabled()) {
                    btnClearCC.requestFocus();
                    focused = btnClearCC;
                }
            }

            if (focused != null) {
                speakViewLabel(focused, "Selected item");
            }
        }, 220);
    }

    private void speakText(String text) {
        if (tts == null || text == null || text.trim().isEmpty()) return;
        try {
            if (tts.isSpeaking()) {
                tts.stop();
            }
        } catch (Exception ignored) {}
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void speakViewLabel(View v, String fallback) {
        if (!introFinished || v == null) return;

        long now = System.currentTimeMillis();
        if (v == lastSpokenView && (now - lastSpeakMillis) < 800) {
            return; // avoid spam
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

    // ===== Agora join logic (original) =====
    private void initAndJoin() {
        try {
            RtcEngineConfig cfg = new RtcEngineConfig();
            cfg.mContext = getApplicationContext();
            cfg.mAppId = appId;
            cfg.mEventHandler = handler;
            engine = RtcEngine.create(cfg);

            engine.enableAudio();
            engine.setDefaultAudioRoutetoSpeakerphone(true);
            engine.setEnableSpeakerphone(true);

            try {
                engine.setAudioProfile(Constants.AUDIO_PROFILE_MUSIC_STANDARD, Constants.AUDIO_SCENARIO_DEFAULT);
            } catch (Throwable ignore) {}

            engine.setChannelProfile(Constants.CHANNEL_PROFILE_LIVE_BROADCASTING);

            if (isVideo) {
                engine.enableVideo();
                engine.setVideoEncoderConfiguration(new VideoEncoderConfiguration(
                        VideoEncoderConfiguration.VD_640x360,
                        VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                        VideoEncoderConfiguration.STANDARD_BITRATE,
                        VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
                ));
            }

            ChannelMediaOptions opts = new ChannelMediaOptions();
            opts.autoSubscribeAudio = true;
            opts.autoSubscribeVideo = isVideo;
            opts.clientRoleType     = Constants.CLIENT_ROLE_AUDIENCE;

            engine.muteAllRemoteAudioStreams(false);
            engine.muteAllRemoteVideoStreams(false);
            engine.adjustPlaybackSignalVolume(100);

            int res = engine.joinChannel(token, channel, 0, opts);
            append("Joining channel: " + channel + " (res=" + res + ")");
        } catch (Exception e) {
            append("Agora init failed: " + e.getMessage());
            speakText("Failed to start the call.");
        }
    }

    private void setupRemoteVideo(int uid) {
        if (engine == null || remoteContainer == null) return;
        clearRemoteVideo();

        SurfaceView surface = RtcEngine.CreateRendererView(getApplicationContext());
        surface.setZOrderMediaOverlay(true);

        remoteContainer.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        engine.setupRemoteVideo(new VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, uid));
        currentRemoteUid = uid;
        append("Rendering remote video: uid=" + uid);
    }

    private void clearRemoteVideo() {
        if (remoteContainer != null) remoteContainer.removeAllViews();
    }

    private void leaveAndFinish() {
        try {
            if (engine != null && joined) engine.leaveChannel();
        } catch (Exception ignored) {}
        destroyEngine();
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyEngine();

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
            tts = null;
        }

        if (vuzixSpeechClient != null) {
            for (String phrase : registeredPhrases) {
                try {
                    vuzixSpeechClient.deletePhrase(phrase);
                } catch (Exception ignored) {}
            }
            registeredPhrases.clear();
            vuzixSpeechClient = null;
        }

        super.onDestroy();
    }

    private void destroyEngine() {
        try {
            if (engine != null) {
                RtcEngine.destroy();
                engine = null;
            }
        } catch (Exception ignored) {}
    }

    private void append(String s) {
        if (tvLog != null) tvLog.append(s + "\n");
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode != REQ_PERM) return;

        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permissions required to join channel", Toast.LENGTH_LONG).show();
                speakText("Permissions required to join the call.");
                return;
            }
        }
        initAndJoin();
    }

    // Intercept keys when locked
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isLocked) {
            int action = event.getAction();
            int keyCode = event.getKeyCode();

            if (action == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_BACK:
                        // Block navigation and back while locked
                        speakText("Screen is locked. Use unlock button or voice command.");
                        return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (isLocked) {
            speakText("Screen is locked. Unlock to leave the call.");
            return;
        }
        super.onBackPressed();
    }

    // Handle Vuzix keycodes for lock/unlock
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_F5) {
            // "lock screen"
            if (!isLocked) {
                setLocked(true);
            } else {
                speakText("Screen already locked.");
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_F6) {
            // "unlock screen"
            if (isLocked) {
                setLocked(false);
            } else {
                speakText("Screen is already unlocked.");
            }
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    // ---------- Captions decoding (original logic) ----------
    private String tryDecodeCaption(byte[] data) {
        if (data == null || data.length == 0) return "";

        if (USE_PROTOBUF_DECODER) {
            String proto = decodeCaptionProto(data);
            if (!TextUtils.isEmpty(proto)) return proto;
        }

        try {
            String raw = new String(data, StandardCharsets.UTF_8);
            String json = parseCaptionFromJson(raw);
            if (!TextUtils.isEmpty(json)) return json;

            return raw; // fallback
        } catch (Exception e) {
            return "";
        }
    }

    private String parseCaptionFromJson(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        try {
            JSONObject obj = new JSONObject(raw);
            String type = obj.optString("type", "");
            if ("cc".equalsIgnoreCase(type) || "translate".equalsIgnoreCase(type)) {
                String t = obj.optString("text");
                if (!TextUtils.isEmpty(t)) return t;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private String decodeCaptionProto(byte[] data) {
        // not used in your current setup
        return "";
    }

    private static String hexPreview(byte[] data, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, max); i++) {
            sb.append(String.format("%02X ", data[i]));
        }
        return sb.toString().trim();
    }

    private void showCaption(String text) {
        if (TextUtils.isEmpty(text)) return;
        if (tvCaptions.getVisibility() != View.VISIBLE) {
            tvCaptions.setVisibility(View.VISIBLE);
            btnClearCC.setEnabled(true);
        }
        CharSequence current = tvCaptions.getText();
        String merged = (current == null || current.length() == 0)
                ? text
                : (current + "\n" + text);

        // keep last 4 lines
        String[] lines = merged.split("\n");
        int keep = Math.max(1, Math.min(lines.length, 4));
        StringBuilder sb = new StringBuilder();
        for (int i = lines.length - keep; i < lines.length; i++) {
            if (i >= 0) sb.append(lines[i]).append(i == lines.length - 1 ? "" : "\n");
        }
        tvCaptions.setText(sb.toString());
    }

    // ---------- simple sanitizer (keeps real text, drops garbage) ----------
    private static String sanitizeCaption(String s) {
        if (s == null || s.trim().isEmpty()) return "";

        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        s = s.replace("\uFFFD", "");
        s = s.replaceAll("[\\p{C}&&[^\\n\\t]]", "");
        s = s.replaceAll("\\b\\d{2,}[A-Za-z]{2,}\\b", "");
        s = s.replaceAll("\\).*$", "");
        s = s.replaceAll("transcribe[^\\n]*", "");
        s = s.replaceAll("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Zs}\\n\\t]", "");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
        s = s.replaceAll("(?m)^[\\s\\u00A0]+$", "");

        if (s.length() < 3 || s.matches(".*[\\d\\W]{2,}.*")) {
            return "";
        }

        return s;
    }

    // ----- focus ripple helper -----
    private void triggerRipple(View v) {
        if (v == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
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
}
