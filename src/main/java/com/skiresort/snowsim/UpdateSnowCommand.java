package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class UpdateSnowCommand implements CommandExecutor {

    private final SnowSim plugin;

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
            sender.sendMessage(ChatColor.YELLOW + "Usage: /updatesnow <layers_to_add>");
            sender.sendMessage(ChatColor.YELLOW + "  Layers are in snow layer units (8 layers = 1 snow block).");
            sender.sendMessage(ChatColor.YELLOW + "  Example: /updatesnow 8  →  adds 1 full snow block of depth everywhere.");
            return true;
        }

        int layersToAdd;
        try {
            layersToAdd = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number: " + args[0]);
            return true;
        }

        if (layersToAdd <= 0) {
            sender.sendMessage(ChatColor.RED + "Layers to add must be a positive number.");
            return true;
        }

        // Load config values
        String worldName = plugin.getConfig().getString("world", "world");
        int x1 = plugin.getConfig().getInt("bounding-box.x1", -22);
        int z1 = plugin.getConfig().getInt("bounding-box.z1", 301);
        int x2 = plugin.getConfig().getInt("bounding-box.x2", 2451);
        int z2 = plugin.getConfig().getInt("bounding-box.z2", 3047);
        int scanFromY = plugin.getConfig().getInt("scan-from-y", 700);
        int columnsPerTick = plugin.getConfig().getInt("columns-per-tick", 1000);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found. Check your config.yml.");
            return true;
        }

        // Normalise bounding box so x1<=x2, z1<=z2
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Starting snow update...");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Area: " + (maxX - minX + 1) + " x " + (maxZ - minZ + 1)
                + " = " + String.format("%,d", totalColumns) + " columns.");
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Adding " + layersToAdd + " layer(s) (" 
                + layersToAdd + "/8 = ~" + String.format("%.1f", layersToAdd / 8.0) + " snow blocks).");
        sender.sendMessage(ChatColor.GRAY + "This may take a while. Progress will be reported every 10%.");

        final int fLayersToAdd = layersToAdd;
        final int fScanFromY = scanFromY;
        final int fColumnsPerTick = columnsPerTick;

        // Build list of all columns to process
        // We process in chunks to avoid freezing the server
        new BukkitRunnable() {
            int x = minX;
            int z = minZ;
            int processed = 0;
            int modified = 0;
            int lastReportedPercent = 0;

            @Override
            public void run() {
                int doneThisTick = 0;

                while (doneThisTick < fColumnsPerTick) {
                    if (x > maxX) {
                        // Done
                        this.cancel();
                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Done! Processed "
                                + String.format("%,d", processed) + " columns, modified "
                                + String.format("%,d", modified) + " columns.");
                        return;
                    }

                    processColumn(world, x, z, fScanFromY, fLayersToAdd, result -> {
                        if (result) modified++;
                    });

                    processed++;
                    doneThisTick++;

                    // Advance position
                    z++;
                    if (z > maxZ) {
                        z = minZ;
                        x++;
                    }

                    // Report progress every 10%
                    int percent = (int) ((processed / (double) totalColumns) * 100);
                    if (percent >= lastReportedPercent + 10) {
                        lastReportedPercent = percent - (percent % 10);
                        sender.sendMessage(ChatColor.GRAY + "[SnowSim] " + lastReportedPercent + "% complete ("
                                + String.format("%,d", processed) + " / " + String.format("%,d", totalColumns) + ")");
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    /**
     * Process a single X/Z column:
     * 1. Scan down from scanFromY to find first snow or ground block
     * 2. Measure current depth in layer units
     * 3. Add layersToAdd
     * 4. Write back the result
     */
    private void processColumn(World world, int x, int z, int scanFromY, int layersToAdd, java.util.function.Consumer<Boolean> callback) {
        // Scan downward to find the surface
        int surfaceY = -1;
        boolean startsOnSnow = false;

        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material mat = block.getType();

            if (mat == Material.SNOW) {
                // Snow layers sitting on top - this is the top of the snow
                surfaceY = y;
                startsOnSnow = true;
                break;
            } else if (mat == Material.SNOW_BLOCK) {
                surfaceY = y;
                startsOnSnow = true;
                break;
            } else if (GROUND_BLOCKS.contains(mat)) {
                surfaceY = y;
                startsOnSnow = false;
                break;
            }
            // Air, leaves, etc. — keep scanning down
        }

        if (surfaceY == -1) {
            callback.accept(false);
            return;
        }

        // Measure current depth in layer units
        int currentLayers = 0;

        if (startsOnSnow) {
            Block topBlock = world.getBlockAt(x, surfaceY, z);

            if (topBlock.getType() == Material.SNOW) {
                // Count the partial layer on top
                Snow snowData = (Snow) topBlock.getBlockData();
                currentLayers += snowData.getLayers();
                surfaceY--; // look below for snow blocks
            }

            // Count solid snow blocks below
            for (int y = surfaceY; y >= world.getMinHeight(); y--) {
                Block b = world.getBlockAt(x, y, z);
                if (b.getType() == Material.SNOW_BLOCK) {
                    currentLayers += 8;
                } else {
                    // Hit ground or something else — this is the actual ground
                    surfaceY = y; // surfaceY is now the ground block
                    break;
                }
            }
        }
        // If startsOnSnow==false, surfaceY is the ground block and currentLayers=0

        int newLayers = currentLayers + layersToAdd;

        // Write back
        // Clear any existing snow above the ground block first
        // Ground block stays at surfaceY, snow goes from surfaceY+1 upward
        int writeY = surfaceY + 1;

        // Clear old snow above ground (in case we're overwriting existing snow)
        // We only need to clear as high as old snow reached
        int oldSnowBlocks = currentLayers / 8;
        int oldTopLayers = currentLayers % 8;
        int clearUpTo = surfaceY + oldSnowBlocks + (oldTopLayers > 0 ? 1 : 0);
        for (int y = surfaceY + 1; y <= clearUpTo; y++) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.SNOW_BLOCK || b.getType() == Material.SNOW) {
                b.setType(Material.AIR, false);
            }
        }

        // Write new snow — stop if we hit a non-air block (don't overwrite builds)
        int fullBlocks = newLayers / 8;
        int remainingLayers = newLayers % 8;

        for (int i = 0; i < fullBlocks; i++) {
            Block b = world.getBlockAt(x, writeY + i, z);
            if (b.getType() != Material.AIR && b.getType() != Material.SNOW && b.getType() != Material.SNOW_BLOCK) {
                // Hit something solid above ground — stop here
                callback.accept(true);
                return;
            }
            b.setType(Material.SNOW_BLOCK, false);
        }

        if (remainingLayers > 0) {
            Block topSnow = world.getBlockAt(x, writeY + fullBlocks, z);
            if (topSnow.getType() != Material.AIR && topSnow.getType() != Material.SNOW) {
                // Hit something solid — stop, don't place partial layer
                callback.accept(true);
                return;
            }
            topSnow.setType(Material.SNOW, false);
            Snow snowData = (Snow) topSnow.getBlockData();
            snowData.setLayers(remainingLayers);
            topSnow.setBlockData(snowData, false);
        }

        callback.accept(true);
    }
}
