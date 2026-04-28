package com.skiresort.snowsim;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Snow;

import java.util.EnumSet;
import java.util.Set;

public class SnowUtil {

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
        Material.PACKED_ICE,
        Material.BLUE_ICE,
        Material.ICE,
        Material.WHITE_CONCRETE,
        Material.WHITE_CONCRETE_POWDER,
        Material.GRAVEL,
        Material.SAND
    );

    /**
     * Finds the ground block Y by scanning downward, ignoring snow entirely.
     * Returns -1 if no ground found.
     */
    public static int findGroundY(World world, int x, int z, int scanFromY) {
        for (int y = scanFromY; y >= world.getMinHeight(); y--) {
            Material mat = world.getBlockAt(x, y, z).getType();
            if (GROUND_BLOCKS.contains(mat)) {
                return y;
            }
            // Skip snow blocks and snow layers — keep scanning for true ground
        }
        return -1;
    }

    /**
     * Measures snow depth in layers above a known ground Y block.
     * Scans upward from groundY+1 counting snow blocks (x8) then partial layers.
     * This is the authoritative depth measurement — always pass the ground Y,
     * never guess from the surface scan.
     */
    public static int measureDepthAboveGround(World world, int x, int groundY, int z) {
        int layers = 0;
        int y = groundY + 1;

        // Count full snow blocks upward
        while (world.getBlockAt(x, y, z).getType() == Material.SNOW_BLOCK) {
            layers += 8;
            y++;
        }

        // Check for partial snow layer on top
        if (world.getBlockAt(x, y, z).getType() == Material.SNOW) {
            Snow sd = (Snow) world.getBlockAt(x, y, z).getBlockData();
            layers += sd.getLayers();
        }

        return layers;
    }

    /**
     * Convenience: find ground Y then measure depth.
     * Used by /snowreport where we already know the ground Y from config.
     */
    public static int measureDepthAt(World world, int x, int groundY, int z) {
        return measureDepthAboveGround(world, x, groundY, z);
    }

    public static int    cmToLayers(double cm)   { return (int) Math.round(cm / CM_PER_LAYER); }
    public static double layersToCm(int layers)  { return layers * CM_PER_LAYER; }

    /**
     * Interpolate the target delta (in layers) for a column at the given elevation,
     * using a blend of elevation-weighted and XZ-distance-weighted IDW.
     */
    public static double interpolateDelta(int colX, int colY, int colZ,
                                          RefPoint[] refs, int[] deltas,
                                          double elevWeight, double idwPower) {
        double totalWeight   = 0;
        double weightedDelta = 0;

        double elevRange = refs[refs.length - 1].y - refs[0].y;

        for (int i = 0; i < refs.length; i++) {
            RefPoint r = refs[i];

            double elevDist = Math.abs(colY - r.y) / Math.max(elevRange, 1.0);
            double xzDist   = Math.sqrt(Math.pow(colX - r.x, 2) + Math.pow(colZ - r.z, 2));
            double xzNorm   = xzDist / 3000.0;

            double blendedDist = elevWeight * elevDist + (1.0 - elevWeight) * xzNorm;
            if (blendedDist < 1e-6) return deltas[i];

            double weight = 1.0 / Math.pow(blendedDist, idwPower);
            totalWeight   += weight;
            weightedDelta += weight * deltas[i];
        }

        return totalWeight > 0 ? weightedDelta / totalWeight : 0;
    }
}
