package com.example.beplay_v3;

import com.google.gson.annotations.SerializedName;

public class RoomsEnvelope {
    // We only map what we need (rooms). Other fields are ignored.
    @SerializedName("rooms") public RoomItem[] rooms;
}
