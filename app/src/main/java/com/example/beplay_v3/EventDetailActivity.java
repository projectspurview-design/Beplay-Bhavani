package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.TextView;

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

public class EventDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID = "extra_category_id";
    public static final String EXTRA_EVENT_ID = "extra_event_id";

    private TextView tv;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_event_detail);

        tv = findViewById(R.id.tvEventDetail);

        String codPais = getIntent().getStringExtra(EXTRA_CODPAIS);
        String categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        String eventId = getIntent().getStringExtra(EXTRA_EVENT_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId)) {
            tv.setText("Missing codPais / categoryId / eventId");
            return;
        }

        // e.g. https://console.beplay.io/api/idiomas/br/categoria/9/event/117
        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId;

        fetch(url);
    }

    private void fetch(String url) {
        Request req = new Request.Builder().url(url).get().build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> tv.setText("Request failed:\n" + e.getMessage()));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> tv.setText("HTTP " + response.code() + "\n" + body));
                    return;
                }
                String pretty;
                try {
                    JsonElement tree = JsonParser.parseString(body);
                    pretty = gson.toJson(tree);
                } catch (Exception e) {
                    pretty = body;
                }
                String finalPretty = pretty;
                runOnUiThread(() -> tv.setText(finalPretty));
            }
        });
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
