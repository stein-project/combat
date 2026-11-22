/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Relation;
import dev.xavier.stein.loader.api.Relations;
import dev.xavier.stein.loader.api.Stacks;
import dev.xavier.stein.loader.api.WorldRender;

import net.minecraft.entity.EntityLivingBase;

import java.util.Arrays;

/**
 * Armor Gap: players missing pieces of armor stand out, glowing in a color (their body and armor
 * tinted, pulsing, like the game's damage red) or outlined by their hit box, or both.
 *
 * <p>Only what the server shows: the worn pieces come from its equipment packets, the same ones
 * everyone's client draws. Allies, your clan and invisible players are left out; a player wearing
 * any piece with Protection at the chosen level or above can be left out too (still well
 * protected).
 *
 * <p>The list is rebuilt every few ticks; the per-frame questions (the tint is asked for every
 * living entity every frame) only look through it.
 */
final class ArmorGap {

    static final int GLOW = 0;
    static final int HITBOX = 1;
    static final int BOTH = 2;

    /** The Protection enchantment's id. */
    private static final int PROTECTION = 0;

    /** Ticks between rebuilds of the list: a piece put on or off shows within a quarter second. */
    private static final int EVERY = 5;

    /** How strong the tint is, from the pulse's low to its high (alpha out of 255). */
    private static final int ALPHA_LOW = 60;

    private static final int ALPHA_HIGH = 120;

    /** The pulse's period. */
    private static final double PULSE_SECONDS = 1.2;

    private final SteinCombat mod;

    /** The players marked now: the first {@link #count}. */
    private Object[] marked = new Object[16];

    private int count;
    private int ticks;

    ArmorGap(SteinCombat mod) {
        this.mod = mod;
    }

    /** Every tick: rebuilds the list now and then, or empties it when off or not allowed. */
    void tick(boolean allowed) {
        // The Fight View's "fully armored as ghosts" needs the list too, highlight or not.
        boolean view = mod.guard.hiding && mod.config.ghostArmored;
        if (!allowed || !(mod.config.armorGap || view)) {
            clear();
            return;
        }
        if (++ticks % EVERY == 0) {
            rebuild();
        }
    }

    private void clear() {
        Arrays.fill(marked, 0, count, null);
        count = 0;
    }

    private void rebuild() {
        clear();
        Object me = Entities.self();
        if (me == null) {
            return;
        }
        CombatConfig c = mod.config;
        for (Object p : Entities.players()) {
            if (p == me
                    || !Entities.isAlive(p)
                    || Entities.isInvisible(p)
                    || Entities.distance(me, p) > c.armorGapRange
                    || friendly(Relations.of(p))
                    || !exposed(p, c)) {
                continue;
            }
            if (count == marked.length) {
                marked = Arrays.copyOf(marked, count * 2);
            }
            marked[count++] = p;
        }
    }

    private static boolean friendly(Relation r) {
        return r == Relation.SELF || r == Relation.GROUP || r == Relation.ALLY;
    }

    /**
     * Missing at least the chosen number of pieces, and (when that option is on) no worn piece with
     * Protection at the chosen level or above.
     */
    static boolean exposed(Object player, CombatConfig c) {
        if (!(player instanceof EntityLivingBase l)) {
            return false;
        }
        int missing = 0;
        for (int slot = 1; slot <= 4; slot++) {
            Object s = l.getEquipmentInSlot(slot);
            if (s == null) {
                missing++;
            } else if (c.armorGapProtection > 0
                    && Stacks.enchantLevel(s, PROTECTION) >= c.armorGapProtection) {
                return false;
            }
        }
        return missing >= c.armorGapMissing;
    }

    /** Is this player marked now? (Focus leaves only them solid.) */
    boolean isMarked(Object entity) {
        for (int i = 0; i < count; i++) {
            if (marked[i] == entity) {
                return true;
            }
        }
        return false;
    }

    /** The tint for this entity this frame (the Loader's onEntityColor): pulsing, if marked. */
    int color(Object entity, int color) {
        if (count == 0
                || color != 0
                || !mod.config.armorGap
                || mod.config.armorGapStyle == HITBOX
                || !isMarked(entity)) {
            return color;
        }
        double phase = System.nanoTime() / 1e9 / PULSE_SECONDS * 2 * Math.PI;
        int alpha = ALPHA_LOW + (int) ((ALPHA_HIGH - ALPHA_LOW) * (0.5 + 0.5 * Math.sin(phase)));
        return alpha << 24 | rgb();
    }

    /** The hit box outlines, in the world's drawing. */
    void frame() {
        if (count == 0 || !mod.config.armorGap || mod.config.armorGapStyle == GLOW) {
            return;
        }
        WorldRender.throughWalls(outlines);
    }

    /**
     * The outlines without the depth test: seen through the players in front (a crowd in the way)
     * and, the API having no finer test, through blocks too.
     */
    private final Runnable outlines =
            () -> {
                int argb = 0xFF000000 | rgb();
                for (int i = 0; i < count; i++) {
                    WorldRender.entityOutline(marked[i], 0.03, argb);
                }
            };

    private int rgb() {
        return Option.rgb(String.valueOf(mod.config.armorGapColor)) & 0xFFFFFF;
    }
}
