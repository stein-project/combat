/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Effects;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Stacks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Supply Tracker measures and simulates: during a rush, how fast each supply goes — the wear
 * of each worn armor piece (hits taken), of the sword, the axe and the bow in the hand (hits dealt,
 * shots), the arrows shot and the enchanted golden apples eaten — and how much of each is left (and
 * of each kind of potion, by name), from three sources the player turns on or off: what is equipped
 * (worn armor, the item in the hand), the inventory, and the server's virtual chest as last seen by
 * a chest trip (Auto Refill or Auto Stash: the chest is only known when opened).
 *
 * <p>A rush begins by itself with damage taken, the player's first hit, or an enemy player nearby;
 * the key ends one (and starts one) by hand. Every tick it follows what is used, so the HUD's
 * figures move as the supplies go.
 *
 * <p>The forecast is what is left divided by the pace measured so far: armor counts the durability
 * of the worn piece plus the spares of the same piece; the others, their durability or how many.
 *
 * <p>Items that leave by other ways do not count as used: nothing is counted while a chest trip or
 * one of the player's screens is moving things.
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
    static final int COUNT = 9;

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
        "steincombat.forecast.apples"
    };

    /** The game's armor piece of each armor supply (helmet 3 ... boots 0). */
    private static final int[] PIECE = {
        Inventory.HELMET, Inventory.CHESTPLATE, Inventory.LEGGINGS, Inventory.BOOTS
    };

    /** A rush starts by itself with an enemy this close, or with damage taken. */
    private static final int START_RADIUS = 8;

    /** An effect's time going up by more than this (ticks) is a new dose taken before it ended. */
    private static final int RENEW_TICKS = 20;

    /** How much each new measurement of a dose's use weighs in the average. */
    private static final double DOSE_WEIGHT = 0.3;

    /**
     * One effect (and level) the player has had: the dose running now, and how much of a dose the
     * player gets on average — eating or drinking again before it ends throws away what was left,
     * so a player who renews early gets less out of each potion or apple.
     */
    private static final class Dose {
        /** The dose running now: its time when taken, and its time left last tick. */
        int applied;

        int last;
        boolean active;
        long seen;

        /** The part of a dose that is used, on average (1: each one runs to its end). */
        double used = 1;

        void measured(double part) {
            used += (Math.max(0.05, Math.min(1, part)) - used) * DOSE_WEIGHT;
        }
    }

    /** By effect and level ({@link #effectKey}), for as long as the game is open. */
    private final Map<Integer, Dose> doses = new LinkedHashMap<>();

    /** One kind of potion, by its name ("Potion of Swiftness", "Splash Potion of Healing"). */
    static final class Potion {
        final String name;

        /** In the inventory now. */
        int now;

        /** One of them, to read its effects from ({@link dev.xavier.stein.loader.api.Effects}). */
        Object stack;

        /** In the chest as last seen. */
        long chest;

        Potion(String name) {
            this.name = name;
        }
    }

    private final SteinCombat mod;

    private boolean running;
    private long ticks;

    /** Used during the rush (durability for armor and weapons; items for the rest). */
    private final long[] used = new long[COUNT];

    /** Last tick's armor, hand and counts. */
    private final Object[] lastArmor = new Object[4];

    private final int[] lastArmorDamage = new int[4];
    private Object lastHeld;
    private int lastHeldDamage;
    private int lastHeldSlot = -1;
    private int lastArrows = -1;
    private int lastApples = -1;
    private float lastHealth = -1;

    /** Each kind of potion seen, in the order they showed up. */
    private final Map<String, Potion> potions = new LinkedHashMap<>();

    private final List<Potion> potionList = new ArrayList<>();

    /** The player hit something since the last tick (a rush can start with the first hit). */
    private boolean attacked;

    /** The chest as last seen: what is left of each supply (chestSeenAt -1: never seen). */
    private final long[] chest = new long[COUNT];

    private long chestSeenAt = -1;
    private long clock;

    Supplies(SteinCombat mod) {
        this.mod = mod;
    }

    boolean running() {
        return running;
    }

    /** The tick count: it moves every tick, and the HUD rebuilds its figures when it does. */
    long clock() {
        return clock;
    }

    /** Minutes of rush measured. */
    double minutes() {
        return ticks / 1200.0;
    }

    /** The player attacked an entity: the rush begins, if none is running. */
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
        lastHeld = null;
        lastArrows = lastApples = -1;
        java.util.Arrays.fill(lastArmor, null);
    }

    void reset() {
        running = false;
        ticks = 0;
        java.util.Arrays.fill(used, 0);
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
        if (!running) {
            boolean hurt = lastHealth >= 0 && health < lastHealth;
            if (hurt || attacked || SmartEffect.enemyNear(me, START_RADIUS)) {
                start();
            }
        }
        attacked = false;
        lastHealth = health;
        doses(me);
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
            }
        }
        lastHeld = held;
        lastHeldDamage = heldDamage;
        lastHeldSlot = slot;
        boolean moving = Screens.isOpen() || mod.coordinator.chestTrip();
        lastArrows = count(ARROWS, lastArrows, moving);
        lastApples = count(APPLES, lastApples, moving);
        countPotions();
    }

    /** Counts one kind of item, and what went since the last tick as used (not while moving). */
    private int count(int supply, int last, boolean moving) {
        int now = inventoryCount(supply);
        if (last >= 0 && now < last && !moving) {
            used[supply] += last - now;
        }
        return now;
    }

    /** How many of each kind of potion the inventory holds (only how many: no pace). */
    private void countPotions() {
        for (int i = 0, n = potionList.size(); i < n; i++) {
            potionList.get(i).now = 0;
        }
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s != null && Stacks.is(s, "potion")) {
                Potion p = potion(Stacks.name(s));
                p.now += Stacks.count(s);
                p.stack = s;
            }
        }
    }

    private Potion potion(String name) {
        Potion p = potions.get(name);
        if (p == null) {
            p = new Potion(name);
            potions.put(name, p);
            potionList.add(p);
        }
        return p;
    }

    /** A chest trip has the server's chest open with its contents: what it holds is noted. */
    void chestSeen() {
        java.util.Arrays.fill(chest, 0);
        for (Potion p : potionList) {
            p.chest = 0;
        }
        int size = Inventory.containerSize();
        for (int slot = 0; slot < size; slot++) {
            Object s = Inventory.slotStack(slot);
            if (s != null && Stacks.is(s, "potion")) {
                Potion p = potion(Stacks.name(s));
                p.chest += Stacks.count(s);
                if (p.stack == null) {
                    p.stack = s;
                }
            } else {
                add(chest, s);
            }
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

    /** Minutes the supply lasts at the pace so far; -1 when nothing was used yet. */
    double forecast(int supply) {
        if (supply == ARROWS && infiniteBow()) {
            return -1;
        }
        return forecast(left(supply), used[supply]);
    }

    /**
     * Follows each effect's doses: renewed before it ended, the part of the last one that was used
     * is measured; run to its end, all of it was. A gap between doses changes nothing: the next one
     * still gives all its time.
     */
    private void doses(Object me) {
        for (Effects.Effect e : Effects.of(me)) {
            if (e.permanent) {
                continue;
            }
            int key = effectKey(e.id, e.amplifier);
            Dose d = doses.get(key);
            if (d == null) {
                d = new Dose();
                doses.put(key, d);
            }
            if (!d.active) {
                d.active = true;
                d.applied = e.duration;
            } else if (e.duration > d.last + RENEW_TICKS) {
                if (d.applied > 0) {
                    d.measured((d.applied - d.last) / (double) d.applied);
                }
                d.applied = e.duration;
            }
            d.last = e.duration;
            d.seen = clock;
        }
        for (Dose d : doses.values()) {
            if (d.active && d.seen != clock) {
                d.active = false;
                d.measured(1); // it ran to its end
            }
        }
    }

    /**
     * The part of a dose of the effect the player gets, on average: 1 until a renewal is seen, less
     * for whoever eats or drinks again before the effect ends.
     */
    double doseUse(int key) {
        Dose d = doses.get(key);
        return d == null ? 1 : d.used;
    }

    /** One key per effect and level. */
    static int effectKey(int id, int amplifier) {
        return id << 8 | (amplifier & 0xFF);
    }

    /** The kinds of potion seen (read-only; in the order they showed up). */
    List<Potion> potions() {
        return potionList;
    }

    /** How many of the potion are left, by the sources turned on. */
    long left(Potion p) {
        CombatConfig c = mod.config;
        long chestCount = c.forecastChest && chestSeenAt >= 0 ? p.chest : 0;
        return (c.forecastInventory ? p.now : 0) + chestCount;
    }

    private double forecast(long left, long usedSoFar) {
        double m = minutes();
        if (m < 0.05 || usedSoFar == 0) {
            return -1;
        }
        return left / (usedSoFar / m);
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

    /** Adds the item to the supply it belongs to (potions are counted by kind, apart). */
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
                    supply == ARROWS ? Stacks.is(s, "arrow") : Valuables.isEnchantedApple(s);
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
