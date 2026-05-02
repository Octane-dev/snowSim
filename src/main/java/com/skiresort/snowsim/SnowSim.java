package com.skiresort.snowsim;

import org.bukkit.plugin.java.JavaPlugin;

public class SnowSim extends JavaPlugin {

    private int[]    cachedDeltas = null;
    private boolean  driftRunning = false;
    private SnowUndo undo         = new SnowUndo();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("updatesnow").setExecutor(new UpdateSnowCommand(this));
        getCommand("snowsample").setExecutor(new SnowSampleCommand(this));
        getCommand("snowapply").setExecutor(new SnowApplyCommand(this));
        getCommand("snowreport").setExecutor(new SnowReportCommand(this));
        getCommand("snowreload").setExecutor(new SnowReloadCommand(this));
        getCommand("snowdrift").setExecutor(new SnowDriftCommand(this));
        getCommand("snowmelt").setExecutor(new SnowMeltCommand(this));
        getCommand("snowundo").setExecutor(new SnowUndoCommand(this));
        getLogger().info("SnowSim enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SnowSim disabled.");
    }

    public int[]    getCachedDeltas()             { return cachedDeltas; }
    public void     setCachedDeltas(int[] deltas) { this.cachedDeltas = deltas; }
    public boolean  isDriftRunning()              { return driftRunning; }
    public void     setDriftRunning(boolean b)    { this.driftRunning = b; }
    public SnowUndo getUndo()                     { return undo; }
}
