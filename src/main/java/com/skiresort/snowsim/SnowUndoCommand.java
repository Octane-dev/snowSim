package com.skiresort.snowsim;

import org.bukkit.*;
import org.bukkit.command.*;

public class SnowUndoCommand implements CommandExecutor {

    private final SnowSim plugin;

    public SnowUndoCommand(SnowSim plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("snowsim.update")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }

        SnowUndo undo = plugin.getUndo();

        if (!undo.canUndo()) {
            sender.sendMessage(ChatColor.YELLOW + "[SnowSim] Nothing to undo.");
            return true;
        }

        String worldName = plugin.getConfig().getString("world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World '" + worldName + "' not found.");
            return true;
        }

        String nextLabel = undo.peekLabel();
        sender.sendMessage(ChatColor.AQUA + "[SnowSim] Undoing: " + nextLabel + "...");

        String undone = undo.undo(world);

        sender.sendMessage(ChatColor.GREEN + "[SnowSim] Undone: " + undone);
        if (undo.canUndo()) {
            sender.sendMessage(ChatColor.GRAY + "[SnowSim] " + undo.levels()
                    + " undo level(s) remaining. Next: " + undo.peekLabel());
        } else {
            sender.sendMessage(ChatColor.GRAY + "[SnowSim] No more undo history.");
        }

        return true;
    }
}
