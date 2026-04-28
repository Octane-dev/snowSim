package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class SnowApplyCommand implements CommandExecutor {

    private final SnowSim plugin;
    private final Random random = new Random();

    public SnowApplyCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        // Check cached deltas exist
        int[] deltas = plugin.getCachedDeltas();
        if (deltas == null) {
            sender.sendMessage(ChatColor.RED + "[SnowSim] No sample data found. Run /snowsample first.");
            return true;
        }

        // Load config
        String worldName  = plugin.getConfig().getString("world", "world");
        int x1            = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1            = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2            = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2            = plugin.getConfig().getInt("bounding-box.z2", 3047);
        int scanFromY     = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick   = plugin.getConfig().getInt("columns-per-tick", 1000);
        int snowVariance  = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance  = plugin.getConfig().getInt("cosmetic.melt-variance", 2);
        double elevWeight = plugin.getConfig().getDouble("interpolation.elevation-weight", 0.7);
        double idwPower   = plugin.getConfig().getDouble("interpolation.idw-power", 2.0);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        // Rebuild ref points from cache
        RefPoint[] refs = new RefPoint[4];
        String[] names = {"base", "mid", "peak", "mammoth"};
        for (int i = 0; i < 4; i++) {
            int rx = plugin.getConfig().getInt("_cached.ref" + i + ".x");
            int ry = plugin.getConfig().getInt("_cached.ref" + i + ".y");
            int rz = plugin.getConfig().getInt("_cached.ref" + i + ".z");
            refs[i] = new RefPoint(names[i], rx, ry, rz, 0);
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Applying snow deltas across resort...");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] "
                + String.format("%,d", totalColumns) + " columns. "
                + "Elev weight: " + elevWeight + "  IDW power: " + idwPower);
        sender.sendMessage(ChatColor.GRAY + "Progress reported every 10%. Run /snowsample again anytime to recalculate.");

        final int[]      fDeltas       = deltas;
        final RefPoint[] fRefs         = refs;
        final int        fScanFromY    = scanFromY;
        final int        fColsPerTick  = colsPerTick;
        final int        fSnowVariance = snowVariance;
        final int        fMeltVariance = meltVariance;
        final double     fElevWeight   = elevWeight;
        final double     fIdwPower     = idwPower;

        new BukkitRunnable() {
            int x = minX;
            int z = minZ;
            int processed  = 0;
            int added      = 0;
            int removed    = 0;
            int unchanged  = 0;
            int lastReportedPercent = 0;

            @Override
            public void run() {
                int doneThisTick = 0;

                while (doneThisTick < fColsPerTick) {
                    if (x > maxX) {
                        this.cancel();
                        // Clear the cache once applied
                        plugin.setCachedDeltas(null);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Apply complete!"
                                + "  Added: "      + String.format("%,d", added)
                                + "  Melted: "     + String.format("%,d", removed)
                                + "  Unchanged: "  + String.format("%,d", unchanged));
                        return;
                    }

                    int result = processColumn(world, x, z, fScanFromY,
                            fRefs, fDeltas, fElevWeight, fIdwPower,
                            fSnowVariance, fMeltVariance);

                    if      (result > 0) added++;
                    else if (result < 0) removed++;
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

    private int processColumn(World world, int x, int z, int scanFromY,
                              RefPoint[] refs, int[] deltas,
                              double elevWeight, double idwPower,
                              int snowVariance, int meltVariance) {

        // 1. Find the surface (scan down for snow or ground)
        int surfaceY = -1;
        boolean startsOnSnow = false;

        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW || mat == Material.SNOW_BLOCK) {
                surfaceY = y;
                startsOnSnow = true;
                break;
            } else if (SnowUtil.GROUND_BLOCKS.contains(mat)) {
                surfaceY = y;
                startsOnSnow = false;
                break;
            }
        }

        if (surfaceY == -1) return 0;

        // 2. Measure current depth
        int currentLayers = 0;
        int groundY = surfaceY;

        if (startsOnSnow) {
            Block topBlock = world.getBlockAt(x, surfaceY, z);
            int scanStart = surfaceY;

            if (topBlock.getType() == Material.SNOW) {
                Snow sd = (Snow) topBlock.getBlockData();
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

        // 3. Interpolate the delta for this column's elevation
        double rawDelta = SnowUtil.interpolateDelta(x, groundY, z, refs, deltas, elevWeight, idwPower);
        int intDelta = (int) Math.round(rawDelta);

        if (intDelta == 0) return 0;

        int newLayers = Math.max(0, currentLayers + intDelta);
        if (newLayers == currentLayers) return 0;

        boolean isAdding = newLayers > currentLayers;

        // 4. Cosmetic variance on the top partial layer
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
