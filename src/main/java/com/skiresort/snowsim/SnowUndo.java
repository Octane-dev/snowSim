package com.skiresort.snowsim;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 3-level undo stack for SnowSim operations.
 * Stores changed blocks as {x, y, z, oldBlockData} before each operation.
 * Works like WorldEdit undo — only changed blocks are stored, not the whole world.
 */
public class SnowUndo {

    public static final int MAX_LEVELS = 3;

    /** A single saved block state */
    public static class BlockSnapshot {
        final int x, y, z;
        final BlockData data;
        BlockSnapshot(int x, int y, int z, BlockData data) {
            this.x = x; this.y = y; this.z = z;
            this.data = data;
        }
    }

    /** One complete undo entry — label + list of changed blocks */
    public static class UndoEntry {
        final String label;
        final List<BlockSnapshot> snapshots;
        UndoEntry(String label) {
            this.label = label;
            this.snapshots = new ArrayList<>();
        }
        void add(int x, int y, int z, BlockData data) {
            snapshots.add(new BlockSnapshot(x, y, z, data));
        }
        int size() { return snapshots.size(); }
    }

    private final Deque<UndoEntry> stack = new ArrayDeque<>();
    private UndoEntry current = null;

    /**
     * Begin recording a new undo entry with the given label.
     * Call this before starting any block-modifying operation.
     */
    public void begin(String label) {
        current = new UndoEntry(label);
    }

    /**
     * Record the current state of a block before it is changed.
     * Only call this if a recording is active (begin() was called).
     */
    public void record(Block block) {
        if (current == null) return;
        current.add(block.getX(), block.getY(), block.getZ(), block.getBlockData().clone());
    }

    /**
     * Commit the current recording to the undo stack.
     * Trims stack to MAX_LEVELS.
     */
    public void commit() {
        if (current == null) return;
        if (current.size() == 0) { current = null; return; }
        stack.push(current);
        current = null;
        while (stack.size() > MAX_LEVELS) stack.pollLast();
    }

    /** Discard the current recording without committing (e.g. on error). */
    public void discard() { current = null; }

    /** Returns true if there is anything to undo. */
    public boolean canUndo() { return !stack.isEmpty(); }

    /** Returns how many undo levels are available. */
    public int levels() { return stack.size(); }

    /**
     * Pop the top undo entry and restore all blocks.
     * Returns the label of the undone operation, or null if nothing to undo.
     */
    public String undo(World world) {
        if (stack.isEmpty()) return null;
        UndoEntry entry = stack.pop();
        for (BlockSnapshot snap : entry.snapshots) {
            Block block = world.getBlockAt(snap.x, snap.y, snap.z);
            block.setBlockData(snap.data, false);
        }
        return entry.label;
    }

    /** Peek at what the next undo would restore, without doing it. */
    public String peekLabel() {
        return stack.isEmpty() ? null : stack.peek().label;
    }

    /** Describe the current stack for /snowreport or status messages. */
    public String status() {
        if (stack.isEmpty()) return "No undo history.";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (UndoEntry e : stack) {
            sb.append(i++).append(". ").append(e.label)
              .append(" (").append(String.format("%,d", e.size())).append(" blocks)");
            if (i <= stack.size()) sb.append("  ");
        }
        return sb.toString();
    }
}
