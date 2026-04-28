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
        Material.SNOW_BLOCK,
        Material.PACKED_ICE,
        Material.BLUE_ICE,
        Material.ICE,
        Material.WHITE_CONCRETE,
        Material.WHITE_CONCRETE_POWDER,
        Material.GRAVEL,
        Material.SAND
    );

    /**
     * Count snow depth in layers above the given ground Y block.
     * Scans upward from groundY+1 counting snow blocks (x8) and snow layers.
     */
    public static int measureDepthAt(World world, int x, int groundY, int z) {
        int layers = 0;
        int y = groundY + 1;

        // Count full snow blocks
        while (true) {
            Block b = world.getBlockAt(x, y, z);
            if (b.getType() == Material.SNOW_BLOCK) {
                layers += 8;
                y++;
            } else {
                break;
            }
        }

        // Check for partial snow layer on top
        Block top = world.getBlockAt(x, y, z);
        if (top.getType() == Material.SNOW) {
            Snow sd = (Snow) top.getBlockData();
            layers += sd.getLayers();
        }

        return layers;
    }

    /**
     * Interpolate the target delta (in layers) for a column at the given elevation,
     * using a blend of elevation-weighted and XZ-distance-weighted IDW across reference points.
     *
     * elevWeight controls how much elevation drives the interpolation vs XZ proximity.
     * A value of 0.7 means 70% elevation, 30% XZ distance.
     */
    public static double interpolateDelta(int colX, int colY, int colZ,
                                          RefPoint[] refs, int[] deltas,
                                          double elevWeight, double idwPower) {
        double totalWeight = 0;
        double weightedDelta = 0;

        for (int i = 0; i < refs.length; i++) {
            RefPoint r = refs[i];

            // Elevation distance (normalised by total elevation range)
            double elevRange = refs[refs.length - 1].y - refs[0].y;
            double elevDist = Math.abs(colY - r.y) / Math.max(elevRange, 1.0);

            // XZ spatial distance (normalised by diagonal of bounding box — rough scale)
            double xzDist = Math.sqrt(Math.pow(colX - r.x, 2) + Math.pow(colZ - r.z, 2));
            double xzNorm = xzDist / 3000.0; // normalise to rough resort scale

            // Blend the two distances
            double blendedDist = elevWeight * elevDist + (1.0 - elevWeight) * xzNorm;

            // Avoid division by zero at exact reference location
            if (blendedDist < 1e-6) return deltas[i];

            double weight = 1.0 / Math.pow(blendedDist, idwPower);
            totalWeight += weight;
            weightedDelta += weight * deltas[i];
        }

        return totalWeight > 0 ? weightedDelta / totalWeight : 0;
    }

    public static int cmToLayers(double cm) {
        return (int) Math.round(cm / CM_PER_LAYER);
    }

    public static double layersToCm(int layers) {
        return layers * CM_PER_LAYER;
    }
}
