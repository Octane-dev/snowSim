package com.skiresort.snowsim;

import org.bukkit.plugin.java.JavaPlugin;

public class SnowSim extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("updatesnow").setExecutor(new UpdateSnowCommand(this));
        getCommand("snowsim").setExecutor(new SnowSimCommand(this));
        
        getLogger().info("SnowSim enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("SnowSim disabled.");
    }
}
