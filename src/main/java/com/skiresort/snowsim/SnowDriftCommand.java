package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

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
            sender.sendMessage(ChatColor.YELLOW + "  wind_direction: degrees, 0=N 90=E 180=S 270=W");
            sender.sendMessage(ChatColor.YELLOW + "  wind_strength:  multiplier, default 1.0 (try 0.5-2.0)");
            sender.sendMessage(ChatColor.YELLOW + "  Examples:");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 315        (NW wind, normal strength)");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 0 1.5      (strong N wind)");
            sender.sendMessage(ChatColor.YELLOW + "    /snowdrift 270 0.5    (light W wind)");
            return true;
        }

        double windDeg, windStrength;
        try {
            windDeg = Double.parseDouble(args[0]) % 360;
            if (windDeg < 0) windDeg += 360;
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid wind direction: " + args[0]);
            return true;
        }

        try {
            windStrength = args.length == 2 ? Double.parseDouble(args[1]) : 1.0;
            if (windStrength <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid wind strength: " + args[1] + " (must be > 0)");
            return true;
        }

        // Load config
        String worldName        = plugin.getConfig().getString("world", "world");
        int x1                  = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1                  = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2                  = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2                  = plugin.getConfig().getInt("bounding-box.z2", -3047);
        int scanFromY           = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick         = plugin.getConfig().getInt("columns-per-tick", 1000);
        int scanDistance        = plugin.getConfig().getInt("drift.scan-distance", 80);
        double maxDriftCm       = plugin.getConfig().getDouble("drift.max-drift-cm", 600.0);
        double maxScourCm       = plugin.getConfig().getDouble("drift.max-scour-cm", 40.0);
        double scourThreshCm    = plugin.getConfig().getDouble("drift.scour-threshold-cm", 50.0);
        int snowVariance        = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance        = plugin.getConfig().getInt("cosmetic.melt-variance", 2);
        int fanAngle            = plugin.getConfig().getInt("drift.fan-angle", 30);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        // Wind comes FROM windDeg, so we look upwind in that direction
        // Convert met convention (0=N, 90=E) to XZ vector
        // In Minecraft: +X = east, +Z = south, -Z = north
        double windRad = Math.toRadians(windDeg);
        double upwindDX = Math.sin(windRad);   // east component
        double upwindDZ = -Math.cos(windRad);  // north component (Z is inverted)

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting drift pass...");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Wind from: " + windDeg + "° at strength " + windStrength);
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Scan distance: " + scanDistance
                + " blocks  Fan: ±" + fanAngle + "°  Max drift: " + maxDriftCm + "cm");
        sender.sendMessage(ChatColor.GRAY + "Progress reported every 10%.");

        final double fWindDeg       = windDeg;
        final double fWindStrength  = windStrength;
        final double fUpwindDX      = upwindDX;
        final double fUpwindDZ      = upwindDZ;
        final int    fScanFromY     = scanFromY;
        final int    fScanDistance  = scanDistance;
        final double fMaxDriftCm    = maxDriftCm;
        final double fMaxScourCm    = maxScourCm;
        final double fScourThreshCm = scourThreshCm;
        final int    fSnowVariance  = snowVariance;
        final int    fMeltVariance  = meltVariance;
        final int    fFanAngle      = fanAngle;

        new BukkitRunnable() {
            int x = minX;
            int z = minZ;
            int processed = 0;
            int drifted   = 0;
            int scoured   = 0;
            int unchanged = 0;
            int lastReportedPercent = 0;

            @Override
            public void run() {
                int doneThisTick = 0;

                while (doneThisTick < colsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        plugin.setDriftRunning(false);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Drift pass complete!"
                                + "  Drifted: "  + String.format("%,d", drifted)
                                + "  Scoured: "  + String.format("%,d", scoured)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int result = processColumn(world, x, z,
                            fScanFromY, fUpwindDX, fUpwindDZ,
                            fScanDistance, fFanAngle,
                            fWindStrength, fMaxDriftCm, fMaxScourCm, fScourThreshCm,
                            fSnowVariance, fMeltVariance);

                    if      (result > 0) drifted++;
                    else if (result < 0) scoured++;
                    else                 unchanged++;

                    processed++;
                    doneThisTick++;

                    z++;
                    if (z > maxZ) { z = minZ; x++; }

                    int percent = (int) ((processed / (double) totalColumns) * 100);
                    if (percent >= lastReportedPercent + 10) {
                        lastReportedPercent = percent - (percent % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] " + lastReportedPercent + "% -- "
                                + String.format("%,d", processed) + " / "
                                + String.format("%,d", totalColumns));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    /**
     * Finds the surface Y for a given X/Z by scanning downward.
     * Returns -1 if nothing found.
     */
    private int findSurfaceY(World world, int x, int z, int scanFromY) {
        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW || mat == Material.SNOW_BLOCK
                    || SnowUtil.GROUND_BLOCKS.contains(mat)) {
                return y;
            }
        }
        return -1;
    }

    /**
     * Process one column for drift.
     * Returns +1 drifted, -1 scoured, 0 unchanged/skipped.
     */
    private int processColumn(World world, int x, int z, int scanFromY,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle,
                              double windStrength,
                              double maxDriftCm, double maxScourCm, double scourThreshCm,
                              int snowVariance, int meltVariance) {

        // 1. Find this column's surface
        int surfaceY = findSurfaceY(world, x, z, scanFromY);
        if (surfaceY == -1) return 0;

        // Determine ground Y and current snow depth
        int groundY = surfaceY;
        int currentLayers = 0;

        Material surfMat = world.getBlockAt(x, surfaceY, z).getType();
        if (surfMat == Material.SNOW || surfMat == Material.SNOW_BLOCK) {
            // Measure snow depth
            int scanStart = surfaceY;
            if (surfMat == Material.SNOW) {
                Snow sd = (Snow) world.getBlockAt(x, surfaceY, z).getBlockData();
                currentLayers += sd.getLayers();
                scanStart--;
            }
            for (int y = scanStart; y >= world.getMinHeight(); y--) {
                if (world.getBlockAt(x, y, z).getType() == Material.SNOW_BLOCK) {
                    currentLayers += 8;
                } else {
                    groundY = y;
                    break;
                }
            }
        }
        // If no snow, groundY = surfaceY, currentLayers = 0

        int colTerrainY = groundY; // the actual terrain height (no snow)

        // 2. Fan scan upwind — sample terrain heights
        // Cast rays at windDir ± fanAngle in steps, find max upwind terrain height
        // Weight each ray by proximity (closer = more shelter effect)
        double shelterScore = 0;
        int rayCount = 0;

        // Sample angles across the fan
        int fanSteps = 7; // odd number so centre ray is included
        for (int step = 0; step < fanSteps; step++) {
            double angleOffset = -fanAngle + (step * (2.0 * fanAngle / (fanSteps - 1)));
            double rayRad = Math.toRadians(angleOffset);

            // Rotate upwind vector by angleOffset
            double rayDX = upwindDX * Math.cos(rayRad) - upwindDZ * Math.sin(rayRad);
            double rayDZ = upwindDX * Math.sin(rayRad) + upwindDZ * Math.cos(rayRad);

            // Sample along this ray at intervals, weighted by inverse distance
            double rayContrib = 0;
            double rayWeightSum = 0;

            int sampleSteps = Math.min(scanDistance, 20); // sample up to 20 points per ray
            double stepSize = scanDistance / (double) sampleSteps;

            for (int s = 1; s <= sampleSteps; s++) {
                double dist = s * stepSize;
                int sx = (int) Math.round(x + rayDX * dist);
                int sz = (int) Math.round(z + rayDZ * dist);

                int upwindSurface = findSurfaceY(world, sx, sz, scanFromY);
                if (upwindSurface == -1) continue;

                // Height difference: positive = upwind terrain is higher (shelter)
                int heightDiff = upwindSurface - colTerrainY;

                // Weight by inverse distance — closer terrain shelters more
                double distWeight = 1.0 / dist;
                rayContrib += heightDiff * distWeight;
                rayWeightSum += distWeight;
            }

            if (rayWeightSum > 0) {
                shelterScore += rayContrib / rayWeightSum;
                rayCount++;
            }
        }

        if (rayCount == 0) return 0;

        // Average shelter score across all fan rays
        shelterScore /= rayCount;

        // 3. Map shelter score to a drift modifier in cm
        // Positive shelterScore = sheltered = drift accumulation
        // Negative shelterScore = exposed = scour (only if snow is shallow)
        double driftModifierCm;

        if (shelterScore > 0) {
            // Sheltered — accumulate drift snow
            // Scale: each block of height difference at close range = significant drift
            // Normalise: a shelter score of ~5 = moderate drift, ~20 = heavy drift
            double driftFraction = Math.min(shelterScore / 20.0, 1.0);
            driftModifierCm = driftFraction * maxDriftCm * windStrength;
        } else {
            // Exposed — scour if snow is shallow enough
            double currentCm = SnowUtil.layersToCm(currentLayers);
            if (currentCm >= scourThreshCm) {
                // Deep snow — no scour, exposed ridges with deep base are fine
                return 0;
            }
            double scourFraction = Math.min(Math.abs(shelterScore) / 20.0, 1.0);
            driftModifierCm = -scourFraction * maxScourCm * windStrength;
        }

        if (Math.abs(driftModifierCm) < 6.25) return 0; // less than half a layer, skip

        int deltaLayers = SnowUtil.cmToLayers(driftModifierCm);
        if (deltaLayers == 0) return 0;

        int newLayers = Math.max(0, currentLayers + deltaLayers);
        if (newLayers == currentLayers) return 0;

        boolean isAdding = newLayers > currentLayers;

        // 4. Cosmetic variance on top partial layer
        int baseBlocks    = newLayers / 8;
        int baseRemaining = newLayers % 8;
        int topLayers;

        if (baseRemaining == 0) {
            topLayers = 0;
        } else if (isAdding) {
            int variance = (snowVariance > 0) ? random.nextInt(snowVariance * 2 + 1) - snowVariance : 0;
            topLayers = Math.max(1, Math.min(7, baseRemaining + variance));
        } else {
            if (meltVariance > 0) {
                int low  = Math.max(1, baseRemaining - meltVariance);
                int high = Math.min(7, baseRemaining + meltVariance);
                List<Integer> candidates = new ArrayList<>();
                for (int i = 1;      i < low;  i++) candidates.add(i);
                for (int i = high+1; i <= 7;   i++) candidates.add(i);
                topLayers = candidates.isEmpty() ? baseRemaining
                                                 : candidates.get(random.nextInt(candidates.size()));
            } else {
                topLayers = baseRemaining;
            }
        }

        // 5. Clear old snow
        int oldSnowBlocks = currentLayers / 8;
        int oldTopLayers  = currentLayers % 8;
        int clearUpTo     = groundY + oldSnowBlocks + (oldTopLayers > 0 ? 1 : 0);

        for (int y = groundY + 1; y <= clearUpTo; y++) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW_BLOCK || mat == Material.SNOW) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }

        if (newLayers == 0) return -1;

        // 6. Write new snow
        int writeY = groundY + 1;

        for (int i = 0; i < baseBlocks; i++) {
            Block b = world.getBlockAt(x, writeY + i, z);
            if (b.getType() != Material.AIR
                    && b.getType() != Material.SNOW
                    && b.getType() != Material.SNOW_BLOCK) {
                return isAdding ? 1 : -1;
            }
            b.setType(Material.SNOW_BLOCK, false);
        }

        if (topLayers > 0) {
            Block topSnow = world.getBlockAt(x, writeY + baseBlocks, z);
            if (topSnow.getType() != Material.AIR && topSnow.getType() != Material.SNOW) {
                return isAdding ? 1 : -1;
            }
            topSnow.setType(Material.SNOW, false);
            Snow sd = (Snow) topSnow.getBlockData();
            sd.setLayers(topLayers);
            topSnow.setBlockData(sd, false);
        }

        return isAdding ? 1 : -1;
    }
}
