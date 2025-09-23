package com.example.beplay_v3;

import android.content.Context;
import android.content.SharedPreferences;

public class SessionManager {
    private static final String PREF = "qr_lock_prefs";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_EXPIRY_MS = "expiry_ms";

    private final SharedPreferences sp;

    private SessionManager(Context ctx) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static SessionManager get(Context ctx) {
        return new SessionManager(ctx.getApplicationContext());
    }

    public void createLoginSession(String userId, long expiryMs) {
        sp.edit()
                .putString(KEY_USER_ID, userId)
                .putLong(KEY_EXPIRY_MS, expiryMs)
                .apply();
    }

    public boolean isQRAuthenticated() {
        return sp.contains(KEY_USER_ID);
    }

    public boolean isQRExpired() {
        long exp = sp.getLong(KEY_EXPIRY_MS, 0L);
        return exp == 0L || System.currentTimeMillis() > exp;
    }

    public void clearQRAuthentication() {
        sp.edit().clear().apply();
    }
}
