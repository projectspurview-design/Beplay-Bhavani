package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RegionLanguagesActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS    = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";
    public static final String EXTRA_EVENT_ID    = "extra_event_id";
    public static final String EXTRA_ROOM_ID     = "extra_room_id";
    public static final String EXTRA_REGION_ID   = "extra_region_id";

    private LinearLayout containerButtons;
    private TextView tvResponse;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_region_languages);

        containerButtons = findViewById(R.id.containerButtons);
        tvResponse = findViewById(R.id.tvResponse);

        codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        regionId = getIntent().getStringExtra(EXTRA_REGION_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId)
                || isEmpty(roomId) || isEmpty(regionId)) {
            containerButtons.addView(disabled("(Missing required params)"));
            return;
        }

        // Fetch languages for this region
        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idiomas";

        fetchLanguages(url);
    }

    private void fetchLanguages(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    containerButtons.addView(disabled("Request failed: " + e.getMessage()));
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        containerButtons.removeAllViews();
                        containerButtons.addView(disabled("HTTP " + response.code()));
                    });
                    return;
                }

                // Parse JSON
                RegionLanguagesEnvelope env;
                try {
                    env = gson.fromJson(body, RegionLanguagesEnvelope.class);
                } catch (Exception ex) {
                    env = null;
                }

                LanguageItem[] languages = (env != null && env.language != null) ? env.language : new LanguageItem[0];

                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    if (languages.length == 0) {
                        containerButtons.addView(disabled("(No languages)"));
                        return;
                    }

                    for (LanguageItem lang : languages) {
                        String label = (lang != null && lang.name != null) ? lang.name : ("Language " + (lang != null ? lang.id : "?"));
                        Button btn = button(label);
                        btn.setOnClickListener(v -> fetchLanguageDetails(lang.id));
                        containerButtons.addView(btn);
                    }
                });
            }
        });
    }

    private void fetchLanguageDetails(Integer languageId) {
        if (languageId == null) return;

        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idioma/" + languageId;

        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> tvResponse.setText("Request failed: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                runOnUiThread(() -> tvResponse.setText(body));
            }
        });
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    private Button disabled(String text) {
        Button b = button(text);
        b.setEnabled(false);
        return b;
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    // --- Models ---
    static class RegionLanguagesEnvelope {
        public LanguageItem[] language;
    }

    static class LanguageItem {
        public Integer id;
        public String name;
        public String code;
        public String codLanguage;
        public String created_at;
        public String updated_at;
    }
}
