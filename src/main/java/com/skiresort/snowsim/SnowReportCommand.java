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

        sender.sendMessage(ChatColor.AQUA + "========= Snow Report =========");

        // --- Reference stations ---
        List<Map<?, ?>> refList = plugin.getConfig().getMapList("reference-points");
        if (!refList.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "-- Reference Stations --");
            sender.sendMessage(ChatColor.AQUA + String.format("  %-12s %6s %5s %6s %8s",
                    "Station", "X", "Y", "Z", "Depth"));
            double totalCm = 0;
            int count = 0;
            for (Map<?, ?> m : refList) {
                String name = (String) m.get("name");
                int rx = ((Number) m.get("x")).intValue();
                int ry = ((Number) m.get("y")).intValue();
                int rz = ((Number) m.get("z")).intValue();
                int layers = SnowUtil.measureDepthAt(world, rx, ry, rz);
                double cm  = SnowUtil.layersToCm(layers);
                totalCm += cm; count++;
                sender.sendMessage(formatStation(name, rx, ry, rz, cm));
            }
            if (count > 0) {
                sender.sendMessage(ChatColor.GRAY + "  Average: "
                        + String.format("%.0fcm", totalCm / count));
            }
        }

        // --- Extra sample points ---
        List<Map<?, ?>> extraList = plugin.getConfig().getMapList("report-points");
        if (!extraList.isEmpty()) {
            sender.sendMessage(ChatColor.AQUA + "-- Sample Points --");
            sender.sendMessage(ChatColor.AQUA + String.format("  %-14s %6s %5s %6s %8s",
                    "Name", "X", "Y", "Z", "Depth"));
            for (Map<?, ?> m : extraList) {
                String name = (String) m.get("name");
                int rx = ((Number) m.get("x")).intValue();
                int ry = ((Number) m.get("y")).intValue();
                int rz = ((Number) m.get("z")).intValue();
                int layers = SnowUtil.measureDepthAboveGround(world, rx, ry, rz);
                double cm  = SnowUtil.layersToCm(layers);
                sender.sendMessage(formatStation(name, rx, ry, rz, cm));
            }
        }

        // --- Undo status ---
        sender.sendMessage(ChatColor.AQUA + "-- Undo History --");
        sender.sendMessage(ChatColor.GRAY + "  " + plugin.getUndo().status());

        // --- Pending deltas warning ---
        if (plugin.getCachedDeltas() != null) {
            sender.sendMessage(ChatColor.YELLOW
                    + "  [!] Pending /snowsample deltas not yet applied. Run /snowapply.");
        }

        sender.sendMessage(ChatColor.AQUA + "===============================");
        return true;
    }

    private String formatStation(String name, int x, int y, int z, double cm) {
        String condition;
        ChatColor condColor;
        if (cm == 0)        { condition = "bare";        condColor = ChatColor.RED;    }
        else if (cm < 25)   { condition = "dusting";     condColor = ChatColor.YELLOW; }
        else if (cm < 75)   { condition = "thin base";   condColor = ChatColor.YELLOW; }
        else if (cm < 150)  { condition = "good base";   condColor = ChatColor.GREEN;  }
        else if (cm < 300)  { condition = "excellent";   condColor = ChatColor.GREEN;  }
        else if (cm < 600)  { condition = "deep powder"; condColor = ChatColor.AQUA;   }
        else                { condition = "extreme";     condColor = ChatColor.AQUA;   }

        return String.format("  %-14s %6d %5d %6d %6.0fcm  ", name, x, y, z, cm)
                + condColor + condition + ChatColor.RESET;
    }
}
