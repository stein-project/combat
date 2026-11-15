/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

/**
 * Who has the inventory, the hand and the chest: one owner at a time, in a fixed order of priority.
 * Surviving (Smart Effect eating and drinking, Auto Protect blocking) first, then Auto Armor, Auto
 * Stash, Auto Refill and, last, the Autoclick.
 *
 * <p>Nobody starts while someone ahead of it is acting ({@link #ahead}), and whoever is acting only
 * gives way at a safe point: every step of the chest trips ends with the cursor empty and the chest
 * in a known state, so the next owner finds the inventory whole. Supply Tracker and Loot Radar only
 * read and never take a turn.
 */
final class Coordinator {

    static final int SURVIVE = 0;
    static final int ARMOR = 1;
    static final int STASH = 2;
    static final int REFILL = 3;
    static final int CLICK = 4;

    private final SteinCombat mod;

    Coordinator(SteinCombat mod) {
        this.mod = mod;
    }

    /** Is the owner acting now? */
    boolean active(int owner) {
        return switch (owner) {
            case SURVIVE -> mod.smartEffect.busy();
            case ARMOR -> mod.armorSettling();
            case STASH -> mod.autoStash.busy();
            case REFILL -> mod.autoRefill.busy();
            default -> false;
        };
    }

    /** Is someone ahead of {@code owner} acting now? Then {@code owner} waits. */
    boolean ahead(int owner) {
        for (int o = SURVIVE; o < owner; o++) {
            if (active(o)) {
                return true;
            }
        }
        return false;
    }

    /** A chest trip (Auto Stash or Auto Refill) has the chest open: the bag is the trip's. */
    boolean chestTrip() {
        return mod.autoStash.busy() || mod.autoRefill.busy();
    }
}
