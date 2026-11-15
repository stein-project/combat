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
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Stacks;

import java.util.List;

/**
 * Loot Swap: with something better on the ground nearby and the inventory full, drops what is worth
 * the least to make room for it — the item is then picked up by walking over it, as always.
 *
 * <p>Better is a valuable item (the Valuable Items list) or a piece of armor or a weapon worth more
 * than what would be dropped. What may be dropped: ordinary items first (blocks and the like, not
 * what a player fights with), then armor at or below the chosen Protection level and below the
 * chosen durability. Never: anything on the Valuable Items list, the worn armor, the best spare of
 * each piece (Auto Armor's next one), the item in the hand.
 *
 * <p>Only the item nearest to the player makes room (the game gives the freed slot to the closest
 * item), and at most twice per item, so walking away from it does not empty the bag. With others
 * ({@link Coordinator}): it waits for everyone ahead of it, only acts with no screen open, and
 * tells Item Lock about each drop it makes.
 */
final class LootSwap {

    /** Ticks after a drop during which it counts as acting (the drop's slot is settling). */
    private static final int BUSY_TICKS = 4;

    /** Ticks between two drops: time for the item to be picked up into the freed slot. */
    private static final int COOLDOWN = 15;

    /** Drops at most per item on the ground (the second if something else took the slot). */
    private static final int MAX_ATTEMPTS = 2;

    private final SteinCombat mod;
    private int busy;
    private int cooldown;

    LootSwap(SteinCombat mod) {
        this.mod = mod;
    }

    boolean busy() {
        return busy > 0;
    }

    void tick() {
        if (busy > 0) {
            busy--;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        CombatConfig c = mod.config;
        Object me = Entities.self();
        if (!c.lootSwap
                || me == null
                || Screens.isOpen()
                || Inventory.cursor() != null
                || mod.coordinator.ahead(Coordinator.SWAP)
                || ArmorSlots.freeSlot() >= 0) {
            return;
        }
        // The item nearest to the player is the one the freed slot goes to (the game picks up the
        // closest first): only that one, when it is worth it, makes room — never one behind
        // another item that would take the slot instead.
        GroundItems.Item target = null;
        double nearest = Double.MAX_VALUE;
        List<GroundItems.Item> items = mod.groundItems.items();
        for (int i = 0, n = items.size(); i < n; i++) {
            GroundItems.Item it = items.get(i);
            double d = Entities.distance(me, it.entity);
            if (d < nearest) {
                nearest = d;
                target = it;
            }
        }
        if (target == null
                || nearest > c.swapRadius
                || target.attempts >= MAX_ATTEMPTS
                || target.rule == null && !Stacks.isDamageable(target.stack)) {
            return; // an ordinary item is never worth a drop
        }
        long targetValue = Valuables.value(c, target.stack);
        int index = cheapest(c, targetValue);
        if (index < 0) {
            target.attempts = MAX_ATTEMPTS; // nothing that may go is worth less: give up on it
            return;
        }
        int slot = Inventory.slotOf(index);
        Object dropped = Inventory.stack(index);
        mod.itemLock.allowThrow();
        if (slot < 0 || !Inventory.drop(slot, true)) {
            return;
        }
        target.attempts++;
        busy = BUSY_TICKS;
        cooldown = COOLDOWN;
        Say.actionBar(Lang.tr("steincombat.swap.dropped", Stacks.name(dropped)));
        if (Boolean.getBoolean("steincombat.debugSwap")) {
            mod.log()
                    .info(
                            "[swap] dropped "
                                    + Stacks.id(dropped)
                                    + " for "
                                    + Stacks.id(target.stack));
        }
    }

    /**
     * The inventory index of what may be dropped and is worth the least, below {@code than}; -1
     * with none.
     */
    private int cheapest(CombatConfig c, long than) {
        int held = Inventory.selectedHotbar();
        int[] keep = new int[4];
        for (int piece = 0; piece < 4; piece++) {
            keep[piece] = bestSpare(piece);
        }
        int best = -1;
        long bestValue = Long.MAX_VALUE;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null || i == held || Valuables.isValuable(c, s)) {
                continue;
            }
            int piece = Inventory.armorPiece(s);
            long v;
            if (piece >= 0) {
                if (keep[piece] == i
                        || Stacks.enchantLevel(s, 0) > c.swapMaxProtection
                        || Valuables.durabilityPercent(s) >= c.swapMaxDurability) {
                    continue;
                }
                v = Valuables.value(c, s);
            } else if (!ArmorSlots.isCombatItem(s)) {
                v = Stacks.count(s); // ordinary items first, the smallest stacks first
            } else {
                continue;
            }
            if (v < than && v < bestValue) {
                best = i;
                bestValue = v;
            }
        }
        return best;
    }

    /** The best spare of the piece (Auto Armor's next one), which never goes. */
    private static int bestSpare(int piece) {
        int best = -1;
        long bestScore = 0;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null || Inventory.armorPiece(s) != piece) {
                continue;
            }
            long score = AutoArmor.score(s) * 1000 + Valuables.durabilityPercent(s);
            if (best < 0 || score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }
}
