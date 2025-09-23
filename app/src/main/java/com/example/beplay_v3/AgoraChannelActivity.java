package com.example.beplay_v3;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

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
    private Button btnMute, btnLeave, btnClearCC;
    private FrameLayout remoteContainer;

    private Integer currentRemoteUid = null;

    private final IRtcEngineEventHandler handler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String ch, int uid, int elapsed) {
            runOnUiThread(() -> {
                joined = true;
                append("Joined: " + ch + " (uid=" + uid + ")");
                btnLeave.setEnabled(true);
                btnMute.setEnabled(true);
                if (hasCc) btnClearCC.setEnabled(true);
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
            // Decode the incoming stream data (speech-to-text/translation)
            String rawText = tryDecodeCaption(data);

            // Log the raw text before any sanitization for debugging purposes
            Log.d("Raw Caption", rawText);

            if (!hasCc) return;

            // --- Only sanitize and show caption in the UI ---
            String sanitizedCaption = sanitizeCaption(rawText);

            // Log the sanitized text after applying filters for debugging
            Log.d("Sanitized Caption", sanitizedCaption);

            // If the sanitized text is not empty, update the UI
            if (TextUtils.isEmpty(sanitizedCaption)) return;

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
        btnLeave        = findViewById(R.id.btnLeave);
        btnClearCC      = findViewById(R.id.btnClearCC);
        remoteContainer = findViewById(R.id.remoteVideoContainer);

        appId   = getIntent().getStringExtra(EXTRA_AGORA_APP_ID);
        token   = getIntent().getStringExtra(EXTRA_AGORA_TOKEN);
        channel = getIntent().getStringExtra(EXTRA_AGORA_CHANNEL);
        isVideo = getIntent().getBooleanExtra(EXTRA_AGORA_IS_VIDEO, false);
        hasCc   = getIntent().getBooleanExtra(EXTRA_AGORA_HAS_CC, false);

        remoteContainer.setVisibility(isVideo ? View.VISIBLE : View.GONE);

        tvCaptions.setVisibility(View.GONE);
        btnClearCC.setVisibility(hasCc ? View.VISIBLE : View.GONE);
        btnClearCC.setEnabled(false);

        if (isEmpty(appId) || isEmpty(token) || isEmpty(channel)) {
            append("Missing appId/token/channel");
            btnLeave.setEnabled(false);
            btnMute.setEnabled(false);
            btnClearCC.setEnabled(false);
            return;
        }

        if (isVideo) {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA }, REQ_PERM);
        } else {
            requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ_PERM);
        }

        btnMute.setOnClickListener(v -> {
            muted = !muted;
            if (engine != null) engine.muteLocalAudioStream(muted);
            btnMute.setText(muted ? "Unmute" : "Mute");
        });

        btnLeave.setOnClickListener(v -> leaveAndFinish());

        btnClearCC.setOnClickListener(v -> {
            tvCaptions.setText("");
            tvCaptions.setVisibility(View.GONE);
        });

        btnLeave.setEnabled(false);
        btnMute.setEnabled(false);
    }

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
        super.onDestroy();
        destroyEngine();
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
                return;
            }
        }
        initAndJoin();
    }

    // ---------- Captions decoding (unchanged logic) ----------

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

            return raw; // previously working fallback
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

    // --- Inside your AgoraChannelActivity.java ---

    // Update the sanitizeCaption function to handle these specific cases:
    private static String sanitizeCaption(String s) {
        if (s == null || s.trim().isEmpty()) return "";  // Don't process empty or null text

        // Normalize common forms (Unicode normalization)
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);

        // Remove the replacement character and non-characters
        s = s.replace("\uFFFD", "");

        // Remove control characters except for newline and tab (we still need newlines for multi-line captions)
        s = s.replaceAll("[\\p{C}&&[^\\n\\t]]", "");

        // Strip out unwanted patterns like "03HR" or similar sequences
        s = s.replaceAll("\\b\\d{2,}[A-Za-z]{2,}\\b", "");

        // Remove everything after the first closing bracket ')'
        s = s.replaceAll("\\).*$", "");

        // Remove unwanted "transcribe" labels and language codes like "transcribezen-US3"
        // Retain the part like "transcribez" and "US3", and filter out only unnecessary metadata
        s = s.replaceAll("transcribe[^\n]*", "");  // Remove the "transcribe" label and language code if it's part of the caption

        // Ensure only letters, numbers, punctuation, spaces, and newlines/tabs remain
        s = s.replaceAll("[^\\p{L}\\p{M}\\p{N}\\p{P}\\p{Zs}\\n\\t]", "");

        // Collapse multiple spaces and trim extra whitespace at the beginning and end
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();

        // Remove any blank lines or lines with just special characters (e.g., control chars)
        s = s.replaceAll("(?m)^[\\s\\u00A0]+$", "");

        // Validate length - If the resulting text is too short or contains only "junk", discard it
        if (s.length() < 3 || s.matches(".*[\\d\\W]{2,}.*")) {
            return "";
        }

        return s;
    }

}
