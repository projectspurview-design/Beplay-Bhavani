package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class EventItem {
    @SerializedName("id")            public Integer id;
    @SerializedName("eventName")     public String eventName;
    @SerializedName("moreDetails")   public String moreDetails;
    @SerializedName("status")        public String status;
    @SerializedName("watchers")      public String watchers;
    @SerializedName("eventFormat")   public String eventFormat;
    @SerializedName("exhibitionType")public String exhibitionType;
    @SerializedName("publicEventLink") public String publicEventLink;
    @SerializedName("internalLink")  public String internalLink;
    @SerializedName("dateStart")     public String dateStart;
    @SerializedName("dateFinish")    public String dateFinish;
    @SerializedName("hourStart")     public String hourStart;
    @SerializedName("hourFinish")    public String hourFinish;
    @SerializedName("created_at")    public String createdAt;
    @SerializedName("updated_at")    public String updatedAt;
    @SerializedName("is_unic")       public String isUnic;
    @SerializedName("image")         public String image;
    @SerializedName("image_alt")     public String imageAlt;
    @SerializedName("lacation_event")public String lacationEvent; // typo as returned by API
    @SerializedName("link")          public String link;
    @SerializedName("label_link")    public String labelLink;
    @SerializedName("client_id")     public String clientId;
    @SerializedName("categoria_id")  public String categoriaId;

    // nested client object (optional)
    public static class Client {
        @SerializedName("id")             public Integer id;
        @SerializedName("name")           public String name;
        @SerializedName("email")          public String email;
        @SerializedName("email_verified_at") public String emailVerifiedAt;
        @SerializedName("created_at")     public String createdAt;
        @SerializedName("updated_at")     public String updatedAt;
        @SerializedName("isCompany")      public String isCompany;
        @SerializedName("companyName")    public String companyName;
        @SerializedName("cpfCnpj")        public String cpfCnpj;
        @SerializedName("phone")          public String phone;
        @SerializedName("is_temp")        public String isTemp;
    }
    @SerializedName("client") public Client client;
}
