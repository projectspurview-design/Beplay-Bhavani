// RegionDetailEnvelope.java  (for /region/{idiomaId})
package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

class RegionDetailEnvelope {
    @SerializedName("event")    Object event;
    @SerializedName("room")     Object room;
    @SerializedName("category") Object category;
    @SerializedName("idioma")   IdiomaItem idioma; // <-- single object with id, nome, ...
}
