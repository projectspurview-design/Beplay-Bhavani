package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class RoomItem {
    @SerializedName("id")           public Integer id;
    @SerializedName("name")         public String name;
    @SerializedName("description")  public String description;
    @SerializedName("event_slug_link") public String eventSlugLink;
    @SerializedName("created_at")   public String createdAt;
    @SerializedName("updated_at")   public String updatedAt;
}
