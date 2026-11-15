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
 * The items on the ground the client knows, tracked once per tick for Loot Radar: each one's stack
 * and its Treasure List rule (or none).
 *
 * <p>The rule is decided when the item first shows up, and again when its data changes: the item's
 * data comes from the server a moment after the entity (until then it shows as stone).
 */
final class GroundItems {

    /** How far items are tracked, in blocks (the radar's longest distance). */
    static final double MAX_DISTANCE = 64;

    /** One item on the ground. */
    static final class Item {
        final Object entity;
        final Object stack;
        final Valuables.Rule rule;

        /** The tick it was last seen (the sweep drops the ones not seen this tick). */
        long seen;

        /** Loot Radar: already announced with the sound. */
        boolean announced;

        /** Loot Radar's label and the distance it was written for. */
        String label;

        int labelDistance = -1;

        Item(Object entity, Object stack, Valuables.Rule rule) {
            this.entity = entity;
            this.stack = stack;
            this.rule = rule;
        }
    }

    private final SteinCombat mod;
    private final Map<Object, Item> byEntity = new IdentityHashMap<>();
    private final List<Item> items = new ArrayList<>();
    private long tick;

    GroundItems(SteinCombat mod) {
        this.mod = mod;
    }

    /** The tracked items (read-only; they change every tick). */
    List<Item> items() {
        return items;
    }

    /** Once per tick: new items in, gone ones out. */
    void tick() {
        tick++;
        Object me = Entities.self();
        if (me == null || !mod.config.lootRadar) {
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
                // when the stack changes, the item is judged again.
                Item again = new Item(e, stack, Valuables.match(mod.config, stack));
                again.announced = it.announced && again.rule == it.rule;
                items.set(items.indexOf(it), again);
                byEntity.put(e, again);
                it = again;
            }
            if (it == null) {
                it = new Item(e, stack, Valuables.match(mod.config, stack));
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
}
