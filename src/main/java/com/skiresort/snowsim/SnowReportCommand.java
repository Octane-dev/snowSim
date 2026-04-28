package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.command.*;

import java.util.List;
import java.util.Map;

public class SnowReportCommand implements CommandExecutor {

    private final SnowSim plugin;

    public SnowReportCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        String worldName = plugin.getConfig().getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        List<Map<?, ?>> refList = plugin.getConfig().getMapList("reference-points");
        if (refList.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No reference points found in config.");
            return true;
        }

        sender.sendMessage(ChatColor.AQUA + "=== Snow Report ===");
        sender.sendMessage(ChatColor.AQUA + String.format("  %-10s %6s %5s %6s %8s %8s",
                "Station", "X", "Y", "Z", "Depth", ""));

        double totalCm = 0;
        int count = 0;

        for (Map<?, ?> m : refList) {
            String name = (String) m.get("name");
            int rx = ((Number) m.get("x")).intValue();
            int ry = ((Number) m.get("y")).intValue();
            int rz = ((Number) m.get("z")).intValue();

            int layers = SnowUtil.measureDepthAt(world, rx, ry, rz);
            double cm  = SnowUtil.layersToCm(layers);
            totalCm += cm;
            count++;

            // Condition label
            String condition;
            ChatColor condColor;
            if (cm == 0) {
                condition = "bare";
                condColor = ChatColor.RED;
            } else if (cm < 25) {
                condition = "dusting";
                condColor = ChatColor.YELLOW;
            } else if (cm < 75) {
                condition = "thin base";
                condColor = ChatColor.YELLOW;
            } else if (cm < 150) {
                condition = "good base";
                condColor = ChatColor.GREEN;
            } else if (cm < 250) {
                condition = "excellent";
                condColor = ChatColor.GREEN;
            } else {
                condition = "deep powder";
                condColor = ChatColor.AQUA;
            }

            sender.sendMessage(String.format("  %-10s %6d %5d %6d %6.0fcm  ",
                    name, rx, ry, rz, cm)
                    + condColor + condition + ChatColor.RESET);
        }

        if (count > 0) {
            sender.sendMessage(ChatColor.GRAY + "  Average across stations: "
                    + String.format("%.0fcm", totalCm / count));
        }

        // Show pending delta warning if snowsample has been run
        if (plugin.getCachedDeltas() != null) {
            sender.sendMessage(ChatColor.YELLOW + "  [!] Pending deltas from /snowsample not yet applied. Run /snowapply.");
        }

        sender.sendMessage(ChatColor.AQUA + "===================");
        return true;
    }
}
