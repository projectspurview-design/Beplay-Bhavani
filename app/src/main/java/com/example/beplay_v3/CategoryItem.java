package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class CategoryItem {
    @SerializedName("id")                 public String id;
    @SerializedName("nome")               public String nome;
    @SerializedName("nomeIdioma")         public String nomeIdioma;
    @SerializedName("descricao")          public String descricao;
    @SerializedName("textoAlternativo")   public String textoAlternativo;
    @SerializedName("fluxo")              public String fluxo;
    @SerializedName("statusCategoriaIdioma") public Integer statusCategoriaIdioma;
    @SerializedName("codigoBandeira")     public String codigoBandeira;
    @SerializedName("codigoPais")         public String codigoPais; // aka codPais in previous list
    @SerializedName("arquivoImagem")      public String arquivoImagem;
}
