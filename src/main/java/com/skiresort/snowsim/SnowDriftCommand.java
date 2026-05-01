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
                        // Smoothing pass temporarily disabled
                        // int totalIters = plugin.getConfig().getInt("drift.smoothing-iterations", 3);
                        // sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting smoothing pass (1/" + totalIters + ")...");
                        // runSmoothingPass(world, minX, maxX, minZ, maxZ,
                        //         fScanFromY, colsPerTick, fSnowVariance, fMeltVariance,
                        //         1, totalIters, sender);
                        plugin.setDriftRunning(false);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Drift complete!");
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
     * Context-aware omni-directional shelter scan.
     *
     * Casts rays in all directions (360°), not just upwind.
     * Each ray contributes to a composite shelter score weighted by:
     *   - Directional alignment: upwind rays count fully, downwind rays count at
     *     reduced weight (wind_bias). This means existing downwind drifts can
     *     shelter a column — the drift feeds back into itself.
     *   - Snow surface height (terrain + snow), not bare terrain only.
     *     Growing drifts become part of the shelter landscape.
     *   - Inverse distance: closer features shelter more.
     *
     * Crest detection finds the nearest significant height gain across all rays
     * (biased upwind) for the slope taper cap.
     */
    private FanResult fanScan(World world, int x, int groundY, int z,
                              double upwindDX, double upwindDZ,
                              int scanDistance, int fanAngle, int scanFromY) {

        // How many rays to cast total across 360 degrees
        final int TOTAL_RAYS  = 16;
        // Weight applied to downwind rays vs upwind rays (0=ignore downwind, 1=equal weight)
        // 0.35 means downwind shelter counts for 35% — enough for drift feedback without
        // removing wind directionality entirely
        final double DOWNWIND_BIAS = 0.35;
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

            // Dot product with upwind vector: 1.0 = fully upwind, -1.0 = fully downwind
            double dot = rayDX * upwindDX + rayDZ * upwindDZ;
            // Map dot [-1,1] to directional weight:
            // upwind (dot=1) → weight 1.0, downwind (dot=-1) → weight DOWNWIND_BIAS
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

                // Use snow surface for shelter (so existing drifts contribute)
                int nGroundY = SnowUtil.findGroundY(world, sx, sz, scanFromY);
                if (nGroundY == -1) continue;
                int nSnowLayers  = SnowUtil.measureDepthAboveGround(world, sx, nGroundY, sz);
                double nSurfaceY = nGroundY + (nSnowLayers / 8.0);

                double distWeight  = 1.0 / dist;
                // Height diff vs our ground — positive = neighbour surface is higher = shelter
                double heightDiff  = nSurfaceY - groundY;
                rayContrib    += heightDiff * distWeight;
                raySurfContrib += nSurfaceY * distWeight;
                rayWeightSum  += distWeight;

                // Crest: only detected on upwind-biased rays (dot > 0.5)
                if (dot > 0.5 && rayCrestDist < 0 && heightDiff >= CREST_THRESHOLD) {
                    rayCrestSurfaceY = nSurfaceY;
                    rayCrestDist     = dist;
                }
            }

            if (rayWeightSum > 0) {
                double rayScore = (rayContrib / rayWeightSum) * dirWeight;
                shelterSum   += rayScore;
                totalWeight  += dirWeight;
                // Only use upwind/crosswind rays (dot > 0) for the surface height cap.
                // This prevents the terrain behind you inflating the cap on windward faces.
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

        double avgCrestSurfaceY = crestRayCount > 0
                ? crestSurfaceYSum / crestRayCount : groundY;
        double avgCrestDist     = crestRayCount > 0
                ? crestDistSum / crestRayCount : 0;

        double avgSurfaceY = surfaceWeight > 0 ? surfaceYSum / surfaceWeight : groundY;

        return new FanResult(
            shelterSum   / totalWeight,
            avgSurfaceY,
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

        // Building edge guard — check immediate 3x3 neighbours for large ground Y drops.
        // If any neighbour's ground is >10 blocks different, this is a building/cliff edge.
        // Skip drift placement entirely to avoid mega artifacts.
        for (int ex = -1; ex <= 1; ex++) {
            for (int ez = -1; ez <= 1; ez++) {
                if (ex == 0 && ez == 0) continue;
                int eGroundY = SnowUtil.findGroundY(world, x + ex, z + ez, scanFromY);
                if (eGroundY != -1 && Math.abs(eGroundY - groundY) > 10) return 0;
            }
        }

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
        //    If this column would be scoured, check if any neighbour has a significantly
        //    higher SNOW SURFACE (ground + snow depth). A deep drift wall next to this
        //    column physically blocks the wind regardless of terrain slope direction —
        //    so we compare snow surfaces, not ground Y.
        if (terrainDriftCm < 0 && fenceDriftCm == 0) {
            // This column's snow surface Y
            double thisSurfaceY = groundY + (currentLayers / 8.0);

            int[] ndx = {-1, 0, 1, -1, 1, -1, 0, 1};
            int[] ndz = {-1, -1, -1, 0, 0, 1, 1, 1};
            for (int ni = 0; ni < 8; ni++) {
                int nGroundY = SnowUtil.findGroundY(world, x + ndx[ni], z + ndz[ni], scanFromY);
                if (nGroundY == -1) continue;
                int nLayers = SnowUtil.measureDepthAboveGround(
                        world, x + ndx[ni], nGroundY, z + ndz[ni]);
                double nSurfaceY = nGroundY + (nLayers / 8.0);
                // If neighbour snow surface is more than 2 blocks above this column's
                // snow surface, the drift wall is sheltering this column — suppress scour
                if (nSurfaceY - thisSurfaceY > 2.0) {
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
        final double SLOPE_TAN = Math.tan(Math.toRadians(slopeAngle));

        double terrainCapY;
        if (fan.crestDistance > 0) {
            // Leeward of a ridge — taper down from the crest snow surface
            terrainCapY = fan.crestSurfaceY - (fan.crestDistance * SLOPE_TAN);
        } else {
            // Windward or flat — cap at the weighted upwind snow surface height
            // (prevents windward faces accumulating above incoming flow level)
            terrainCapY = fan.weightedSurfaceY;
        }

        double capY = Math.min(terrainCapY, fenceCapY);
        int maxLayersFromCap = (int) Math.floor((capY - groundY) * 8);

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
                                  int currentIter, int totalIters,
                                  CommandSender sender) {

        final int    REPOSE_LAYERS = plugin.getConfig().getInt("drift.smoothing-repose-layers", 5);
        final double FILL_FRACTION = plugin.getConfig().getDouble("drift.smoothing-fill-fraction", 0.7);
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
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Smoothing pass " + currentIter + "/" + totalIters
                                + " complete!  Filled: " + String.format("%,d", filled)
                                + "  Unchanged: "        + String.format("%,d", unchanged));

                        if (currentIter < totalIters) {
                            // Chain next smoothing iteration
                            sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting smoothing pass ("
                                    + (currentIter + 1) + "/" + totalIters + ")...");
                            runSmoothingPass(world, minX, maxX, minZ, maxZ,
                                    scanFromY, colsPerTick, snowVariance, meltVariance,
                                    currentIter + 1, totalIters, sender);
                        } else {
                            plugin.setDriftRunning(false);
                            sender.sendMessage(ChatColor.GREEN + "[SnowSim] All smoothing passes done.");
                        }
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
     * Searches a 5x5 radius for the deepest nearby snow — allows fills to
     * propagate across wide gullies rather than just immediate neighbours.
     * Also guards against building edge artifacts by skipping columns where
     * ground Y differs too much from neighbours (building edges).
     * Only ever adds snow — never removes.
     */
    private int smoothColumn(World world, int x, int z, int scanFromY,
                             int reposeLayersThreshold, double fillFraction,
                             int snowVariance, int meltVariance) {

        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);

        // 5x5 radius search for deepest neighbour snow
        // Also track max ground Y difference to detect building edges
        int maxNeighbourLayers = currentLayers;
        int maxGroundYDiff     = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = x + dx, nz = z + dz;
                int nGroundY = SnowUtil.findGroundY(world, nx, nz, scanFromY);
                if (nGroundY == -1) continue;

                // Track ground Y difference — large diff = building edge
                int groundDiff = Math.abs(nGroundY - groundY);
                if (groundDiff > maxGroundYDiff) maxGroundYDiff = groundDiff;

                int nLayers = SnowUtil.measureDepthAboveGround(world, nx, nGroundY, nz);

                // Compare snow SURFACES (ground + snow), not bare ground.
                // A deep drift wall on flat or lower ground should still fill into
                // an adjacent hollow — the surface height is what matters physically.
                double nSurfaceY    = nGroundY + (nLayers / 8.0);
                double thisSurfaceY = groundY  + (currentLayers / 8.0);

                // Only fill if neighbour snow surface is higher than ours
                // This prevents snow being pushed uphill where the surface is already higher
                if (nSurfaceY <= thisSurfaceY) continue;

                if (nLayers > maxNeighbourLayers) maxNeighbourLayers = nLayers;
            }
        }

        // Building edge guard — if ground drops >10 blocks to any neighbour within 5x5,
        // this is likely a building/cliff edge. Skip to avoid mega artifacts.
        if (maxGroundYDiff > 10) return 0;

        int diff = maxNeighbourLayers - currentLayers;
        if (diff <= reposeLayersThreshold) return 0;

        int fillLayers = (int) Math.round((diff - reposeLayersThreshold) * fillFraction);
        if (fillLayers <= 0) return 0;

        int newLayers = currentLayers + fillLayers;

        return SnowApplyCommand.writeSnow(world, x, groundY, z,
                currentLayers, newLayers, snowVariance, meltVariance, random);
    }


}
