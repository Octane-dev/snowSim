package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class UpdateSnowCommand implements CommandExecutor {

    private final SnowSim plugin;
    private final Random random = new Random();

    // 1 layer = 12.5cm, 8 layers = 1 block = 100cm = 1 metre
    private static final double CM_PER_LAYER = 12.5;

    // Ground blocks that snow can settle on
    private static final Set<Material> GROUND_BLOCKS = EnumSet.of(
        Material.DIRT,
        Material.GRASS_BLOCK,
        Material.STONE,
        Material.ANDESITE,
        Material.COARSE_DIRT,
        Material.ROOTED_DIRT,
        Material.TUFF,
        Material.COBBLESTONE,
        Material.DEEPSLATE,
        Material.COBBLED_DEEPSLATE,
        Material.SNOW_BLOCK,
        Material.PACKED_ICE,
        Material.BLUE_ICE,
        Material.ICE,
        Material.WHITE_CONCRETE,
        Material.WHITE_CONCRETE_POWDER,
        Material.GRAVEL,
        Material.SAND
    );

    public UpdateSnowCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /updatesnow <depth_in_cm>");
            sender.sendMessage(ChatColor.YELLOW + "  Sets the target snow depth across the resort.");
            sender.sendMessage(ChatColor.YELLOW + "  1 block = 100cm = 1m.  1 layer = 12.5cm.");
            sender.sendMessage(ChatColor.YELLOW + "  Snow will be added or removed to reach the target.");
            sender.sendMessage(ChatColor.YELLOW + "  Examples:");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 50   -> 50cm (4 layers)");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 100  -> 1m (1 full snow block)");
            sender.sendMessage(ChatColor.YELLOW + "    /updatesnow 200  -> 2m (2 snow blocks)");
            return true;
        }

        double targetCm;
        try {
            targetCm = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number: " + args[0]);
            return true;
        }

        if (targetCm < 0) {
            sender.sendMessage(ChatColor.RED + "Depth must be 0 or greater.");
            return true;
        }

        // Convert cm to layers (round to nearest layer)
        int targetLayers = (int) Math.round(targetCm / CM_PER_LAYER);

        // Load config values
        String worldName  = plugin.getConfig().getString("world", "world");
        int x1            = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1            = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2            = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2            = plugin.getConfig().getInt("bounding-box.z2", 3047);
        int scanFromY     = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick   = plugin.getConfig().getInt("columns-per-tick", 1000);
        int snowVariance  = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance  = plugin.getConfig().getInt("cosmetic.melt-variance", 2);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found. Check your config.yml.");
            return true;
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Target depth: " + targetCm + "cm"
                + " = " + targetLayers + " layers"
                + " = " + String.format("%.2f", targetLayers / 8.0) + " snow blocks.");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Area: "
                + (maxX - minX + 1) + " x " + (maxZ - minZ + 1)
                + " = " + String.format("%,d", totalColumns) + " columns.");
        sender.sendMessage(ChatColor.GRAY + "Progress reported every 10%.");

        final int fTargetLayers = targetLayers;
        final int fScanFromY    = scanFromY;
        final int fColsPerTick  = colsPerTick;
        final int fSnowVariance = snowVariance;
        final int fMeltVariance = meltVariance;

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
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Done!"
                                + " Added: "     + String.format("%,d", added)
                                + "  Melted: "   + String.format("%,d", removed)
                                + "  Unchanged: " + String.format("%,d", unchanged));
                        return;
                    }

                    int result = processColumn(world, x, z, fScanFromY, fTargetLayers, fSnowVariance, fMeltVariance);
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
                                + String.format("%,d", processed) + " / " + String.format("%,d", totalColumns));
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    /**
     * Process one X/Z column.
     * Returns: +1 if snow was added, -1 if snow was removed, 0 if unchanged or skipped.
     */
    private int processColumn(World world, int x, int z, int scanFromY,
                              int targetLayers, int snowVariance, int meltVariance) {

        // 1. Find the surface
        int surfaceY = -1;
        boolean startsOnSnow = false;

        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW || mat == Material.SNOW_BLOCK) {
                surfaceY = y;
                startsOnSnow = true;
                break;
            } else if (GROUND_BLOCKS.contains(mat)) {
                surfaceY = y;
                startsOnSnow = false;
                break;
            }
        }

        if (surfaceY == -1) return 0;

        // 2. Measure current depth in layers
        int currentLayers = 0;
        int groundY = surfaceY;

        if (startsOnSnow) {
            Block topBlock = world.getBlockAt(x, surfaceY, z);
            int scanStart = surfaceY;

            if (topBlock.getType() == Material.SNOW) {
                Snow snowData = (Snow) topBlock.getBlockData();
                currentLayers += snowData.getLayers();
                scanStart--;
            }

            for (int y = scanStart; y >= world.getMinHeight(); y--) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType() == Material.SNOW_BLOCK) {
                    currentLayers += 8;
                } else {
                    groundY = y;
                    break;
                }
            }
        }

        if (currentLayers == targetLayers) return 0;

        boolean isAdding = targetLayers > currentLayers;

        // 3. Apply cosmetic variance to the top partial layer only
        int baseBlocks    = targetLayers / 8;
        int baseRemaining = targetLayers % 8;
        int topLayers;

        if (baseRemaining == 0) {
            topLayers = 0;
        } else if (isAdding) {
            // Snow falling: small variance +/- snowVariance layers
            int variance = (snowVariance > 0) ? random.nextInt(snowVariance * 2 + 1) - snowVariance : 0;
            topLayers = Math.max(1, Math.min(7, baseRemaining + variance));
        } else {
            // Melting: pick from outside a gap around target to look patchy
            if (meltVariance > 0) {
                int low  = Math.max(1, baseRemaining - meltVariance);
                int high = Math.min(7, baseRemaining + meltVariance);
                List<Integer> candidates = new ArrayList<>();
                for (int i = 1; i < low; i++)        candidates.add(i);
                for (int i = high + 1; i <= 7; i++)  candidates.add(i);
                topLayers = candidates.isEmpty() ? baseRemaining
                                                 : candidates.get(random.nextInt(candidates.size()));
            } else {
                topLayers = baseRemaining;
            }
        }

        // 4. Clear existing snow
        int oldSnowBlocks = currentLayers / 8;
        int oldTopLayers  = currentLayers % 8;
        int clearUpTo     = groundY + oldSnowBlocks + (oldTopLayers > 0 ? 1 : 0);

        for (int y = groundY + 1; y <= clearUpTo; y++) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (mat == Material.SNOW_BLOCK || mat == Material.SNOW) {
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
            }
        }

        if (targetLayers == 0) return currentLayers > 0 ? -1 : 0;

        // 5. Write new snow
        int writeY = groundY + 1;

        for (int i = 0; i < baseBlocks; i++) {
            Block b = world.getBlockAt(x, writeY + i, z);
            if (b.getType() != Material.AIR && b.getType() != Material.SNOW && b.getType() != Material.SNOW_BLOCK) {
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
            Snow snowData = (Snow) topSnow.getBlockData();
            snowData.setLayers(topLayers);
            topSnow.setBlockData(snowData, false);
        }

        return isAdding ? 1 : -1;
    }
}
