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

    public static final double CM_PER_LAYER = 12.5;

    public static final Set<Material> GROUND_BLOCKS = EnumSet.of(
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
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /updatesnow <depth_in_cm>");
            return true;
        }

        double targetCm;
        try {
            targetCm = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid number.");
            return true;
        }

        if (targetCm < 0) {
            sender.sendMessage(ChatColor.RED + "Depth must be >= 0.");
            return true;
        }

        int targetLayers = (int) Math.round(targetCm / CM_PER_LAYER);

        int baseLayers = plugin.getConfig().getInt("current-base-layers", -1);
        if (baseLayers == -1) {
            sender.sendMessage(ChatColor.RED + "Snow baseline not calibrated!");
            sender.sendMessage(ChatColor.YELLOW + "Run /snowsim calibrate first.");
            return true;
        }

        int deltaLayers = targetLayers - baseLayers;

        String worldName  = plugin.getConfig().getString("world", "world");
        int x1            = plugin.getConfig().getInt("bounding-box.x1");
        int z1            = plugin.getConfig().getInt("bounding-box.z1");
        int x2            = plugin.getConfig().getInt("bounding-box.x2");
        int z2            = plugin.getConfig().getInt("bounding-box.z2");
        int scanFromY     = plugin.getConfig().getInt("scan-from-y", 700);
        int colsPerTick   = plugin.getConfig().getInt("columns-per-tick", 1000);
        int snowVariance  = plugin.getConfig().getInt("cosmetic.snow-variance", 1);
        int meltVariance  = plugin.getConfig().getInt("cosmetic.melt-variance", 2);

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World not found.");
            return true;
        }

        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);
        int totalColumns = (maxX - minX + 1) * (maxZ - minZ + 1);

        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Applying delta: "
                + deltaLayers + " layers (" + String.format("%.1f", deltaLayers * CM_PER_LAYER) + "cm)");

        final int fDelta        = deltaLayers;
        final int fScanFromY    = scanFromY;
        final int fColsPerTick  = colsPerTick;
        final int fSnowVariance = snowVariance;
        final int fMeltVariance = meltVariance;
        final int fBaseLayers   = baseLayers;

        new BukkitRunnable() {

            int x = minX;
            int z = minZ;

            @Override
            public void run() {

                int done = 0;

                while (done < fColsPerTick) {

                    if (x > maxX) {
                        this.cancel();

                        int newBase = fBaseLayers + fDelta;
                        plugin.getConfig().set("current-base-layers", newBase);
                        plugin.saveConfig();

                        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Done.");
                        return;
                    }

                    processColumn(world, x, z, fScanFromY, fDelta, fSnowVariance, fMeltVariance);

                    done++;

                    z++;
                    if (z > maxZ) {
                        z = minZ;
                        x++;
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return true;
    }

    public static class SnowColumn {
        public final int groundY;
        public final int layers;

        public SnowColumn(int groundY, int layers) {
            this.groundY = groundY;
            this.layers = layers;
        }
    }

    public static SnowColumn scanColumn(World world, int x, int z, int scanFromY) {

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

        if (surfaceY == -1) {
            return new SnowColumn(world.getMinHeight(), 0);
        }

        int layers = 0;
        int groundY = surfaceY;

        if (startsOnSnow) {

            Block top = world.getBlockAt(x, surfaceY, z);

            if (top.getType() == Material.SNOW) {
                layers += ((Snow) top.getBlockData()).getLayers();
                surfaceY--;
            }

            for (int y = surfaceY; y >= world.getMinHeight(); y--) {
                Block b = world.getBlockAt(x, y, z);

                if (b.getType() == Material.SNOW_BLOCK) {
                    layers += 8;
                } else {
                    groundY = y;
                    break;
                }
            }
        }

        return new SnowColumn(groundY, layers);
    }

    private int processColumn(World world, int x, int z, int scanFromY,
                               int deltaLayers, int snowVariance, int meltVariance) {

        SnowColumn col = scanColumn(world, x, z, scanFromY);

        int currentLayers = col.layers;
        int groundY = col.groundY;

        int targetLayers = Math.max(0, currentLayers + deltaLayers);

        if (currentLayers == targetLayers) return 0;

        boolean isAdding = targetLayers > currentLayers;

        int diff = targetLayers - currentLayers;

        // REMOVE
        if (diff < 0) {

            int toRemove = -diff;

            for (int y = world.getMaxHeight(); y > groundY && toRemove > 0; y--) {

                Block b = world.getBlockAt(x, y, z);

                if (b.getType() == Material.SNOW) {
                    Snow s = (Snow) b.getBlockData();
                    int layers = s.getLayers();

                    if (layers > toRemove) {
                        s.setLayers(layers - toRemove);
                        b.setBlockData(s, false);
                        return -1;
                    } else {
                        b.setType(Material.AIR, false);
                        toRemove -= layers;
                    }
                }

                if (b.getType() == Material.SNOW_BLOCK) {
                    b.setType(Material.AIR, false);
                    toRemove -= 8;
                }
            }

            return -1;
        }

        // ADD
        int toAdd = diff;

        for (int y = groundY + 1; y < world.getMaxHeight() && toAdd > 0; y++) {

            Block b = world.getBlockAt(x, y, z);

            if (b.getType() != Material.AIR &&
                b.getType() != Material.SNOW &&
                b.getType() != Material.SNOW_BLOCK) {
                continue;
            }

            if (toAdd >= 8) {
                b.setType(Material.SNOW_BLOCK, false);
                toAdd -= 8;
            } else {
                b.setType(Material.SNOW, false);
                Snow s = (Snow) b.getBlockData();

                int add = Math.max(1, Math.min(7, toAdd));

                if (y == groundY + 1 && snowVariance > 0) {
                    add += random.nextInt(snowVariance * 2 + 1) - snowVariance;
                    add = Math.max(1, Math.min(7, add));
                }

                s.setLayers(add);
                b.setBlockData(s, false);

                toAdd -= add;
            }
        }

        return isAdding ? 1 : -1;
    }
}
