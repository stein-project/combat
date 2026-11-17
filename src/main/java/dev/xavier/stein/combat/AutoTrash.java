/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Stacks;

import java.util.Arrays;
import java.util.List;

/**
 * Auto Trash: throws away what the player marked as trash (blocks picked up in a fight, mob drops,
 * empty bottles and bowls...), one stack at a time, with the same drop click a player makes (Q over
 * the slot), so the bag keeps room for loot and armor.
 *
 * <p>Never what matters: an item on the Treasure List, the item in the hand, and the hotbar only
 * when the player asks ({@link CombatConfig#trashHotbar}). Item Lock does not hold the trash back:
 * it guards against the drop key pressed by mistake, and the trash list is the player's own choice
 * ({@link #dropping} lets its clicks through). It waits for the others: no drop with a chest open
 * (Auto Refill's, Auto Stash's or the player's), with Auto Armor acting or an item on the cursor.
 */
final class AutoTrash {

    /** {@code -Dsteincombat.debugItems=true}: every drop in the log. */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugItems");

    /** Ticks between two drops (200 ms). */
    private static final int GAP = 4;

    /** The list a new config starts with: what a fight leaves in the bag and nobody keeps. */
    static final List<String> DEFAULTS =
            Arrays.asList(
                    "minecraft:dirt",
                    "minecraft:cobblestone",
                    "minecraft:gravel",
                    "minecraft:sand",
                    "minecraft:netherrack",
                    "minecraft:rotten_flesh",
                    "minecraft:bone",
                    "minecraft:string",
                    "minecraft:spider_eye",
                    "minecraft:wheat_seeds",
                    "minecraft:poisonous_potato",
                    "minecraft:glass_bottle",
                    "minecraft:bowl");

    private final SteinCombat mod;
    private int wait;

    /** A drop of Auto Trash's is going out (Item Lock lets it through). */
    boolean dropping;

    AutoTrash(SteinCombat mod) {
        this.mod = mod;
    }

    /** Every tick: one trash stack out, if it is a quiet moment. */
    void tick() {
        CombatConfig c = mod.config;
        if (!c.autoTrash || c.trashItems.isEmpty() || Entities.self() == null) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (Inventory.isContainerOpen()
                || Inventory.cursor() != null
                || mod.armorSettling()
                || mod.autoRefill.busy()
                || mod.autoStash.busy()) {
            return;
        }
        int i = find(c);
        if (i < 0) {
            return;
        }
        int slot = Inventory.slotOf(i);
        String what = DEBUG ? Stacks.id(Inventory.stack(i)) : "";
        dropping = true;
        boolean dropped;
        try {
            dropped = slot >= 0 && Inventory.drop(slot, true);
        } finally {
            dropping = false;
        }
        if (dropped) {
            wait = GAP;
            if (DEBUG) {
                mod.log().info("[trash] dropped " + what + " from " + i);
            }
        }
    }

    /** The first trash stack: the bag, then (if asked) the hotbar, never the hand; -1 with none. */
    private int find(CombatConfig c) {
        for (int i = Inventory.MAIN_START; i < Inventory.ARMOR_START; i++) {
            if (trash(c, Inventory.stack(i))) {
                return i;
            }
        }
        if (c.trashHotbar) {
            int held = Inventory.selectedHotbar();
            for (int i = 0; i < Inventory.MAIN_START; i++) {
                if (i != held && trash(c, Inventory.stack(i))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Is the stack on the trash list, and nothing the player keeps? */
    boolean trash(CombatConfig c, Object s) {
        if (s == null || Valuables.isValuable(c, s)) {
            return false;
        }
        for (String id : c.trashItems) {
            if (Stacks.is(s, id)) {
                return true;
            }
        }
        return false;
    }

    /** The item id as the list keeps it ("minecraft:dirt"). */
    static String normalize(String id) {
        String t = id.trim().toLowerCase(java.util.Locale.ROOT);
        return t.isEmpty() || t.contains(":") ? t : "minecraft:" + t;
    }
}
