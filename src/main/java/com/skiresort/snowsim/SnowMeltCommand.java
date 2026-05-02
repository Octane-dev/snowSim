package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class SnowMeltCommand implements CommandExecutor {

    private final SnowSim plugin;
    private final Random random = new Random();

    public SnowMeltCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length != 4) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /snowmelt <base_cm> <mid_cm> <peak_cm> <mammoth_cm>");
            sender.sendMessage(ChatColor.YELLOW + "  Melt amounts in cm at each reference elevation.");
            sender.sendMessage(ChatColor.YELLOW + "  Higher elevations interpolate proportionally less melt.");
            sender.sendMessage(ChatColor.YELLOW + "  Example: /snowmelt 30 20 10 5");
            sender.sendMessage(ChatColor.YELLOW + "  Use 0 for a station to melt nothing there.");
            return true;
        }

        double[] meltCms = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                meltCms[i] = Double.parseDouble(args[i]);
                if (meltCms[i] < 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid value: " + args[i] + " (must be >= 0)");
                return true;
            }
        }

        String worldName = plugin.getConfig().getString("world", "world");
        int x1           = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1           = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2           = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2           = plugin.getConfig().getInt("bounding-box.z2", -3047);
        int scanFromY    = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick  = plugin.getConfig().getInt("columns-per-tick", 1000);
        int meltVariance = plugin.getConfig().getInt("cosmetic.melt-variance", 2);
        double elevWeight = plugin.getConfig().getDouble("interpolation.elevation-weight", 0.7);
        double idwPower   = plugin.getConfig().getDouble("interpolation.idw-power", 2.0);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        List<Map<?, ?>> refList = plugin.getConfig().getMapList("reference-points");
        if (refList.size() < 4) {
            sender.sendMessage(ChatColor.RED + "Need 4 reference points in config.");
            return true;
        }

        // Build ref points with melt amounts as negative deltas
        RefPoint[] refs  = new RefPoint[4];
        int[]      deltas = new int[4];
        String[] names   = {"base", "mid", "peak", "mammoth"};

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Melt amounts by station:");
        for (int i = 0; i < 4; i++) {
            Map<?, ?> m = refList.get(i);
            int rx = ((Number) m.get("x")).intValue();
            int ry = ((Number) m.get("y")).intValue();
            int rz = ((Number) m.get("z")).intValue();
            refs[i]   = new RefPoint(names[i], rx, ry, rz, 0);
            deltas[i] = -SnowUtil.cmToLayers(meltCms[i]); // negative = removal
            sender.sendMessage(ChatColor.GRAY + "  " + names[i] + ": -" + meltCms[i] + "cm");
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting melt pass over "
                + String.format("%,d", totalColumns) + " columns...");

        // Begin undo recording
        plugin.getUndo().begin("/snowmelt " + String.join(" ", args));

        final RefPoint[] fRefs       = refs;
        final int[]      fDeltas     = deltas;
        final int        fScanFromY  = scanFromY;
        final int        fColsPerTick = colsPerTick;
        final int        fMeltVar    = meltVariance;
        final double     fElevWeight = elevWeight;
        final double     fIdwPower   = idwPower;

        new BukkitRunnable() {
            int x = minX, z = minZ;
            int processed = 0, melted = 0, unchanged = 0;
            int lastPct = 0;

            @Override
            public void run() {
                int done = 0;
                while (done < fColsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        plugin.getUndo().commit();
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Melt complete!"
                                + "  Melted: "    + String.format("%,d", melted)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int groundY = SnowUtil.findGroundY(world, x, z, fScanFromY);
                    if (groundY != -1) {
                        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);
                        if (currentLayers > 0) {
                            // Interpolate melt amount for this column's elevation
                            double rawDelta = SnowUtil.interpolateDelta(
                                    x, groundY, z, fRefs, fDeltas, fElevWeight, fIdwPower);
                            int intDelta = (int) Math.round(rawDelta);
                            if (intDelta < 0) {
                                int newLayers = Math.max(0, currentLayers + intDelta);
                                if (newLayers < currentLayers) {
                                    SnowApplyCommand.writeSnow(world, x, groundY, z,
                                            currentLayers, newLayers, 0, fMeltVar, random, plugin.getUndo());
                                    melted++;
                                } else unchanged++;
                            } else unchanged++;
                        } else unchanged++;
                    }

                    processed++; done++;
                    z++;
                    if (z > maxZ) { z = minZ; x++; }

                    int pct = (int)((processed / (double)totalColumns) * 100);
                    if (pct >= lastPct + 10) {
                        lastPct = pct - (pct % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] Melt " + lastPct + "% -- "
                                + String.format("%,d", processed) + " / "
                                + String.format("%,d", totalColumns));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }


}
