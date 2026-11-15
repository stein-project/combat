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
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Stacks;

/**
 * What Supply Forecast measures and simulates: during a rush, how fast each supply goes — the wear
 * of each worn armor piece (hits taken), of the sword, the axe and the bow in the hand (hits dealt,
 * shots), the arrows shot, the enchanted golden apples eaten and the potions drunk or thrown — and
 * how much of each is left, from three sources the player turns on or off: what is equipped (worn
 * armor, the item in the hand), the inventory, and the server's chest as last seen by a chest trip
 * (Auto Refill or Auto Stash: the chest is only known when opened).
 *
 * <p>With auto start, a rush begins with damage taken, the player's first hit, or an enemy player
 * nearby; the key starts and ends one by hand.
 *
 * <p>The forecast is what is left divided by the pace measured so far: armor counts the durability
 * of the worn piece plus the spares of the same piece; the others, their durability or how many.
 * Early in the rush, with few measurements, it is a range that narrows as they come.
 *
 * <p>Items that leave by other ways do not count as used: nothing is counted while a chest trip,
 * Loot Swap or one of the player's screens is moving things.
 */
final class Supplies {

    static final int HELMET = 0;
    static final int CHESTPLATE = 1;
    static final int LEGGINGS = 2;
    static final int BOOTS = 3;
    static final int SWORD = 4;
    static final int AXE = 5;
    static final int BOW = 6;
    static final int ARROWS = 7;
    static final int APPLES = 8;
    static final int POTIONS = 9;
    static final int COUNT = 10;

    /** The lang key of each supply. */
    static final String[] KEYS = {
        "steincombat.forecast.helmet",
        "steincombat.forecast.chestplate",
        "steincombat.forecast.leggings",
        "steincombat.forecast.boots",
        "steincombat.forecast.sword",
        "steincombat.forecast.axe",
        "steincombat.forecast.bow",
        "steincombat.forecast.arrows",
        "steincombat.forecast.apples",
        "steincombat.forecast.potions"
    };

    /** The game's armor piece of each armor supply (helmet 3 ... boots 0). */
    private static final int[] PIECE = {
        Inventory.HELMET, Inventory.CHESTPLATE, Inventory.LEGGINGS, Inventory.BOOTS
    };

    /** A rush starts by itself with an enemy this close, or with damage taken. */
    private static final int START_RADIUS = 8;

    private final SteinCombat mod;

    private boolean running;
    private long ticks;

    /** Used during the rush (durability for armor and weapons; items for the rest). */
    private final long[] used = new long[COUNT];

    /** How many times each one was seen going down (the confidence). */
    private final int[] events = new int[COUNT];

    /** Last tick's armor, hand and counts. */
    private final Object[] lastArmor = new Object[4];

    private final int[] lastArmorDamage = new int[4];
    private Object lastHeld;
    private int lastHeldDamage;
    private int lastHeldSlot = -1;
    private int lastArrows = -1;
    private int lastApples = -1;
    private int lastPotions = -1;
    private float lastHealth = -1;

    /** The player hit something since the last tick (a rush can start with the first hit). */
    private boolean attacked;

    /** The chest as last seen: what is left of each supply, and when (-1: never seen). */
    private final long[] chest = new long[COUNT];

    private long chestSeenAt = -1;
    private long clock;

    Supplies(SteinCombat mod) {
        this.mod = mod;
    }

    boolean running() {
        return running;
    }

    /** Minutes of rush measured. */
    double minutes() {
        return ticks / 1200.0;
    }

    /** Seconds since the chest was last seen; -1 if it never was. */
    long chestAge() {
        return chestSeenAt < 0 ? -1 : (clock - chestSeenAt) / 20;
    }

    /** The player attacked an entity: with auto start, the rush begins. */
    void attacked() {
        attacked = true;
    }

    /** The key: starts a rush, or ends the one running (and clears it). */
    void toggle() {
        if (running) {
            reset();
        } else {
            start();
        }
    }

    private void start() {
        running = true;
        ticks = 0;
        java.util.Arrays.fill(used, 0);
        java.util.Arrays.fill(events, 0);
        lastHeld = null;
        lastArrows = lastApples = lastPotions = -1;
        java.util.Arrays.fill(lastArmor, null);
    }

    void reset() {
        running = false;
        ticks = 0;
        java.util.Arrays.fill(used, 0);
        java.util.Arrays.fill(events, 0);
    }

