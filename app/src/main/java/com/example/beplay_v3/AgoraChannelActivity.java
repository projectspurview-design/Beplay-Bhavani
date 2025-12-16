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
import java.util.ArrayDeque;
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

// ✅ Because java_multiple_files = true, these are top-level generated classes:
import io.agora.rtc.speech2text.Text;
import io.agora.rtc.speech2text.Word;
import io.agora.rtc.speech2text.Translation;

public class AgoraChannelActivity extends AppCompatActivity {

    public static final String EXTRA_AGORA_APP_ID    = "extra_agora_app_id";
    public static final String EXTRA_AGORA_TOKEN     = "extra_agora_token";
    public static final String EXTRA_AGORA_CHANNEL   = "extra_agora_channel";
    public static final String EXTRA_AGORA_IS_VIDEO  = "extra_agora_is_video";
    public static final String EXTRA_AGORA_HAS_CC    = "extra_agora_has_cc";

    private static final int REQ_PERM = 33;

    // ✅ Decode protobuf (same as web)
    private static final boolean USE_PROTOBUF_DECODER = true;

    // If you want to reduce duplicates even more, set false (final only)
    private static final boolean SHOW_INTERIM_CAPTIONS = true;

    // Prefer Arabic translations if present (web side)
    private static final String PREFERRED_TRANSLATION_LANG = "ar";

    // UI: keep last N lines visible
    private static final int MAX_VISIBLE_LINES = 4;

    // Recent-final dedupe window
    private static final long FINAL_DEDUPE_WINDOW_MS = 12_000;

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

    // ===== TTS fields =====
    private TextToSpeech tts;
    private View lastSpokenView = null;
    private long lastSpeakMillis = 0L;
    private boolean introFinished = false;

    // ===== Lock state =====
    private boolean isLocked = false;

    // ===== Vuzix voice =====
    private VuzixSpeechClient vuzixSpeechClient;
    private final Set<String> registeredPhrases = new HashSet<>();

    // ==========================================================
    // ✅ Caption state: FINAL lines + one LIVE (interim) line
    // ==========================================================
    private final ArrayDeque<String> finalLines = new ArrayDeque<>();
    private String liveKey = null;
    private String liveText = "";

    private static class RecentFinal {
        final String key;
        final long ts;
        RecentFinal(String key, long ts) { this.key = key; this.ts = ts; }
    }
    private final ArrayDeque<RecentFinal> recentFinals = new ArrayDeque<>();

    private final IRtcEngineEventHandler handler = new IRtcEngineEventHandler() {
        @Override
        public void onJoinChannelSuccess(String ch, int uid, int elapsed) {
            runOnUiThread(() -> {
                joined = true;
                append("Joined: " + ch + " (uid=" + uid + ")");
                btnLeave.setEnabled(true);
                btnMute.setEnabled(true);
                if (hasCc) btnClearCC.setEnabled(true);
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
            if (!hasCc) return;

            DecodedCaption dc = decodeCaptionWithMeta(uid, data);
            if (dc == null) return;

            String sanitized = sanitizeCaption(dc.text);
            if (TextUtils.isEmpty(sanitized)) return;

            runOnUiThread(() -> {
                if (dc.isFinal) {
                    commitFinalLine(dc.key, sanitized);
                } else {
                    updateLiveLine(dc.key, sanitized);
                }
            });
        }

        @Override
        public void onStreamMessageError(int uid, int streamId, int error, int missed, int cached) {
            append("Stream msg error uid=" + uid + " streamId=" + streamId +
                    " err=" + error + " missed=" + missed + " cached=" + cached);
        }

        @Override
        public void onError(int err) {
            runOnUiThread(() -> append("Agora error: " + err));
        }
    };

    // ===========================
    // ✅ Decode result with meta
    // ===========================
    private static class DecodedCaption {
        final String text;
        final boolean isFinal;
        final String key; // segment key for interim/final (uid + seqnum)
        DecodedCaption(String text, boolean isFinal, String key) {
            this.text = text;
            this.isFinal = isFinal;
            this.key = key;
        }
    }

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
        btnClearCC.setVisibility(View.GONE);
        btnClearCC.setEnabled(false);

        initTts();
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

            btnLock.setOnClickListener(v -> setLocked(!isLocked));
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

        btnLeave.setOnClickListener(v -> handleLeavePressed());

        btnClearCC.setOnClickListener(v -> {
            if (isLocked) {
                speakText("Screen is locked. Unlock to clear captions.");
                return;
            }
            clearAllCaptions();
            speakText("Captions cleared");
        });

        btnLeave.setEnabled(false);
        btnMute.setEnabled(false);
    }

    // ===========================
    // ✅ Caption rendering logic
    // ===========================
    private void updateLiveLine(String key, String text) {
        if (!SHOW_INTERIM_CAPTIONS) return;

        liveKey = key;
        liveText = text;
        renderCaptions();
    }

    private void commitFinalLine(String key, String text) {
        // if this final is the same segment that was live, clear live
        if (key != null && key.equals(liveKey)) {
            liveKey = null;
            liveText = "";
        }

        // drop recent duplicates (finals can be resent)
        if (isRecentFinalDuplicate(key, text)) {
            renderCaptions(); // still remove live if needed
            return;
        }

        finalLines.addLast(text);
        while (finalLines.size() > 30) finalLines.removeFirst(); // keep buffer

        renderCaptions();
    }

    private void clearAllCaptions() {
        finalLines.clear();
        liveKey = null;
        liveText = "";
        recentFinals.clear();

        tvCaptions.setText("");
        tvCaptions.setVisibility(View.GONE);
        btnClearCC.setEnabled(false);
    }

    private void renderCaptions() {
        // Build visible lines: last N final + optional live
        boolean hasLive = !TextUtils.isEmpty(liveText);

        int maxFinalVisible = hasLive ? (MAX_VISIBLE_LINES - 1) : MAX_VISIBLE_LINES;
        if (maxFinalVisible < 0) maxFinalVisible = 0;

        // take last maxFinalVisible from finalLines
        String[] finals = finalLines.toArray(new String[0]);
        int start = Math.max(0, finals.length - maxFinalVisible);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < finals.length; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(finals[i]);
        }

        if (hasLive) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(liveText);
        }

