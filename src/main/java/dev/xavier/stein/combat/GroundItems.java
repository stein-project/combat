/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The items on the ground the client knows, tracked once per tick for Loot Radar (what to show) and
 * Loot Swap (what is worth making room for): each one's stack, its Valuable Items rule (or none),
 * when it was first seen and whether it came from a player's death — an item that appears within a
 * few blocks of a player who just died, within two seconds.
 *
 * <p>The rule is decided when the item first shows up, and again when its data changes: the item's
 * data comes from the server a moment after the entity (until then it shows as stone).
 */
final class GroundItems {

    /** How far items are tracked, in blocks (the radar's longest distance). */
    static final double MAX_DISTANCE = 64;

    /** A player's death counts for the items that appear this close, this soon after it. */
    private static final double DEATH_RADIUS = 4;

    private static final int DEATH_TICKS = 40;
    private static final int DEATHS = 8;

    /** One item on the ground. */
    static final class Item {
        final Object entity;
        final Object stack;
        final Valuables.Rule rule;
        final long firstSeen;
        final boolean fromDeath;

        /** The tick it was last seen (the sweep drops the ones not seen this tick). */
        long seen;

        /** Loot Radar: already announced with the sound. */
        boolean announced;

        /** Loot Radar's label and the distance it was written for. */
        String label;

        int labelDistance = -1;

        /** Loot Swap: how many times it made room for it. */
        int attempts;

        Item(Object entity, Object stack, Valuables.Rule rule, long firstSeen, boolean fromDeath) {
            this.entity = entity;
            this.stack = stack;
            this.rule = rule;
            this.firstSeen = firstSeen;
            this.fromDeath = fromDeath;
        }
    }

    private final SteinCombat mod;
    private final Map<Object, Item> byEntity = new IdentityHashMap<>();
    private final List<Item> items = new ArrayList<>();

    /** Recent player deaths: x, y, z and the tick, in a ring. */
    private final double[] deaths = new double[DEATHS * 4];

    private int nextDeath;
    private long tick;

    GroundItems(SteinCombat mod) {
        this.mod = mod;
        for (int i = 0; i < DEATHS; i++) {
            deaths[i * 4 + 3] = Double.NEGATIVE_INFINITY;
        }
    }

    /** The tracked items (read-only; they change every tick). */
    List<Item> items() {
        return items;
    }

    /** A player died where the client sees it: the items that appear around come from it. */
    void entityDeath(Object entity) {
        if (!Entities.isPlayer(entity) || entity == Entities.self()) {
            return;
        }
        int i = nextDeath * 4;
        deaths[i] = Entities.x(entity, 1F);
        deaths[i + 1] = Entities.y(entity, 1F);
        deaths[i + 2] = Entities.z(entity, 1F);
        deaths[i + 3] = tick;
        nextDeath = (nextDeath + 1) % DEATHS;
    }

    /** Once per tick: new items in, gone ones out. */
    void tick() {
        tick++;
        Object me = Entities.self();
        if (me == null || !wanted()) {
            if (!items.isEmpty()) {
                items.clear();
                byEntity.clear();
            }
            return;
        }
        List<Object> all = Entities.all();
        for (int i = 0, n = all.size(); i < n; i++) {
            Object e = all.get(i);
            Object stack = Entities.itemStack(e);
            if (stack == null || Entities.distance(me, e) > MAX_DISTANCE) {
                continue;
            }
            Item it = byEntity.get(e);
            if (it != null && it.stack != stack) {
                // The item's data arrives after the entity (it shows up as stone for a moment):
                // when the stack changes, the item is judged again, keeping where it came from.
                Item again =
                        new Item(
                                e,
                                stack,
                                Valuables.match(mod.config, stack),
                                it.firstSeen,
                                it.fromDeath);
                items.set(items.indexOf(it), again);
                byEntity.put(e, again);
                it = again;
            }
            if (it == null) {
                it = new Item(e, stack, Valuables.match(mod.config, stack), tick, nearDeath(e));
                byEntity.put(e, it);
                items.add(it);
            }
            it.seen = tick;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.seen != tick) {
                items.remove(i);
                byEntity.remove(it.entity);
            }
        }
    }

    /** The rules changed: the items are judged again on the next tick. */
    void rulesChanged() {
        items.clear();
        byEntity.clear();
    }

    /** Only while someone reads it: Loot Radar or Loot Swap on. */
    private boolean wanted() {
        CombatConfig c = mod.config;
        return c.lootRadar || c.lootSwap;
    }

    private boolean nearDeath(Object e) {
        double x = Entities.x(e, 1F);
        double y = Entities.y(e, 1F);
        double z = Entities.z(e, 1F);
        for (int i = 0; i < DEATHS; i++) {
            int k = i * 4;
            if (tick - deaths[k + 3] > DEATH_TICKS) {
                continue;
            }
            double dx = x - deaths[k], dy = y - deaths[k + 1], dz = z - deaths[k + 2];
            if (dx * dx + dy * dy + dz * dz <= DEATH_RADIUS * DEATH_RADIUS) {
                return true;
            }
        }
        return false;
    }
}