    void tick() {
        clock++;
        CombatConfig c = mod.config;
        Object me = Entities.self();
        if (me == null) {
            if (running) {
                reset();
            }
            return;
        }
        if (!c.supplyForecast) {
            return;
        }
        // health with absorption: with a golden apple's hearts, damage only takes those
        float health = Entities.health(me) + Entities.absorption(me);
        if (!running && c.forecastAutoStart) {
            boolean hurt = lastHealth >= 0 && health < lastHealth;
            if (hurt || attacked || SmartEffect.enemyNear(me, START_RADIUS)) {
                start();
            }
        }
        attacked = false;
        lastHealth = health;
        if (!running) {
            return;
        }
        ticks++;
        for (int k = 0; k < 4; k++) {
            Object s = Inventory.armor(PIECE[k]);
            int damage = s == null ? 0 : Stacks.damage(s);
            if (s != null && lastArmor[k] != null && Stacks.sameKind(s, lastArmor[k])) {
                int delta = damage - lastArmorDamage[k];
                if (delta > 0 && delta < 50) {
                    used[k] += delta;
                    events[k]++;
                }
            }
            lastArmor[k] = s;
            lastArmorDamage[k] = damage;
        }
        Object held = Inventory.heldItem();
        int heldDamage = held == null ? 0 : Stacks.damage(held);
        int weapon = weaponOf(held);
        int slot = Inventory.selectedHotbar();
        // the server replaces the stack when it wears, so the same slot and kind is the same item
        if (weapon >= 0
                && slot == lastHeldSlot
                && lastHeld != null
                && Stacks.sameKind(held, lastHeld)) {
            int delta = heldDamage - lastHeldDamage;
            if (delta > 0 && delta < 20) {
                used[weapon] += delta;
                events[weapon]++;
            }
        }
        lastHeld = held;
        lastHeldDamage = heldDamage;
        lastHeldSlot = slot;
        boolean moving = Screens.isOpen() || mod.coordinator.chestTrip() || mod.lootSwap.busy();
        lastArrows = count(ARROWS, lastArrows, moving);
        lastApples = count(APPLES, lastApples, moving);
        lastPotions = count(POTIONS, lastPotions, moving);
    }

    /** Counts one kind of item, and what went since the last tick as used (not while moving). */
    private int count(int supply, int last, boolean moving) {
        int now = inventoryCount(supply);
        if (last >= 0 && now < last && !moving) {
            used[supply] += last - now;
            events[supply]++;
        }
        return now;
    }

    /** A chest trip has the server's chest open with its contents: what it holds is noted. */
    void chestSeen() {
        java.util.Arrays.fill(chest, 0);
        int size = Inventory.containerSize();
        for (int slot = 0; slot < size; slot++) {
            add(chest, Inventory.slotStack(slot));
        }
        chestSeenAt = clock;
    }

    /** What is left of a supply, by the sources turned on. */
    long left(int supply) {
        CombatConfig c = mod.config;
        long sum = 0;
        if (supply < 4) {
            Object worn = Inventory.armor(PIECE[supply]);
            if (c.forecastWorn && worn != null) {
                sum += remaining(worn);
            }
        } else if (supply <= BOW && c.forecastWorn && weaponOf(Inventory.heldItem()) == supply) {
            sum += remaining(Inventory.heldItem());
        }
        if (c.forecastInventory) {
            long[] inv = new long[COUNT];
            int held = Inventory.selectedHotbar();
            for (int i = 0; i < Inventory.ARMOR_START; i++) {
                if (supply >= SWORD && supply <= BOW && i == held && c.forecastWorn) {
                    continue; // already counted as equipped
                }
                add(inv, Inventory.stack(i));
            }
            sum += inv[supply];
        }
        if (c.forecastChest && chestSeenAt >= 0) {
            sum += chest[supply];
        }
        return sum;
    }

    /** Used per minute so far; 0 with nothing measured. */
    double rate(int supply) {
        double m = minutes();
        return m < 0.05 || used[supply] == 0 ? 0 : used[supply] / m;
    }

    /** Minutes the supply lasts at the pace so far; -1 when nothing was used yet. */
    double forecast(int supply) {
        double r = rate(supply);
        if (r <= 0) {
            return -1;
        }
        if (supply == ARROWS && infiniteBow()) {
            return -1;
        }
        return left(supply) / r;
    }

    /** The relative error of the forecast: wide early, narrower with each measurement. */
    double spread(int supply) {
        return Math.min(0.6, 1.2 / Math.sqrt(Math.max(1, events[supply])));
    }

    private static boolean infiniteBow() {
        Object held = Inventory.heldItem();
        return held != null && Stacks.is(held, "bow") && Stacks.enchantLevel(held, 51) > 0;
    }

    /** Durability left, or the count for what does not wear out. */
    private static long remaining(Object s) {
        int max = Stacks.maxDamage(s);
        return max > 0 ? max - Stacks.damage(s) : Stacks.count(s);
    }

    /** Adds the item to the supply it belongs to. */
    private static void add(long[] into, Object s) {
        if (s == null) {
            return;
        }
        int piece = Inventory.armorPiece(s);
        if (piece >= 0) {
            for (int k = 0; k < 4; k++) {
                if (PIECE[k] == piece) {
                    into[k] += remaining(s);
                }
            }
            return;
        }
        int weapon = weaponOf(s);
        if (weapon >= 0) {
            into[weapon] += remaining(s);
        } else if (Stacks.is(s, "arrow")) {
            into[ARROWS] += Stacks.count(s);
        } else if (Valuables.isEnchantedApple(s)) {
            into[APPLES] += Stacks.count(s);
        } else if (Stacks.is(s, "potion")) {
            into[POTIONS] += Stacks.count(s);
        }
    }

    private static int inventoryCount(int supply) {
        int n = 0;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null) {
                continue;
            }
            boolean match =
                    switch (supply) {
                        case ARROWS -> Stacks.is(s, "arrow");
                        case APPLES -> Valuables.isEnchantedApple(s);
                        default -> Stacks.is(s, "potion");
                    };
            if (match) {
                n += Stacks.count(s);
            }
        }
        return n;
    }

    /** The weapon supply of the item (sword, axe or bow), or -1. */
    static int weaponOf(Object s) {
        if (s == null) {
            return -1;
        }
        String id = Stacks.id(s);
        if (id.endsWith("_sword")) {
            return SWORD;
        }
        if (id.endsWith("_axe")) {
            return AXE;
        }
        return id.equals("minecraft:bow") ? BOW : -1;
    }
}
