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
            return true;
        }

        double windDeg, windStrength = 1.0;
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

        String worldName      = plugin.getConfig().getString("world", "world");
        int x1                = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1                = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2                = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2                = plugin.getConfig().getInt("bounding-box.z2", -3047);
        int scanFromY         = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick       = plugin.getConfig().getInt("drift.columns-per-tick", 300);
        int scanDistance      = plugin.getConfig().getInt("drift.scan-distance", 80);
        int fanAngle          = plugin.getConfig().getInt("drift.fan-angle", 40);
        double maxDriftCm     = plugin.getConfig().getDouble("drift.max-drift-cm", 600.0);
        double maxScourCm     = plugin.getConfig().getDouble("drift.max-scour-cm", 40.0);
        double scourThreshCm  = plugin.getConfig().getDouble("drift.scour-threshold-cm", 50.0);
        double shelterNorm    = plugin.getConfig().getDouble("drift.shelter-normaliser", 8.0);
        int fenceReach        = plugin.getConfig().getInt("drift.fence-drift-reach", 18);
        double fencePeakCm    = plugin.getConfig().getDouble("drift.fence-drift-peak-cm", 150.0);
        double slopeAngle     = plugin.getConfig().getDouble("drift.drift-slope-angle", 35.0);
        int snowVariance      = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance      = plugin.getConfig().getInt("cosmetic.melt-variance", 2);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        double windRad  = Math.toRadians(windDeg);
        double upwindDX =  Math.sin(windRad);
        double upwindDZ = -Math.cos(windRad);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting drift pass...");
        final double fWindStrength  = windStrength;
        final double fUpwindDX      = upwindDX;
        final double fUpwindDZ      = upwindDZ;

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
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Drift pass complete!");
                        plugin.setDriftRunning(false);
                        return;
                    }

                    int result = processColumn(world, x, z, scanFromY,
                            fUpwindDX, fUpwindDZ,
                            scanDistance, fanAngle, fWindStrength,
                            maxDriftCm, maxScourCm, scourThreshCm, shelterNorm,
                            fenceReach, fencePeakCm, slopeAngle,
                            snowVariance, meltVariance);

                    if (result > 0) drifted++;
                    else if (result < 0) scoured++;
                    else unchanged++;

                    processed++; done++;
                    z++;
                    if (z > maxZ) { z = minZ; x++; }

                    int pct = (int)((processed / (double)totalColumns) * 100);
                    if (pct >= lastPct + 10) {
                        lastPct = pct - (pct % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] " + lastPct + "%");
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    private static class FanResult {
        final double shelterScore;
        final double weightedSurfaceY; // UPWIND ONLY
        final double crestSurfaceY;
        final double crestDistance;
        FanResult(double shelterScore, double weightedSurfaceY,
                  double crestSurfaceY, double crestDistance) {
            this.shelterScore      = shelterScore;
            this.weightedSurfaceY  = weightedSurfaceY;
            this.crestSurfaceY     = crestSurfaceY;
            this.crestDistance     = crestDistance;
        }
    }

    private FanResult fanScan(World world, int x, int groundY, int z,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle, int scanFromY) {

        final int TOTAL_RAYS  = 16;
        final double DOWNWIND_BIAS = 0.20; // Reduced to prevent mountain-back-shelter
        final int CREST_THRESHOLD  = 2;

        double shelterSum    = 0;
        double surfaceYSum   = 0;
        double totalWeight   = 0;
        double surfaceWeight = 0;
        int    rayCount      = 0;

        double crestSurfaceYSum = 0;
        double crestDistSum     = 0;
        int    crestRayCount    = 0;

        int samplePts = Math.min(scanDistance, 20);
        double stepSize = scanDistance / (double) samplePts;

        for (int ri = 0; ri < TOTAL_RAYS; ri++) {
            double rayDeg = (360.0 / TOTAL_RAYS) * ri;
            double rayRad = Math.toRadians(rayDeg);
            double rayDX  =  Math.sin(rayRad);
            double rayDZ  = -Math.cos(rayRad);

            double dot = rayDX * upwindDX + rayDZ * upwindDZ;
            double dirWeight = DOWNWIND_BIAS + (1.0 - DOWNWIND_BIAS) * ((dot + 1.0) / 2.0);

            double rayContrib    = 0;
            double raySurfContrib = 0;
            double rayWeightSum  = 0;

            double rayCrestSurfaceY = -1;
            double rayCrestDist     = -1;

            for (int s = 1; s <= samplePts; s++) {
                double dist = s * stepSize;
                int sx = (int) Math.round(x + rayDX * dist);
                int sz = (int) Math.round(z + rayDZ * dist);

                int nGroundY = SnowUtil.findGroundY(world, sx, sz, scanFromY);
                if (nGroundY == -1) continue;
                int nSnowLayers  = SnowUtil.measureDepthAboveGround(world, sx, nGroundY, sz);
                double nSurfaceY = nGroundY + (nSnowLayers / 8.0);

                double distWeight  = 1.0 / dist;
                double heightDiff  = nSurfaceY - groundY;
                
                rayContrib    += heightDiff * distWeight;
                raySurfContrib += nSurfaceY * distWeight;
                rayWeightSum  += distWeight;

                // Crest: strictly upwind check
                if (dot > 0.6 && rayCrestDist < 0 && heightDiff >= CREST_THRESHOLD) {
                    rayCrestSurfaceY = nSurfaceY;
                    rayCrestDist     = dist;
                }
            }

            if (rayWeightSum > 0) {
                shelterSum   += (rayContrib / rayWeightSum) * dirWeight;
                totalWeight  += dirWeight;
                
                // Height Cap (SurfaceY) only uses upwind/side rays (dot > 0)
                // This prevents the mountain "behind" you from inflating the cap on windward faces.
                if (dot > 0) {
                    surfaceYSum  += (raySurfContrib / rayWeightSum) * dirWeight;
                    surfaceWeight += dirWeight;
                }
                rayCount++;
            }

            if (rayCrestDist > 0) {
                crestSurfaceYSum += rayCrestSurfaceY;
                crestDistSum     += rayCrestDist;
                crestRayCount++;
            }
        }

        if (rayCount == 0) return new FanResult(0, groundY, groundY, 0);

        double avgCrestSurfaceY = crestRayCount > 0 ? crestSurfaceYSum / crestRayCount : groundY;
        double avgCrestDist     = crestRayCount > 0 ? crestDistSum / crestRayCount : 0;
        double avgSurfaceY      = surfaceWeight > 0 ? surfaceYSum / surfaceWeight : groundY;

        return new FanResult(shelterSum / totalWeight, avgSurfaceY, avgCrestSurfaceY, avgCrestDist);
    }

    private int processColumn(World world, int x, int z, int scanFromY,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle, double windStrength,
                              double maxDriftCm, double maxScourCm, double scourThreshCm,
                              double shelterNorm,
                              int fenceReach, double fencePeakCm, double slopeAngle,
                              int snowVariance, int meltVariance) {

        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);
        double currentCm  = SnowUtil.layersToCm(currentLayers);

        // Immediate neighbor building/cliff edge guard
        for (int ex = -1; ex <= 1; ex++) {
            for (int ez = -1; ez <= 1; ez++) {
                if (ex == 0 && ez == 0) continue;
                int eGroundY = SnowUtil.findGroundY(world, x + ex, z + ez, scanFromY);
                if (eGroundY != -1 && Math.abs(eGroundY - groundY) > 10) return 0;
            }
        }

        // Fence Logic (Condensed for space, remains same as original logic)
        double fenceDriftCm  = 0;
        double fenceCapY     = Double.MAX_VALUE;
        if (world.getBlockAt(x, groundY, z).getType() != Material.OAK_FENCE) {
            for (int dist = 1; dist <= fenceReach; dist++) {
                int fx = (int) Math.round(x + upwindDX * dist);
                int fz = (int) Math.round(z + upwindDZ * dist);
                // (Simplified fence search - check if fence exists upwind)
                if (world.getBlockAt(fx, groundY, fz).getType() == Material.OAK_FENCE || 
                    world.getBlockAt(fx, groundY+1, fz).getType() == Material.OAK_FENCE) {
                    double taper = Math.cos((dist / (double) fenceReach) * (Math.PI / 2));
                    fenceDriftCm = fencePeakCm * taper * windStrength;
                    fenceCapY = groundY + 1.5; // Roughly fence top
                    break;
                }
            }
        }

        FanResult fan = fanScan(world, x, groundY, z, upwindDX, upwindDZ, scanDistance, fanAngle, scanFromY);

        double terrainDriftCm = 0;
        if (fan.shelterScore > 0) {
            double fraction = Math.sqrt(Math.min(fan.shelterScore / shelterNorm, 1.0));
            terrainDriftCm = fraction * maxDriftCm * windStrength;
        } else if (fan.shelterScore < -0.1 && currentCm > 0) {
            double fraction = Math.sqrt(Math.min(Math.abs(fan.shelterScore) / (shelterNorm * 0.5), 1.0));
            terrainDriftCm = -fraction * maxScourCm * windStrength;
        }

        double totalDriftCm = terrainDriftCm + fenceDriftCm;
        if (Math.abs(totalDriftCm) < 6.0) return 0;

        int deltaLayers = SnowUtil.cmToLayers(totalDriftCm);
        int newLayers = Math.max(0, currentLayers + deltaLayers);

        // Height Taper Cap Logic
        final double SLOPE_TAN = Math.tan(Math.toRadians(slopeAngle));
        double terrainCapY;
        if (fan.crestDistance > 0) {
            // Leeward of a ridge: Taper down from the crest
            terrainCapY = fan.crestSurfaceY - (fan.crestDistance * SLOPE_TAN);
        } else {
            // Windward or Flat: Cap at the height of incoming snow
            terrainCapY = fan.weightedSurfaceY;
        }

        double finalCapY = Math.min(terrainCapY, fenceCapY);
        int maxLayersByCap = (int) Math.floor((finalCapY - groundY) * 8);

        if (newLayers > currentLayers) {
            newLayers = Math.min(newLayers, Math.max(0, maxLayersByCap));
        }

        if (newLayers == currentLayers) return 0;

        return SnowApplyCommand.writeSnow(world, x, groundY, z,
                currentLayers, newLayers, snowVariance, meltVariance, random);
    }
}
