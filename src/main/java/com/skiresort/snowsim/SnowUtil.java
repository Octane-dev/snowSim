package com.skiresort.snowsim;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;

import java.util.EnumSet;
import java.util.Set;

public class SnowUtil {

    public static final double CM_PER_LAYER = 12.5;

    // True ground blocks — snow settles on these
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
        Material.PACKED_ICE,
        Material.BLUE_ICE,
        Material.ICE,
        Material.WHITE_CONCRETE,
        Material.WHITE_CONCRETE_POWDER,
        Material.GRAVEL,
        Material.SAND
    );

    // Snow fence blocks — treated as raised barriers for drift calculation
    public static final Set<Material> FENCE_BLOCKS = EnumSet.of(
        Material.OAK_FENCE
    );

    // Water blocks — snow can cap these if the block above is air
    public static final Set<Material> WATER_BLOCKS = EnumSet.of(
        Material.WATER
    );

    /**
     * Finds the ground block Y scanning downward, ignoring snow entirely.
     * Also treats OAK_FENCE tops and water surfaces as valid ground.
     * Returns -1 if nothing found.
     */
    public static int findGroundY(World world, int x, int z, int scanFromY) {
        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (GROUND_BLOCKS.contains(mat)) return y;
            if (FENCE_BLOCKS.contains(mat))  return y; // top of fence = valid surface
            if (WATER_BLOCKS.contains(mat)) {
                // Only valid if block above is air (snow can cap it)
                Material above = world.getBlockAt(x, y + 1, z).getType();
                if (above == Material.AIR || above == Material.SNOW || above == Material.SNOW_BLOCK) {
                    return y;
                }
            }
            // Skip SNOW_BLOCK and SNOW — keep scanning for true ground
        }
        return -1;
    }

    /**
     * Finds terrain-only ground Y, ignoring snow AND fences AND water.
     * Used by drift shelter scan so fences/snow don't masquerade as terrain.
     */
    public static int findTerrainY(World world, int x, int z, int scanFromY) {
        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            if (GROUND_BLOCKS.contains(world.getBlockAt(x, y, z).getType())) return y;
        }
        return -1;
    }

    /**
     * Measures snow depth in layers above a known ground Y block.
     * Scans upward counting snow blocks (x8) then partial layer on top.
     */
    public static int measureDepthAboveGround(World world, int x, int groundY, int z) {
        int layers = 0;
        int y = groundY + 1;

        while (world.getBlockAt(x, y, z).getType() == Material.SNOW_BLOCK) {
            layers += 8;
            y++;
        }

        if (world.getBlockAt(x, y, z).getType() == Material.SNOW) {
            Snow sd = (Snow) world.getBlockAt(x, y, z).getBlockData();
            layers += sd.getLayers();
        }

        return layers;
    }

    public static int    measureDepthAt(World world, int x, int groundY, int z) {
        return measureDepthAboveGround(world, x, groundY, z);
    }

    public static int    cmToLayers(double cm)  { return (int) Math.round(cm / CM_PER_LAYER); }
    public static double layersToCm(int layers) { return layers * CM_PER_LAYER; }

    /**
     * Interpolate the target delta (in layers) for a column at the given elevation,
     * using a blend of elevation-weighted and XZ-distance-weighted IDW.
     */
    public static double interpolateDelta(int colX, int colY, int colZ,
                                          RefPoint[] refs, int[] deltas,
                                          double elevWeight, double idwPower) {
        double totalWeight = 0, weightedDelta = 0;
        double elevRange = refs[refs.length - 1].y - refs[0].y;

        for (int i = 0; i < refs.length; i++) {
            RefPoint r = refs[i];
            double elevDist    = Math.abs(colY - r.y) / Math.max(elevRange, 1.0);
            double xzDist      = Math.sqrt(Math.pow(colX - r.x, 2) + Math.pow(colZ - r.z, 2));
            double xzNorm      = xzDist / 3000.0;
            double blendedDist = elevWeight * elevDist + (1.0 - elevWeight) * xzNorm;
            if (blendedDist < 1e-6) return deltas[i];
            double weight   = 1.0 / Math.pow(blendedDist, idwPower);
            totalWeight    += weight;
            weightedDelta  += weight * deltas[i];
        }
        return totalWeight > 0 ? weightedDelta / totalWeight : 0;
    }
}
