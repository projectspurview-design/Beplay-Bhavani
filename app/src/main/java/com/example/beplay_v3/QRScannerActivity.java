package com.example.beplay_v3;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Date;
import java.util.Locale;

public class QRScannerActivity extends AppCompatActivity {

    private static final String TAG = "QRScannerActivity";
    private static final long EXPIRY_TOLERANCE_MS = 60_000L;

    private FirebaseFirestore db;
    private SessionManager session;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        // No setContentView(): we don't show any UI here

        // ---- Firebase init (auto; manual fallback) ----
        FirebaseApp app;
        try { app = FirebaseApp.initializeApp(this); } catch (Exception e) { app = null; }
        if (app == null) {
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setProjectId("be-play-android-app")
                    .setApplicationId("1:4309035528:android:f7e3c77787ff60f1927fbd")
                    .setApiKey("AIzaSyATmdadFFVE1NCG67NB47dv5FKmOZLP9J0")
                    .setDatabaseUrl("https://be-play-android-app-default-rtdb.firebaseio.com")
                    .setStorageBucket("be-play-android-app.firebasestorage.app")
                    .build();
            try { FirebaseApp.initializeApp(this, options); }
            catch (Exception e) {
                Toast.makeText(this, "Firebase init failed", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        db = FirebaseFirestore.getInstance();
        session = SessionManager.get(this);

        // Get scanned payload
        String raw = getIntent().getStringExtra("BARCODE_VALUE");
        if (raw == null || raw.trim().isEmpty()) {
            Toast.makeText(this, "No QR content", Toast.LENGTH_SHORT).show();
            finish(); // back to camera
            return;
        }

        validateWithFirestore(raw.trim());
    }

    private void validateWithFirestore(String qrContent) {
        // Accept "Userid=Beplay;Password=1111" OR "UserId:Beplay;Password=1111"
        String userId = null, password = null;
        try {
            String[] pairs = qrContent.split(";");
            for (String p : pairs) {
                String[] kv = p.split("[:=]", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim().toLowerCase(Locale.US);
                String v = kv[1].trim();
                if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                    v = v.substring(1, v.length() - 1).trim();
                }
                if ("userid".equals(k) || "userId".equalsIgnoreCase(k)) userId = v;
                if ("password".equals(k)) password = v;
            }
        } catch (Exception e) {
            Log.e(TAG, "QR parse error", e);
        }

        if (userId == null || password == null) {
            Toast.makeText(this, "Invalid QR format", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        final String finalUserId = userId;
        final String finalPassword = password;

        // 1) Try Users/{Userid}
        db.collection("Users")
                .document(finalUserId)
                .get()
                .addOnCompleteListener((Task<DocumentSnapshot> task) -> {
                    if (!task.isSuccessful()) {
                        Toast.makeText(this, "DB error", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    DocumentSnapshot doc = task.getResult();
                    if (doc != null && doc.exists()) {
                        handleUserDocValidate(doc, finalPassword);
                    } else {
                        // 2) Fallback: where Userid == userId (handles auto-ID docs)
                        db.collection("Users")
                                .whereEqualTo("Userid", finalUserId)
                                .limit(1)
                                .get()
                                .addOnSuccessListener(qs -> {
                                    if (qs.isEmpty()) {
                                        Toast.makeText(this, "User not found", Toast.LENGTH_SHORT).show();
                                        finish();
                                    } else {
                                        handleUserDocValidate(qs.getDocuments().get(0), finalPassword);
                                    }
                                })
                                .addOnFailureListener(e -> {
                                    Toast.makeText(this, "DB error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    finish();
                                });
                    }
                });
    }

    private void handleUserDocValidate(DocumentSnapshot doc, String scannedPassword) {
        String storedPassword = doc.getString("Password");
        Date expiryDate = doc.getDate("ExpirationDate");

        if (storedPassword == null || expiryDate == null) {
            Toast.makeText(this, "Incomplete user", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!storedPassword.equals(scannedPassword)) {
            Toast.makeText(this, "Invalid password", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        long now = System.currentTimeMillis();
        long expiryMs = expiryDate.getTime();
        if (now > (expiryMs + EXPIRY_TOLERANCE_MS)) {
            Toast.makeText(this, "Access expired", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String logicalUserId = doc.getString("Userid");
        if (logicalUserId == null || logicalUserId.trim().isEmpty()) {
            logicalUserId = doc.getId();
        }

        session.createLoginSession(logicalUserId, expiryMs);
        Toast.makeText(this, "QR verified", Toast.LENGTH_SHORT).show();

        startActivity(new Intent(this, SplashActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
