package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class LanguageActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS     = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";
    public static final String EXTRA_EVENT_ID    = "extra_event_id";
    public static final String EXTRA_ROOM_ID     = "extra_room_id";

    private final OkHttpClient client = new OkHttpClient();
    private final Gson gsonPretty = new GsonBuilder().setPrettyPrinting().create();

    private TextView tvResult;

    private String codPais, categoryId, eventId, roomId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_language);

        tvResult = findViewById(R.id.tvResult);
        tvResult.setText("Loading...");

        // Get values from Intent
        codPais    = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId    = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId     = getIntent().getStringExtra(EXTRA_ROOM_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId)) {
            tvResult.setText("❌ Missing codPais / categoryId / eventId / roomId");
            return;
        }

        // 🔑 Build the full URL dynamically, only hardcoding "/regions"
        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/regions";

        fetchAndShow(url);
    }

    private void fetchAndShow(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    tvResult.setText("Request failed: " + e.getMessage());
                    Toast.makeText(LanguageActivity.this, "Network error", Toast.LENGTH_SHORT).show();
                });
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                final String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> tvResult.setText("HTTP " + response.code() + "\n\n" + body));
                    return;
                }

                // Try to pretty-print JSON
                String toDisplay = body;
                try {
                    JsonElement el = JsonParser.parseString(body);
                    toDisplay = gsonPretty.toJson(el);
                } catch (Exception ignored) { /* keep raw */ }

                final String finalText = toDisplay;
                runOnUiThread(() -> tvResult.setText(finalText));
            }
        });
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