        String out = sb.toString().trim();
        if (TextUtils.isEmpty(out)) {
            tvCaptions.setText("");
            tvCaptions.setVisibility(View.GONE);
            btnClearCC.setEnabled(false);
            return;
        }

        tvCaptions.setText(out);
        if (tvCaptions.getVisibility() != View.VISIBLE) {
            tvCaptions.setVisibility(View.VISIBLE);
        }
        btnClearCC.setEnabled(true);
    }

    private boolean isRecentFinalDuplicate(String segKey, String text) {
        String key = normalizeKey(segKey, text);
        if (TextUtils.isEmpty(key)) return true;

        long now = System.currentTimeMillis();
        while (!recentFinals.isEmpty() && (now - recentFinals.peekFirst().ts) > FINAL_DEDUPE_WINDOW_MS) {
            recentFinals.removeFirst();
        }

        for (RecentFinal rf : recentFinals) {
            if (rf.key.equals(key)) return true;
        }

        recentFinals.addLast(new RecentFinal(key, now));
        if (recentFinals.size() > 60) recentFinals.removeFirst();
        return false;
    }

    private String normalizeKey(String segKey, String text) {
        String t = (text == null) ? "" : text.toLowerCase(Locale.ROOT).trim();
        t = t.replaceAll("\\s+", " ");
        t = t.replaceAll("[\\p{Punct}]+$", ""); // remove trailing punctuation
        if (TextUtils.isEmpty(t)) return "";
        // segKey ensures same speaker+seq segment repeats are dropped
        return (segKey == null ? "" : segKey) + "|" + t;
    }

    // ===========================
    // ✅ Decoding (protobuf/web style)
    // ===========================
    private DecodedCaption decodeCaptionWithMeta(int rtcUid, byte[] data) {
        if (data == null || data.length == 0) return null;

        // JSON path
        if (looksLikeJson(data)) {
            String raw = decodeAsJsonOrUtf8(data);
            if (TextUtils.isEmpty(raw)) return null;
            // treat JSON as final
            return new DecodedCaption(raw, true, "json|" + rtcUid);
        }

        // Protobuf first
        if (USE_PROTOBUF_DECODER) {
            DecodedCaption dc = decodeCaptionProto(data, rtcUid);
            if (dc != null && !TextUtils.isEmpty(dc.text)) return dc;
        }

        // Fallback UTF-8
        String raw = decodeAsJsonOrUtf8(data);
        if (TextUtils.isEmpty(raw)) return null;
        return new DecodedCaption(raw, true, "utf8|" + rtcUid);
    }

    private DecodedCaption decodeCaptionProto(byte[] data, int rtcUid) {
        try {
            Text msg = Text.parseFrom(data);

            long speakerUid = msg.getUid();
            if (speakerUid <= 0) speakerUid = rtcUid;

            // segment key: same speaker + seqnum => same segment updates
            String segKey = speakerUid + "#" + msg.getSeqnum();

            boolean isFinal = isFinalSegment(msg);

            String text = extractFromTranslations(msg);
            if (TextUtils.isEmpty(text)) {
                text = extractFromWords(msg);
            }

            if (TextUtils.isEmpty(text)) return null;

            // If you don't want interim, drop them here
            if (!SHOW_INTERIM_CAPTIONS && !isFinal) return null;

            Log.d("STT_PROTO", "uid=" + speakerUid + " seq=" + msg.getSeqnum() +
                    " final=" + isFinal + " text=" + text);

            return new DecodedCaption(text, isFinal, segKey);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private boolean isFinalSegment(Text msg) {
        if (msg.getEndOfSegment()) return true;

        for (Translation t : msg.getTransList()) {
            if (t.getIsFinal()) return true;
        }
        for (Word w : msg.getWordsList()) {
            if (w.getIsFinal()) return true;
        }
        return false;
    }

    private String extractFromTranslations(Text msg) {
        if (msg.getTransCount() <= 0) return "";

        Translation best = null;
        for (Translation t : msg.getTransList()) {
            if (PREFERRED_TRANSLATION_LANG.equalsIgnoreCase(t.getLang())) {
                best = t;
                break;
            }
        }
        if (best == null) best = msg.getTrans(0);

        if (best.getTextsCount() <= 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < best.getTextsCount(); i++) {
            String part = best.getTexts(i);
            if (!TextUtils.isEmpty(part)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(part);
            }
        }
        return sb.toString().trim();
    }

    private String extractFromWords(Text msg) {
        if (msg.getWordsCount() <= 0) return "";

        StringBuilder sb = new StringBuilder();
        for (Word w : msg.getWordsList()) {
            String t = w.getText();
            if (!TextUtils.isEmpty(t)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(t);
            }
        }
        return sb.toString().trim();
    }

    private String decodeAsJsonOrUtf8(byte[] data) {
        try {
            String raw = new String(data, StandardCharsets.UTF_8);
            String json = parseCaptionFromJson(raw);
            if (!TextUtils.isEmpty(json)) return json;
            return raw;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean looksLikeJson(byte[] data) {
        int i = 0;
        while (i < data.length) {
            byte b = data[i];
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') { i++; continue; }
            return b == '{' || b == '[';
        }
        return false;
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

    // ---------- sanitizer ----------
    private static String sanitizeCaption(String s) {
        if (s == null || s.trim().isEmpty()) return "";

        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        s = s.replace("\uFFFD", "");
        s = s.replaceAll("[\\p{C}&&[^\\n\\t]]", "");
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();

        if (s.length() < 2) return "";
        return s;
    }

    // ===========================
    // ✅ Vuzix + Lock + TTS (original behavior kept)
    // ===========================
    private void initVuzixSpeechClient() {
        try { vuzixSpeechClient = new VuzixSpeechClient(this); }
        catch (Exception e) { vuzixSpeechClient = null; }
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

        try {
            vuzixSpeechClient.insertKeycodePhrase("leave", KeyEvent.KEYCODE_F7);
            vuzixSpeechClient.insertKeycodePhrase("Leave", KeyEvent.KEYCODE_F7);
            registeredPhrases.add("leave");
            registeredPhrases.add("Leave");
        } catch (Exception ignored) {}
    }

    private void setLocked(boolean lock) {
        isLocked = lock;
        if (btnLock != null) {
            btnLock.setText(lock ? "Unlock" : "Lock");
            if (lock) btnLock.requestFocus();
        }
        if (lock) speakText("Screen locked. Use voice command to change controls.");
        else speakText("Screen unlocked.");
    }
    private void handleLeavePressed() {
        if (isLocked) {
            speakText("Screen is locked. Unlock to leave the call.");
            return;
        }
        speakText("Leaving call");
        leaveAndFinish();
    }
    private void initTts() {
        if (tts != null) return;
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                tts.setPitch(0.9f);

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        if ("intro_agora_call".equals(utteranceId)) {
                            introFinished = true;
                            runOnUiThread(() -> speakCurrentlyFocusedItem());
                        }
                    }
                    @Override public void onError(String utteranceId) { introFinished = true; }
                });

                tts.speak("Call screen", TextToSpeech.QUEUE_FLUSH, null, "intro_agora_call");
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

            if (focused != null) speakViewLabel(focused, "Selected item");
        }, 220);
    }

    private void speakText(String text) {
        if (tts == null || text == null || text.trim().isEmpty()) return;
        try { if (tts.isSpeaking()) tts.stop(); } catch (Exception ignored) {}
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    private void speakViewLabel(View v, String fallback) {
        if (!introFinished || v == null) return;

        long now = System.currentTimeMillis();
        if (v == lastSpokenView && (now - lastSpeakMillis) < 800) return;
        lastSpokenView = v;
        lastSpeakMillis = now;

        CharSequence labelCs = null;
        if (v instanceof Button) labelCs = ((Button) v).getText();
        String label = (labelCs != null) ? labelCs.toString() : null;
        String toSpeak = (label != null && !label.trim().isEmpty()) ? label : fallback;
        if (!TextUtils.isEmpty(toSpeak)) speakText(toSpeak);
    }

    // ===========================
    // ✅ Agora join logic (original)
    // ===========================
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
        try { if (engine != null && joined) engine.leaveChannel(); } catch (Exception ignored) {}
        destroyEngine();
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyEngine();

        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {}
            tts = null;
        }

        if (vuzixSpeechClient != null) {
            for (String phrase : registeredPhrases) {
                try { vuzixSpeechClient.deletePhrase(phrase); } catch (Exception ignored) {}
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_F5) {
            if (!isLocked) setLocked(true);
            else speakText("Screen already locked.");
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_F6) {
            if (isLocked) setLocked(false);
            else speakText("Screen is already unlocked.");
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_F7) {
            handleLeavePressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

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
