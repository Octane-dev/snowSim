package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.command.*;

import java.util.List;
import java.util.Map;

public class SnowSampleCommand implements CommandExecutor {

    private final SnowSim plugin;

    public SnowSampleCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        // Expect 4 depth values in cm, one per reference point
        // Usage: /snowsample <base_cm> <mid_cm> <peak_cm> <mammoth_cm>
        if (args.length != 4) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /snowsample <base_cm> <mid_cm> <peak_cm> <mammoth_cm>");
            sender.sendMessage(ChatColor.YELLOW + "  Depths are target snow depths in cm at each reference point.");
            sender.sendMessage(ChatColor.YELLOW + "  Example: /snowsample 40 80 120 180");
            sender.sendMessage(ChatColor.YELLOW + "  Then run /snowapply to apply across the resort.");
            return true;
        }

        double[] targetCms = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                targetCms[i] = Double.parseDouble(args[i]);
                if (targetCms[i] < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid depth value: " + args[i] + " (must be >= 0)");
                return true;
            }
        }

        // Load reference points from config
        String worldName = plugin.getConfig().getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        List<Map<?, ?>> refList = plugin.getConfig().getMapList("reference-points");
        if (refList.size() < 4) {
            sender.sendMessage(ChatColor.RED + "Need 4 reference points in config. Found: " + refList.size());
            return true;
        }

        RefPoint[] refs = new RefPoint[4];
        String[] names = {"base", "mid", "peak", "mammoth"};
        for (int i = 0; i < 4; i++) {
            Map<?, ?> m = refList.get(i);
            int rx = ((Number) m.get("x")).intValue();
            int ry = ((Number) m.get("y")).intValue();
            int rz = ((Number) m.get("z")).intValue();
            int targetLayers = SnowUtil.cmToLayers(targetCms[i]);
            refs[i] = new RefPoint(names[i], rx, ry, rz, targetLayers);
        }

        // Measure current snow depth at each reference point
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Sampling reference points...");
        sender.sendMessage(ChatColor.AQUA + String.format("  %-10s %6s %6s %8s %8s %8s",
                "Point", "X", "Z", "Current", "Target", "Delta"));
        sender.sendMessage(ChatColor.AQUA + "  " + "-".repeat(52));

        int[] deltas = new int[4];
        boolean anyChange = false;

        for (int i = 0; i < 4; i++) {
            RefPoint r = refs[i];
            int currentLayers = SnowUtil.measureDepthAt(world, r.x, r.y, r.z);
            int delta = r.targetLayers - currentLayers;
            deltas[i] = delta;
            if (delta != 0) anyChange = true;

            double currentCm = SnowUtil.layersToCm(currentLayers);
            double targetCm  = SnowUtil.layersToCm(r.targetLayers);
            double deltaCm   = SnowUtil.layersToCm(Math.abs(delta));

            String deltaStr = delta == 0 ? "  none"
                    : (delta > 0 ? ChatColor.GREEN + "+" : ChatColor.RED + "-")
                      + String.format("%.0fcm", deltaCm);

            sender.sendMessage(String.format("  %-10s %6d %6d %6.0fcm %6.0fcm  %s",
                    r.name, r.x, r.z, currentCm, targetCm, deltaStr) + ChatColor.RESET);
        }

        if (!anyChange) {
            sender.sendMessage(ChatColor.GREEN + "[SnowSim] All reference points already at target depth. Nothing to do.");
            plugin.setCachedDeltas(null);
            return true;
        }

        // Cache deltas and the ref points for /snowapply
        plugin.setCachedDeltas(deltas);

        // Store refs in plugin for snowapply to use
        plugin.getConfig().set("_cached.ref0.x", refs[0].x);
        plugin.getConfig().set("_cached.ref0.y", refs[0].y);
        plugin.getConfig().set("_cached.ref0.z", refs[0].z);
        plugin.getConfig().set("_cached.ref0.delta", deltas[0]);
        plugin.getConfig().set("_cached.ref1.x", refs[1].x);
        plugin.getConfig().set("_cached.ref1.y", refs[1].y);
        plugin.getConfig().set("_cached.ref1.z", refs[1].z);
        plugin.getConfig().set("_cached.ref1.delta", deltas[1]);
        plugin.getConfig().set("_cached.ref2.x", refs[2].x);
        plugin.getConfig().set("_cached.ref2.y", refs[2].y);
        plugin.getConfig().set("_cached.ref2.z", refs[2].z);
        plugin.getConfig().set("_cached.ref2.delta", deltas[2]);
        plugin.getConfig().set("_cached.ref3.x", refs[3].x);
        plugin.getConfig().set("_cached.ref3.y", refs[3].y);
        plugin.getConfig().set("_cached.ref3.z", refs[3].z);
        plugin.getConfig().set("_cached.ref3.delta", deltas[3]);

        sender.sendMessage(ChatColor.YELLOW + "[SnowSim] Deltas calculated. Run " 
                + ChatColor.WHITE + "/snowapply" 
                + ChatColor.YELLOW + " to apply across the resort.");
        return true;
    }
}
