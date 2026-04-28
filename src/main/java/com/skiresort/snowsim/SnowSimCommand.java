// package com.skiresort.snowsim;

// import org.bukkit.*;
// import org.bukkit.command.*;
// import org.bukkit.configuration.ConfigurationSection;

// import java.util.*;

// public class SnowSimCommand implements CommandExecutor {

//     private final SnowSim plugin;

//     public SnowSimCommand(SnowSim plugin) {
//         this.plugin = plugin;
//     }

//     @Override
//     public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

//         if (args.length == 0 || !args[0].equalsIgnoreCase("calibrate")) {
//             sender.sendMessage(ChatColor.YELLOW + "Usage: /snowsim calibrate");
//             return true;
//         }

//         sender.sendMessage(ChatColor.AQUA + "[SnowSim] Calibrating snow baseline...");

//         String worldName = plugin.getConfig().getString("world", "world");
//         int scanFromY    = plugin.getConfig().getInt("scan-from-y", 700);

//         World world = Bukkit.getWorld(worldName);
//         if (world == null) {
//             sender.sendMessage(ChatColor.RED + "World not found: " + worldName);
//             return true;
//         }

//         List<Map<?, ?>> points = plugin.getConfig().getMapList("reference-points");

//         if (points.isEmpty()) {
//             sender.sendMessage(ChatColor.RED + "No reference-points defined in config!");
//             return true;
//         }

//         List<Integer> samples = new ArrayList<>();

//         for (Map<?, ?> p : points) {
//             int x = (int) p.get("x");
//             int z = (int) p.get("z");

//             // Skip unloaded chunks (important)
//             if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

//             UpdateSnowCommand.SnowColumn col =
//                     UpdateSnowCommand.scanColumn(world, x, z, scanFromY);
            
//             int layers = col.layers;

//             samples.add(layers);
//         }

//         if (samples.isEmpty()) {
//             sender.sendMessage(ChatColor.RED + "No valid samples (chunks may be unloaded).");
//             return true;
//         }

//         // Sort for trimming
//         Collections.sort(samples);

//         // Trim top/bottom 20% (outlier removal)
//         int trim = samples.size() / 5;
//         List<Integer> trimmed = samples.subList(trim, samples.size() - trim);

//         int total = 0;
//         for (int val : trimmed) total += val;

//         int baseline = total / trimmed.size();

//         // Save baseline
//         plugin.getConfig().set("current-base-layers", baseline);
//         plugin.saveConfig();

//         double cm = baseline * 12.5;

//         sender.sendMessage(ChatColor.GREEN + "[SnowSim] Baseline calibrated:");
//         sender.sendMessage(ChatColor.GRAY + "  " + baseline + " layers");
//         sender.sendMessage(ChatColor.GRAY + "  " + String.format("%.1f", cm) + " cm");
//         sender.sendMessage(ChatColor.GRAY + "  " + String.format("%.2f", cm / 100.0) + " m");

//         return true;
//     }
// }
