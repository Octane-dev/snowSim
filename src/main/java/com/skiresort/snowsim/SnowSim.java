package com.skiresort.snowsim;

import org.bukkit.plugin.java.JavaPlugin;

public class SnowSim extends JavaPlugin {

    // Cached deltas from /snowsample, keyed by reference point name
    // Each entry is the delta in layers to add (positive) or remove (negative)
    private int[] cachedDeltas = null;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("updatesnow").setExecutor(new UpdateSnowCommand(this));
        getCommand("snowsample").setExecutor(new SnowSampleCommand(this));
        getCommand("snowapply").setExecutor(new SnowApplyCommand(this));
        getLogger().info("SnowSim enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SnowSim disabled.");
    }

    public int[] getCachedDeltas() { return cachedDeltas; }
    public void setCachedDeltas(int[] deltas) { this.cachedDeltas = deltas; }
}
