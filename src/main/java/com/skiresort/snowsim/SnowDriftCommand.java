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
        int colsPerTick       = plugin.getConfig().getInt("drift.columns-per-tick",
                                    plugin.getConfig().getInt("columns-per-tick", 300));
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
        int reposeLayers      = plugin.getConfig().getInt("drift.respose-layers", 11);
        double fillFraction   = plugin.getConfig().getDouble("drift.fill-fraction", 0.4);

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
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Wind from " + windDeg
                + "° strength " + windStrength
                + "  shelter-normaliser: " + shelterNorm);
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Scan: " + scanDistance
                + " blocks  Fan: ±" + fanAngle
                + "°  Max drift: " + maxDriftCm + "cm");
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
        final double fShelterNorm   = shelterNorm;
        final int    fFenceReach    = fenceReach;
        final double fFencePeakCm   = fencePeakCm;
        final double fSlopeAngle    = slopeAngle;
        final int    fSnowVariance  = snowVariance;
        final int    fMeltVariance  = meltVariance;
        final int    fReposeLayers  = reposeLayers;
        final double fFillFraction  = fillFraction;

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
                        // Phase 1 done — kick off phase 2 (neighbour smoothing fill)
                        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Drift pass complete!"
                                + "  Drifted: "   + String.format("%,d", drifted)
                                + "  Scoured: "   + String.format("%,d", scoured)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting smoothing pass...");
                        runSmoothingPass(world, minX, maxX, minZ, maxZ,
                                fScanFromY, colsPerTick, fSnowVariance, fMeltVariance, fReposeLayers, fFillFraction, sender);
                        return;
                    }

                    int result = processColumn(world, x, z, fScanFromY,
                            fUpwindDX, fUpwindDZ,
                            fScanDistance, fFanAngle, fWindStrength,
                            fMaxDriftCm, fMaxScourCm, fScourThreshCm, fShelterNorm,
                            fFenceReach, fFencePeakCm, fSlopeAngle,
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

    /**
     * Holds the result of the upwind fan scan:
     * - shelterScore:       weighted height difference (positive = sheltered)
     * - weightedSurfaceY:   weighted avg upwind SNOW surface Y (terrain + snow)
     * - crestSurfaceY:      snow surface Y at the detected ridge crest
     * - crestDistance:      XZ distance in blocks to the detected ridge crest
     */
    private static class FanResult {
        final double shelterScore;
        final double weightedSurfaceY;
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

    /**
     * Cast the upwind fan.
     * - Shelter score uses terrain-only heights.
     * - weightedSurfaceY uses snow surfaces (for overall cap reference).
     * - Crest = nearest upwind point where snow surface is >= 2 blocks above current ground.
     *   The leeward slope taper originates from the crest snow surface Y and crest distance.
     */
    private FanResult fanScan(World world, int x, int groundY, int z,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle, int scanFromY) {

        double shelterWeightedDiff = 0;
        double surfaceWeightedY    = 0;
        double totalWeight         = 0;
        int    rayCount            = 0;

        // Crest detection — averaged across fan rays for smoothness
        double crestSurfaceYSum  = 0;
        double crestDistanceSum  = 0;
        int    crestRayCount     = 0;

        // Minimum height gain above current ground to count as a crest
        final int CREST_THRESHOLD = 2;

        int fanSteps  = 7;
        int samplePts = Math.min(scanDistance, 20);
        double stepSize = scanDistance / (double) samplePts;

        for (int step = 0; step < fanSteps; step++) {
            double angleOffset = -fanAngle + (step * (2.0 * fanAngle / (fanSteps - 1)));
            double rayRad = Math.toRadians(angleOffset);
            double rayDX  = upwindDX * Math.cos(rayRad) - upwindDZ * Math.sin(rayRad);
            double rayDZ  = upwindDX * Math.sin(rayRad) + upwindDZ * Math.cos(rayRad);

            double rayTerrainContrib = 0;
            double raySurfaceContrib = 0;
            double rayWeightSum      = 0;

            // Track crest for this ray — nearest point >= CREST_THRESHOLD above groundY
            double rayCrestSurfaceY  = -1;
            double rayCrestDist      = -1;

            for (int s = 1; s <= samplePts; s++) {
                double dist = s * stepSize;
                int sx = (int) Math.round(x + rayDX * dist);
                int sz = (int) Math.round(z + rayDZ * dist);

                int upwindTerrainY = SnowUtil.findTerrainY(world, sx, sz, scanFromY);
                if (upwindTerrainY == -1) continue;

                int upwindGroundY    = SnowUtil.findGroundY(world, sx, sz, scanFromY);
                int upwindSnowLayers = upwindGroundY != -1
                        ? SnowUtil.measureDepthAboveGround(world, sx, upwindGroundY, sz) : 0;
                double upwindSurfaceY = upwindTerrainY + (upwindSnowLayers / 8.0);

                double distWeight = 1.0 / dist;
                rayTerrainContrib += (upwindTerrainY - groundY) * distWeight;
                raySurfaceContrib += upwindSurfaceY * distWeight;
                rayWeightSum      += distWeight;

                // Crest detection — first point this ray encounters significant height gain
                if (rayCrestDist < 0 && (upwindSurfaceY - groundY) >= CREST_THRESHOLD) {
                    rayCrestSurfaceY = upwindSurfaceY;
                    rayCrestDist     = dist;
                }
            }

            if (rayWeightSum > 0) {
                shelterWeightedDiff += rayTerrainContrib / rayWeightSum;
                surfaceWeightedY    += raySurfaceContrib / rayWeightSum;
                totalWeight         += 1.0;
                rayCount++;
            }

            if (rayCrestDist > 0) {
                crestSurfaceYSum += rayCrestSurfaceY;
                crestDistanceSum += rayCrestDist;
                crestRayCount++;
            }
        }

        if (rayCount == 0) return new FanResult(0, groundY, groundY, 0);

        double avgCrestSurfaceY = crestRayCount > 0
                ? crestSurfaceYSum / crestRayCount : groundY;
        double avgCrestDist     = crestRayCount > 0
                ? crestDistanceSum / crestRayCount : 0;

        return new FanResult(
            shelterWeightedDiff / rayCount,
            surfaceWeightedY    / totalWeight,
            avgCrestSurfaceY,
            avgCrestDist
        );
    }

    private int processColumn(World world, int x, int z, int scanFromY,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle, double windStrength,
                              double maxDriftCm, double maxScourCm, double scourThreshCm,
                              double shelterNorm,
                              int fenceReach, double fencePeakCm, double slopeAngle,
                              int snowVariance, int meltVariance) {

        // 1. Find ground (includes fence tops and cappable water)
        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);
        double currentCm  = SnowUtil.layersToCm(currentLayers);

        // 2. Fence leeward drift
        double fenceDriftCm  = 0;
        double fenceCapY     = Double.MAX_VALUE; // cap from fence height
        Material thisBlock   = world.getBlockAt(x, groundY, z).getType();
        boolean thisIsFence  = thisBlock == Material.OAK_FENCE;

        if (!thisIsFence) {
            for (int dist = 1; dist <= fenceReach; dist++) {
                int fx = (int) Math.round(x + upwindDX * dist);
                int fz = (int) Math.round(z + upwindDZ * dist);

                for (int fy = groundY + 10; fy >= groundY - 2; fy--) {
                    if (world.getBlockAt(fx, fy, fz).getType() == Material.OAK_FENCE) {
                        // Measure actual fence height
                        int fenceHeightBlocks = 0;
                        for (int fh = fy; world.getBlockAt(fx, fh, fz).getType()
                                == Material.OAK_FENCE; fh++) {
                            fenceHeightBlocks++;
                        }
                        double fenceHeightCm = fenceHeightBlocks * 100.0;

                        // Check burial
                        int fenceTerrainY = SnowUtil.findTerrainY(world, fx, fz, scanFromY);
                        if (fenceTerrainY != -1) {
                            int fenceSnowLayers = SnowUtil.measureDepthAboveGround(
                                    world, fx, fenceTerrainY, fz);
                            double fenceSnowCm = SnowUtil.layersToCm(fenceSnowLayers);
                            if (fenceSnowCm >= fenceHeightCm) break; // buried, skip

                            double taper = Math.cos(
                                    (dist / (double) fenceReach) * (Math.PI / 2));
                            fenceDriftCm = fencePeakCm * taper * windStrength;

                            // Cap: top of fence snow surface
                            // = fence terrain Y + snow blocks + fence height blocks
                            fenceCapY = fenceTerrainY
                                    + (fenceSnowLayers / 8.0)
                                    + fenceHeightBlocks;
                        }
                        break;
                    }
                }
                if (fenceDriftCm > 0) break;
            }
        }

        // 3. Fence top settling — only if surrounding snow has reached fence height
        if (thisIsFence) {
            int thisFenceHeight = 0;
            for (int fh = groundY;
                    world.getBlockAt(x, fh, z).getType() == Material.OAK_FENCE; fh++) {
                thisFenceHeight++;
            }
            double thisFenceHeightCm = thisFenceHeight * 100.0;

            int surroundLayers = 0, surroundCount = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz2 = -3; dz2 <= 3; dz2++) {
                    if (dx == 0 && dz2 == 0) continue;
                    int nearGround = SnowUtil.findGroundY(world, x + dx, z + dz2, scanFromY);
                    if (nearGround != -1 && !SnowUtil.FENCE_BLOCKS.contains(
                            world.getBlockAt(x + dx, z + dz2, nearGround).getType())) {
                        surroundLayers += SnowUtil.measureDepthAboveGround(
                                world, x + dx, nearGround, z + dz2);
                        surroundCount++;
                    }
                }
            }
            double avgSurroundCm = surroundCount > 0
                    ? SnowUtil.layersToCm(surroundLayers / surroundCount) : 0;
            if (avgSurroundCm < thisFenceHeightCm) return 0;
        }

        // 4. Fan scan — shelter score + weighted upwind snow surface Y
        FanResult fan = fanScan(world, x, groundY, z,
                upwindDX, upwindDZ, scanDistance, fanAngle, scanFromY);

        if (fan.shelterScore == 0 && fenceDriftCm == 0) return 0;

        // 5. Map shelter score to terrain drift modifier
        double terrainDriftCm = 0;
        if (fan.shelterScore > 0) {
            double fraction = Math.min(fan.shelterScore / shelterNorm, 1.0);
            fraction = Math.sqrt(fraction); // soften the curve
            terrainDriftCm = fraction * maxDriftCm * windStrength;
        } else if (fan.shelterScore < 0) {
            if (currentCm < scourThreshCm) {
                double fraction = Math.min(Math.abs(fan.shelterScore) / shelterNorm, 1.0);
                fraction = Math.sqrt(fraction);
                terrainDriftCm = -fraction * maxScourCm * windStrength;
            }
        }

        // 6. Scour neighbour override
        //    If this column would be scoured but has a significantly deeper neighbour,
        //    suppress the scour — the smoothing pass will fill it in instead.
        if (terrainDriftCm < 0 && fenceDriftCm == 0) {
            int[] ndx = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] ndz = {-1, -1, -1, 0, 0, 1, 1, 1};
            for (int ni = 0; ni < 8; ni++) {
                int nGroundY = SnowUtil.findGroundY(world, x + ndx[ni], z + ndz[ni], scanFromY);
                if (nGroundY == -1) continue;
                int nLayers = SnowUtil.measureDepthAboveGround(
                        world, x + ndx[ni], nGroundY, z + ndz[ni]);
                // If neighbour has more than 16 layers (2 blocks = 200cm) more than us, skip scour
                if (nLayers - currentLayers > 16) {
                    return 0;
                }
            }
        }

        // Combine terrain and fence drift
        double totalDriftCm = terrainDriftCm + fenceDriftCm;
        if (Math.abs(totalDriftCm) < 6.25) return 0;

        int deltaLayers = SnowUtil.cmToLayers(totalDriftCm);
        if (deltaLayers == 0) return 0;

        int newLayers = Math.max(0, currentLayers + deltaLayers);
        if (newLayers == currentLayers) return 0;

        // 7. Apply tapered height cap based on leeward slope angle
        //    The drift profile tapers from the crest outward at ~35 degrees.
        //    capY at distance d from crest = crestSurfaceY - d * tan(slopeAngle)
        //    This produces a natural ramp rather than a flat-topped block.

        // Slope angle in degrees — controls how far the drift extends horizontally.
        // 35 degrees = tan(35) ≈ 0.70 blocks drop per block of distance.
        // Lower angle = gentler slope = drift extends further out.
        final double SLOPE_TAN = Math.tan(Math.toRadians(slopeAngle));

        // Terrain cap: crest snow surface Y minus slope drop for this column's distance
        // crestDistance is how far upwind the crest is; this column is AT distance 0
        // so total distance from crest to this column = crestDistance (we are leeward of it)
        double terrainCapY;
        if (fan.crestDistance > 0) {
            // How far are we from the crest? We're at the column being processed,
            // the crest was detected at fan.crestDistance blocks upwind.
            // The slope drops from crest outward (downwind), so the cap at this column
            // is crestSurfaceY minus the slope drop over crestDistance.
            double slopeDrop = fan.crestDistance * SLOPE_TAN;
            terrainCapY = fan.crestSurfaceY - slopeDrop;
        } else {
            // No crest detected — fall back to weighted surface Y
            terrainCapY = fan.weightedSurfaceY;
        }

        double capY = Math.min(terrainCapY, fenceCapY);

        // Convert capY to max layers above this column's ground
        double capHeightBlocks = capY - groundY;
        int maxLayersFromCap   = (int) Math.floor(capHeightBlocks * 8);

        // Only cap when adding snow — never cap scour
        if (newLayers > currentLayers) {
            newLayers = Math.min(newLayers, Math.max(0, maxLayersFromCap));
        }

        if (newLayers == currentLayers) return 0;

        return SnowApplyCommand.writeSnow(world, x, groundY, z,
                currentLayers, newLayers, snowVariance, meltVariance, random);
    }
    /**
     * Phase 2 — Neighbour smoothing fill.
     * For every column, look at all 8 neighbours. If any neighbour has significantly
     * more snow, fill this column toward that neighbour proportionally.
     * This smooths sharp drift edges and fills scoured patches next to deep deposits.
     */
    private void runSmoothingPass(World world, int minX, int maxX, int minZ, int maxZ,
                                  int scanFromY, int colsPerTick,
                                  int snowVariance, int meltVariance,
                                  int REPOSE_LAYERS, double FILL_FRACTION,
                                  CommandSender sender) {

        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        new BukkitRunnable() {
            int x = minX, z = minZ;
            int processed = 0, filled = 0, unchanged = 0;
            int lastPct = 0;

            @Override
            public void run() {
                int done = 0;
                while (done < colsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        plugin.setDriftRunning(false);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Smoothing complete!"
                                + "  Filled: "    + String.format("%,d", filled)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int result = smoothColumn(world, x, z, scanFromY,
                            REPOSE_LAYERS, FILL_FRACTION, snowVariance, meltVariance);
                    if (result > 0) filled++;
                    else            unchanged++;

                    processed++; done++;
                    z++;
                    if (z > maxZ) { z = minZ; x++; }

                    int pct = (int)((processed / (double)totalColumns) * 100);
                    if (pct >= lastPct + 10) {
                        lastPct = pct - (pct % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] Smoothing " + lastPct + "% -- "
                                + String.format("%,d", processed) + " / "
                                + String.format("%,d", totalColumns));
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 1L);
    }

    /**
     * Smooth a single column by filling toward deeper neighbours.
     * Only ever adds snow — never removes. Scoured patches fill in naturally.
     */
    private int smoothColumn(World world, int x, int z, int scanFromY,
                             int reposeLayersThreshold, double fillFraction,
                             int snowVariance, int meltVariance) {

        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);

        // Check all 8 neighbours for deeper snow
        int maxNeighbourLayers = currentLayers;
        int[] dx = {-1, 0, 1, -1, 1, -1, 0, 1};
        int[] dz = {-1, -1, -1, 0, 0, 1, 1, 1};

        for (int i = 0; i < 8; i++) {
            int nx = x + dx[i];
            int nz = z + dz[i];
            int nGroundY = SnowUtil.findGroundY(world, nx, nz, scanFromY);
            if (nGroundY == -1) continue;
            int nLayers = SnowUtil.measureDepthAboveGround(world, nx, nGroundY, nz);
            if (nLayers > maxNeighbourLayers) maxNeighbourLayers = nLayers;
        }

        // Height difference between deepest neighbour and this column
        int diff = maxNeighbourLayers - currentLayers;

        // Only fill if difference exceeds angle of repose threshold
        if (diff <= reposeLayersThreshold) return 0;

        // Fill a fraction of the excess above the repose threshold
        int fillLayers = (int) Math.round((diff - reposeLayersThreshold) * fillFraction);
        if (fillLayers <= 0) return 0;

        int newLayers = currentLayers + fillLayers;

        return SnowApplyCommand.writeSnow(world, x, groundY, z,
                currentLayers, newLayers, snowVariance, meltVariance, random);
    }


}
