package com.skiresort.snowsim;

import org.bukkit.ChatColor;
import org.bukkit.command.*;

public class SnowReloadCommand implements CommandExecutor {

    private final SnowSim plugin;

    public SnowReloadCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        plugin.reloadConfig();
        plugin.setCachedDeltas(null); // clear any pending sample since config may have changed

        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Config reloaded.");
        sender.sendMessage(ChatColor.GRAY + "  World:         " + plugin.getConfig().getString("world", "world"));
        sender.sendMessage(ChatColor.GRAY + "  Scan from Y:   " + plugin.getConfig().getInt("scan-from-y", 700));
        sender.sendMessage(ChatColor.GRAY + "  Cols/tick:     " + plugin.getConfig().getInt("columns-per-tick", 1000));
        sender.sendMessage(ChatColor.GRAY + "  Bounding box:  "
                + "X " + plugin.getConfig().getInt("bounding-box.x1") + " to " + plugin.getConfig().getInt("bounding-box.x2")
                + "  Z " + plugin.getConfig().getInt("bounding-box.z1") + " to " + plugin.getConfig().getInt("bounding-box.z2"));
        sender.sendMessage(ChatColor.GRAY + "  Elev weight:   " + plugin.getConfig().getDouble("interpolation.elevation-weight", 0.7));
        sender.sendMessage(ChatColor.GRAY + "  IDW power:     " + plugin.getConfig().getDouble("interpolation.idw-power", 2.0));
        sender.sendMessage(ChatColor.YELLOW + "  Note: any pending /snowsample deltas have been cleared.");

        return true;
    }
}
