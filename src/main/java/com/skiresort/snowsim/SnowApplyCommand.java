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

        int[] deltas = plugin.getCachedDeltas();
        if (deltas == null) {
            sender.sendMessage(ChatColor.RED + "[SnowSim] No sample data found. Run /snowsample first.");
            return true;
        }

        String worldName  = plugin.getConfig().getString("world", "world");
        int x1            = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1            = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2            = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2            = plugin.getConfig().getInt("bounding-box.z2", -3047);
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

        RefPoint[] refs = new RefPoint[4];
        String[] names = {"base", "mid", "peak", "mammoth"};
        for (int i = 0; i < 4; i++) {
            int rx = plugin.getConfig().getInt("_cached.ref" + i + ".x");
            int ry = plugin.getConfig().getInt("_cached.ref" + i + ".y");
            int rz = plugin.getConfig().getInt("_cached.ref" + i + ".z");
            refs[i] = new RefPoint(names[i], rx, ry, rz, 0);
        }

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Applying snow deltas across resort...");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] "
                + String.format("%,d", totalColumns) + " columns.");
        sender.sendMessage(ChatColor.GRAY + "Progress reported every 10%.");

        final int[]      fDeltas      = deltas;
        final RefPoint[] fRefs        = refs;
        final int        fScanFromY   = scanFromY;
        final int        fColsPerTick = colsPerTick;
        final int        fSnowVar     = snowVariance;
        final int        fMeltVar     = meltVariance;
        final double     fElevWeight  = elevWeight;
        final double     fIdwPower    = idwPower;

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
                        plugin.setCachedDeltas(null);
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Apply complete!"
                                + "  Added: "     + String.format("%,d", added)
                                + "  Melted: "    + String.format("%,d", removed)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int result = processColumn(world, x, z, fScanFromY,
                            fRefs, fDeltas, fElevWeight, fIdwPower, fSnowVar, fMeltVar);
                    if      (result > 0) added++;
                    else if (result < 0) removed++;
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
                              RefPoint[] refs, int[] deltas,
                              double elevWeight, double idwPower,
                              int snowVariance, int meltVariance) {

        // 1. Find true ground (ignores snow)
        int groundY = SnowUtil.findGroundY(world, x, z, scanFromY);
        if (groundY == -1) return 0;

        // 2. Measure depth upward from ground
        int currentLayers = SnowUtil.measureDepthAboveGround(world, x, groundY, z);

        // 3. Interpolate delta for this column's elevation
        double rawDelta = SnowUtil.interpolateDelta(x, groundY, z, refs, deltas, elevWeight, idwPower);
        int intDelta = (int) Math.round(rawDelta);
        if (intDelta == 0) return 0;

        int newLayers = Math.max(0, currentLayers + intDelta);
        if (newLayers == currentLayers) return 0;

        return writeSnow(world, x, groundY, z, currentLayers, newLayers, snowVariance, meltVariance);
    }

    /**
     * Shared snow writing logic — clears old snow, writes new snow with cosmetic variance.
     * Returns +1 added, -1 removed, 0 unchanged.
     */
    static int writeSnow(World world, int x, int groundY, int z,
                         int currentLayers, int newLayers,
                         int snowVariance, int meltVariance) {
        return writeSnow(world, x, groundY, z, currentLayers, newLayers, snowVariance, meltVariance, new Random());
    }

    static int writeSnow(World world, int x, int groundY, int z,
                         int currentLayers, int newLayers,
                         int snowVariance, int meltVariance, Random random) {

        boolean isAdding = newLayers > currentLayers;

        // Cosmetic variance on top partial layer only
        int baseBlocks    = newLayers / 8;
        int baseRemaining = newLayers % 8;
        int topLayers;

        if (baseRemaining == 0) {
            topLayers = 0;
        } else if (isAdding) {
            int v = (snowVariance > 0) ? random.nextInt(snowVariance * 2 + 1) - snowVariance : 0;
            topLayers = Math.max(1, Math.min(7, baseRemaining + v));
        } else {
            if (meltVariance > 0) {
                int low  = Math.max(1, baseRemaining - meltVariance);
                int high = Math.min(7, baseRemaining + meltVariance);
                List<Integer> c = new ArrayList<>();
                for (int i = 1;      i < low;  i++) c.add(i);
                for (int i = high+1; i <= 7;   i++) c.add(i);
                topLayers = c.isEmpty() ? baseRemaining : c.get(random.nextInt(c.size()));
            } else {
                topLayers = baseRemaining;
            }
        }

        // Clear all snow above ground by scanning upward until actual air.
        // This handles cosmetic variance layers sitting higher than calculated depth,
        // preventing remnant layer artefacts on scour passes.
        for (int y = groundY + 1; y <= groundY + 200; y++) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW_BLOCK || mat == Material.SNOW) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            } else {
                break; // hit air or non-snow, stop clearing
            }
        }

        if (newLayers == 0) return -1;

        // Write new snow
        int writeY = groundY + 1;
        for (int i = 0; i < baseBlocks; i++) {
            Block b = world.getBlockAt(x, writeY + i, z);
            if (b.getType() != Material.AIR && b.getType() != Material.SNOW
                    && b.getType() != Material.SNOW_BLOCK) return isAdding ? 1 : -1;
            b.setType(Material.SNOW_BLOCK, false);
        }

        if (topLayers > 0) {
            Block top = world.getBlockAt(x, writeY + baseBlocks, z);
            if (top.getType() != Material.AIR && top.getType() != Material.SNOW)
                return isAdding ? 1 : -1;
            top.setType(Material.SNOW, false);
            Snow sd = (Snow) top.getBlockData();
            sd.setLayers(topLayers);
            top.setBlockData(sd, false);
        }

        return isAdding ? 1 : -1;
    }
}
