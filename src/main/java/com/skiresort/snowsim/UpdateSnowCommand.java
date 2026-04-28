package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

public class UpdateSnowCommand implements CommandExecutor {

    private final SnowSim plugin;
    private final Random random = new Random();

    public UpdateSnowCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /updatesnow <depth_in_cm>");
            sender.sendMessage(ChatColor.YELLOW + "  Sets an absolute uniform snow depth across the resort.");
            sender.sendMessage(ChatColor.YELLOW + "  1 block = 100cm.  1 layer = 12.5cm.");
            sender.sendMessage(ChatColor.YELLOW + "  Examples:");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 0    -> clear all snow");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 50   -> 50cm (4 layers)");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 100  -> 1m (1 snow block)");
            return true;
        }

        double targetCm;
        try {
            targetCm = Double.parseDouble(args[0]);
            if (targetCm < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid depth: " + args[0] + " (must be >= 0)");
            return true;
        }

        int targetLayers = SnowUtil.cmToLayers(targetCm);

        String worldName = plugin.getConfig().getString("world", "world");
        int x1           = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1           = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2           = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2           = plugin.getConfig().getInt("bounding-box.z2", -3047);
        int scanFromY    = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick  = plugin.getConfig().getInt("columns-per-tick", 1000);
        int snowVariance = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance = plugin.getConfig().getInt("cosmetic.melt-variance", 2);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Target: " + targetCm + "cm"
                + " = " + targetLayers + " layers"
                + " = " + String.format("%.2f", targetLayers / 8.0) + " blocks.");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] "
                + String.format("%,d", totalColumns) + " columns. Progress every 10%.");

        final int fTargetLayers = targetLayers;
        final int fScanFromY    = scanFromY;
        final int fColsPerTick  = colsPerTick;
        final int fSnowVar      = snowVariance;
        final int fMeltVar      = meltVariance;

        new BukkitRunnable() {
            int x = minX, z = minZ;
            int processed = 0, added = 0, removed = 0, unchanged = 0;
            int lastPct = 0;

            @Override
            public void run() {
                int done = 0;
                while (done < fColsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Done!"
                                + "  Added: "     + String.format("%,d", added)
                                + "  Melted: "    + String.format("%,d", removed)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    // Ground-first approach: find true terrain, measure snow above it
                    int groundY = SnowUtil.findGroundY(world, x, z, fScanFromY);
                    if (groundY != -1) {
                        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);
                        if (currentLayers != fTargetLayers) {
                            int r = SnowApplyCommand.writeSnow(world, x, groundY, z,
                                    currentLayers, fTargetLayers, fSnowVar, fMeltVar, random);
                            if      (r > 0) added++;
                            else if (r < 0) removed++;
                            else            unchanged++;
                        } else {
                            unchanged++;
                        }
                    }

                    processed++; done++;
                    z++;
                    if (z > maxZ) { z = minZ; x++; }

                    int pct = (int)((processed / (double)totalColumns) * 100);
                    if (pct >= lastPct + 10) {
                        lastPct = pct - (pct % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] " + lastPct + "% -- "
                                + String.format("%,d", processed) + " / " + String.format("%,d", totalColumns));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }
}
