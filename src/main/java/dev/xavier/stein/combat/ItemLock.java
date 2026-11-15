/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Screens;

/**
 * Item Lock: the drop key never drops a locked item — Q (and Ctrl+Q) with it in the hand, and Q
 * with the mouse over it in the inventory or a chest. The only way to drop it is on purpose: pick
 * it up with the mouse and click outside the window. Locked is what the Treasure List says, or
 * every item with "All items" on. There is no key that lets it through: the mouse is the way.
 */
final class ItemLock {

    /** Ticks between two "locked" notices on the action bar. */
    private static final int NOTICE_GAP = 40;

    private final SteinCombat mod;

    private int lastNotice = -NOTICE_GAP;
    private int ticks;

    /** Drops cancelled so far (the debug log counts them). */
    private int blocked;

    ItemLock(SteinCombat mod) {
        this.mod = mod;
    }

    /** Every tick: the clock of the notices. */
    void tick() {
        ticks++;
    }

    /** Is the item locked? */
    boolean locked(Object stack) {
        CombatConfig c = mod.config;
        return stack != null && c.itemLock && (c.itemLockAll || Valuables.isValuable(c, stack));
    }

    /**
     * After each key event, before the game reads its keys: with a locked item in the hand and no
     * screen open, the drop key's presses are eaten — the game never sees them.
     */
    void keyInput() {
        if (!mod.config.itemLock || Screens.isOpen()) {
            return;
        }
        Keys.Binding drop = Keys.game(Keys.GameKey.DROP);
        if (!locked(Inventory.heldItem())) {
            return;
        }
        boolean ate = false;
        while (drop.consumeClick()) {
            ate = true;
        }
        if (ate) {
            refused();
        }
    }

    /**
     * A window click: the drop click (Q over a slot, with Ctrl the whole stack) on a locked item is
     * cancelled. Picking it up and clicking outside the window ({@link Inventory#OUTSIDE} with the
     * pickup click) is not touched: that is the way to drop on purpose.
     */
    boolean onWindowClick(int slot, int mode) {
        if (!mod.config.itemLock || mode != Inventory.THROW || slot < 0) {
            return false;
        }
        if (!locked(Inventory.slotStack(slot))) {
            return false;
        }
        refused();
        return true;
    }

    private void refused() {
        blocked++;
        if (Boolean.getBoolean("steincombat.debugItems")) {
            mod.log().info("[lock] drop refused (" + blocked + ")");
        }
        if (ticks - lastNotice >= NOTICE_GAP) {
            lastNotice = ticks;
            Say.actionBar(Lang.tr("steincombat.itemLock.refused"));
        }
    }
}
