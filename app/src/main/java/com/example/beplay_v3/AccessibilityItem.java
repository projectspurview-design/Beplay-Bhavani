package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class AccessibilityItem {
    @SerializedName("id") public Integer id;
    @SerializedName("name") public String name;
    @SerializedName("image_link") public String imageLink;
    @SerializedName("alt_image") public String altImage;
    @SerializedName("type_channel_id") public String typeChannelId;
    @SerializedName("link") public String link;
}
