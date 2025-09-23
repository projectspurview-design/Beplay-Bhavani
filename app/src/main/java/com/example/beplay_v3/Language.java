package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class Language {
    @SerializedName("id")       public String id;      // idioma id from first endpoint
    @SerializedName("nome")     public String nome;    // label (Portuguese)
    @SerializedName("codigo")   public String codigo;
    @SerializedName("status")   public String status;
    @SerializedName("codPais")  public String codPais; // country code like "br"
    @SerializedName("created_at") public String createdAt;
    @SerializedName("updated_at") public String updatedAt;
}
