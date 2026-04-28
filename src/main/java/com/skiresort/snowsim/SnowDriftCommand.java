package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Random;

public class SnowDriftCommand implements CommandExecutor {

    private final SnowSim plugin;
    private final Random random = new Random();

    public SnowDriftCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /snowdrift <wind_direction> [wind_strength]");
            sender.sendMessage(ChatColor.YELLOW + "  wind_direction: degrees 0=N 90=E 180=S 270=W");
            sender.sendMessage(ChatColor.YELLOW + "  wind_strength:  multiplier, default 1.0");
            sender.sendMessage(ChatColor.YELLOW + "  Examples:");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 315       (NW wind, normal)");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 0 1.5     (strong N wind)");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 270 0.5   (light W wind)");
            return true;
        }

        double windDeg;
        double windStrength = 1.0;

        try {
            windDeg = Double.parseDouble(args[0]) % 360;
            if (windDeg < 0) windDeg += 360;
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid wind direction: " + args[0]);
            return true;
        }

        if (args.length == 2) {
            try {
                windStrength = Double.parseDouble(args[1]);
                if (windStrength <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid wind strength: " + args[1] + " (must be > 0)");
                return true;
            }
        }

        // Load config
        String worldName     = plugin.getConfig().getString("world", "world");
        int x1               = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1               = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2               = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2               = plugin.getConfig().getInt("bounding-box.z2", -3047);
        int scanFromY        = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick      = plugin.getConfig().getInt("drift.columns-per-tick",
                                   plugin.getConfig().getInt("columns-per-tick", 300));
        int scanDistance     = plugin.getConfig().getInt("drift.scan-distance", 80);
        int fanAngle         = plugin.getConfig().getInt("drift.fan-angle", 30);
        double maxDriftCm    = plugin.getConfig().getDouble("drift.max-drift-cm", 600.0);
        double maxScourCm    = plugin.getConfig().getDouble("drift.max-scour-cm", 40.0);
        double scourThreshCm = plugin.getConfig().getDouble("drift.scour-threshold-cm", 50.0);
        int snowVariance     = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance     = plugin.getConfig().getInt("cosmetic.melt-variance", 2);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        // Wind direction in Minecraft XZ space
        // Met convention: wind FROM degrees. 0=N, 90=E, 180=S, 270=W
        // Minecraft: +X=east, +Z=south, -Z=north
        // So upwind vector (direction we look for shelter) = direction wind is coming FROM
        double windRad  = Math.toRadians(windDeg);
        double upwindDX =  Math.sin(windRad);
        double upwindDZ = -Math.cos(windRad);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting drift pass...");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Wind from " + windDeg + "° at strength " + windStrength);
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Scan: " + scanDistance + " blocks  Fan: ±" + fanAngle
                + "°  Max drift: " + maxDriftCm + "cm  Scour threshold: " + scourThreshCm + "cm");
        sender.sendMessage(ChatColor.GRAY + "Progress every 10%.");

        final double fWindStrength  = windStrength;
        final double fUpwindDX      = upwindDX;
        final double fUpwindDZ      = upwindDZ;
        final int    fScanFromY     = scanFromY;
        final int    fScanDistance  = scanDistance;
        final int    fFanAngle      = fanAngle;
        final double fMaxDriftCm    = maxDriftCm;
        final double fMaxScourCm    = maxScourCm;
        final double fScourThreshCm = scourThreshCm;
        final int    fSnowVariance  = snowVariance;
        final int    fMeltVariance  = meltVariance;

        new BukkitRunnable() {
            int x = minX, z = minZ;
            int processed = 0, drifted = 0, scoured = 0, unchanged = 0;
            int lastPct = 0;

            @Override
            public void run() {
                int done = 0;
                while (done < colsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        plugin.setDriftRunning(false);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Drift complete!"
                                + "  Drifted: "  + String.format("%,d", drifted)
                                + "  Scoured: "  + String.format("%,d", scoured)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int result = processColumn(world, x, z, fScanFromY,
                            fUpwindDX, fUpwindDZ, fScanDistance, fFanAngle,
                            fWindStrength, fMaxDriftCm, fMaxScourCm, fScourThreshCm,
                            fSnowVariance, fMeltVariance);

                    if      (result > 0) drifted++;
                    else if (result < 0) scoured++;
                    else                 unchanged++;

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

    private int processColumn(World world, int x, int z, int scanFromY,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle,
                              double windStrength,
                              double maxDriftCm, double maxScourCm, double scourThreshCm,
                              int snowVariance, int meltVariance) {

        // 1. Find TRUE terrain ground (no snow) for this column
        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        // 2. Measure current snow depth from ground up
        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);

        // 3. Fan scan upwind using TERRAIN height only (no snow)
        //    This prevents previously drifted snow from masquerading as terrain shelter
        double shelterScore = 0;
        int rayCount = 0;

        int fanSteps  = 7;
        int samplePts = Math.min(scanDistance, 20);
        double stepSize = scanDistance / (double) samplePts;

        for (int step = 0; step < fanSteps; step++) {
            double angleOffset = -fanAngle + (step * (2.0 * fanAngle / (fanSteps - 1)));
            double rayRad = Math.toRadians(angleOffset);

            // Rotate upwind vector
            double rayDX = upwindDX * Math.cos(rayRad) - upwindDZ * Math.sin(rayRad);
            double rayDZ = upwindDX * Math.sin(rayRad) + upwindDZ * Math.cos(rayRad);

            double rayContrib   = 0;
            double rayWeightSum = 0;

            for (int s = 1; s <= samplePts; s++) {
                double dist = s * stepSize;
                int sx = (int) Math.round(x + rayDX * dist);
                int sz = (int) Math.round(z + rayDZ * dist);

                // Use terrain-only height — ignores snow on upwind terrain
                int upwindTerrainY = SnowUtil.findGroundY(world, sx, sz, scanFromY);
                if (upwindTerrainY == -1) continue;

                // Height difference vs our terrain (not snow surface)
                // Positive = upwind ground is higher = sheltered
                int heightDiff = upwindTerrainY - groundY;

                double distWeight = 1.0 / dist;
                rayContrib   += heightDiff * distWeight;
                rayWeightSum += distWeight;
            }

            if (rayWeightSum > 0) {
                shelterScore += rayContrib / rayWeightSum;
                rayCount++;
            }
        }

        if (rayCount == 0) return 0;
        shelterScore /= rayCount;

        // 4. Map shelter score to drift modifier in cm
        double driftModifierCm;

        if (shelterScore > 0) {
            // Sheltered hollow — accumulate drift
            double fraction = Math.min(shelterScore / 20.0, 1.0);
            driftModifierCm = fraction * maxDriftCm * windStrength;
        } else {
            // Exposed — only scour if snow is shallow
            double currentCm = SnowUtil.layersToCm(currentLayers);
            if (currentCm >= scourThreshCm) return 0; // deep base, leave it alone
            double fraction = Math.min(Math.abs(shelterScore) / 20.0, 1.0);
            driftModifierCm = -fraction * maxScourCm * windStrength;
        }

        // Skip if less than half a layer's worth
        if (Math.abs(driftModifierCm) < 6.25) return 0;

        int deltaLayers = SnowUtil.cmToLayers(driftModifierCm);
        if (deltaLayers == 0) return 0;

        int newLayers = Math.max(0, currentLayers + deltaLayers);
        if (newLayers == currentLayers) return 0;

        return SnowApplyCommand.writeSnow(world, x, groundY, z,
                currentLayers, newLayers, snowVariance, meltVariance, random);
    }
}
