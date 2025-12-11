package com.example.beplay_v3;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
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

public class RegionIdiomasActivity extends AppCompatActivity {

    public static final String EXTRA_CODPAIS      = "extra_codpais";
    public static final String EXTRA_CATEGORY_ID  = "extra_category_id";
    public static final String EXTRA_EVENT_ID     = "extra_event_id";
    public static final String EXTRA_ROOM_ID      = "extra_room_id";
    public static final String EXTRA_REGION_ID    = "extra_region_id";

    private LinearLayout containerButtons;
    private final OkHttpClient client = new OkHttpClient();
    private final Gson gson = new GsonBuilder().create();

    private String codPais, categoryId, eventId, roomId, regionId;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_region_idiomas);

        containerButtons = findViewById(R.id.containerButtons);

        codPais    = getIntent().getStringExtra(EXTRA_CODPAIS);
        categoryId = getIntent().getStringExtra(EXTRA_CATEGORY_ID);
        eventId    = getIntent().getStringExtra(EXTRA_EVENT_ID);
        roomId     = getIntent().getStringExtra(EXTRA_ROOM_ID);
        regionId   = getIntent().getStringExtra(EXTRA_REGION_ID);

        if (isEmpty(codPais) || isEmpty(categoryId) || isEmpty(eventId) || isEmpty(roomId) || isEmpty(regionId)) {
            containerButtons.addView(disabled("(Missing codPais/categoryId/eventId/roomId/regionId)"));
            return;
        }

        String url = "https://console.beplay.io/api/idiomas/" + codPais
                + "/categoria/" + categoryId
                + "/event/" + eventId
                + "/room/" + roomId
                + "/region/" + regionId
                + "/idiomas";

        fetchAndRender(url);
    }

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

                IdiomaItem[] idiomas;
                try { idiomas = gson.fromJson(body, IdiomaItem[].class); }
                catch (Exception ex) { idiomas = new IdiomaItem[0]; }

                IdiomaItem[] finalIdiomas = idiomas;
                runOnUiThread(() -> {
                    containerButtons.removeAllViews();
                    if (finalIdiomas.length == 0) {
                        containerButtons.addView(disabled("(too much cock idiomas)"));
                        return;
                    }
                    for (IdiomaItem item : finalIdiomas) {
                        String label = (item != null && item.nome != null && !item.nome.trim().isEmpty())
                                ? item.nome
                                : ("Idioma " + (item != null && item.id != null ? item.id : "?"));

                        Button btn = button(label);
                        btn.setOnClickListener(v -> {
                            String idiomaId = (item != null && item.id != null) ? String.valueOf(item.id) : null;
                            if (isEmpty(idiomaId)) return;

                            Intent i = new Intent(RegionIdiomasActivity.this, IdiomaDetailActivity.class);
                            i.putExtra(IdiomaDetailActivity.EXTRA_CODPAIS, codPais);
                            i.putExtra(IdiomaDetailActivity.EXTRA_CATEGORY_ID, categoryId);
                            i.putExtra(IdiomaDetailActivity.EXTRA_EVENT_ID, eventId);
                            i.putExtra(IdiomaDetailActivity.EXTRA_ROOM_ID, roomId);
                            i.putExtra(IdiomaDetailActivity.EXTRA_REGION_ID, regionId);
                            i.putExtra(IdiomaDetailActivity.EXTRA_IDIOMA_ID, idiomaId);
                            startActivity(i);
                        });
                        containerButtons.addView(btn);
                    }
                });
            }
        });
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }
    private Button disabled(String text) { Button b = button(text); b.setEnabled(false); return b; }
    private int dp(int v) { float d = getResources().getDisplayMetrics().density; return Math.round(v * d); }
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
}
