package com.skiresort.snowsim;

public class RefPoint {
    public final String name;
    public final int x, y, z;
    public final int targetLayers; // target depth in snow layers

    public RefPoint(String name, int x, int y, int z, int targetLayers) {
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.targetLayers = targetLayers;
    }
}
