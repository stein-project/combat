/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Blend;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Game;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Render;
import dev.xavier.stein.loader.api.Stacks;
import dev.xavier.stein.loader.api.WorldRender;

import java.util.List;

/**
 * Loot Radar: valuable items on the ground (the Treasure List) stand out, any drop, each rule with
 * its own style and color, set in Loot Radar's section — <b>Glow</b>, an outline seen through any
 * block or entity; <b>Beacon</b>, a beam from the sky over the item, seen from far away like a
 * waypoint; <b>Marker</b>, the item's name and distance floating over it — or not marked at all.
 * Each style draws only itself. Every item in range shows, with no limit, fainter the farther it
 * is; the list's order is the priority, and the higher rules are drawn on top. An arrow at the edge
 * of the screen points to the ones out of view, and a sound says when a valuable one drops.
 *
 * <p>Only drawing: nothing about the game changes.
 */
final class LootRadar {

    /** Ticks between two drop sounds. */
    private static final int SOUND_GAP = 20;

    /** Out of view past this angle from where the player looks, in degrees: the arrow shows. */
    private static final float ARROW_ANGLE = 50F;

    /** Half the side of the glow's box around an item, in blocks. */
    private static final double GLOW_SIZE = 0.3;

    /** How high the beacon goes above the item, in blocks. */
    private static final double BEAM_HEIGHT = 60;

    private final SteinCombat mod;

    /** The items to draw this frame, lowest priority first (reused: no allocation per frame). */
    private final java.util.ArrayList<GroundItems.Item> shown = new java.util.ArrayList<>();

    private double[] shownDistance = new double[32];

    private long tick;
    private long lastSound = -SOUND_GAP;

    /** What the reused draw calls read: the item, its color and its position. */
    private GroundItems.Item drawItem;

    private int drawColor;
    private double drawX;
    private double drawY;
    private double drawZ;

    /** Glow: a soft box around the item, a little bigger than it, and its edges. */
    private final Runnable glow =
            () -> {
                double r = GLOW_SIZE;
                WorldRender.box(
                        drawX - r,
                        drawY,
                        drawZ - r,
                        drawX + r,
                        drawY + r * 2,
                        drawZ + r,
                        (drawColor & 0x00FFFFFF) | 0x40000000);
                WorldRender.boxOutline(
                        drawX - r,
                        drawY,
                        drawZ - r,
                        drawX + r,
                        drawY + r * 2,
                        drawZ + r,
                        0.03,
                        drawColor);
            };

    private final Runnable beam =
            () ->
                    WorldRender.beam(
                            drawX,
                            drawZ,
                            drawY,
                            drawY + BEAM_HEIGHT,
                            0.12,
                            (drawColor & 0x00FFFFFF) | 0x60000000);
    private final Runnable beamThroughWalls = () -> WorldRender.throughWalls(beam);

    /** Marker: a thin line up from the item to its name and distance. */
    private final Runnable label =
            () -> {
                WorldRender.line(
                        drawX, drawY + 0.3, drawZ, drawX, drawY + 1.0, drawZ, 0.02, drawColor);
                WorldRender.label(
                        drawItem.label, drawX, drawY + 1.1, drawZ, labelScale(), drawColor, true);
            };

    LootRadar(SteinCombat mod) {
        this.mod = mod;
    }

    /** Once per tick, after the ground items: the sound for what just dropped. */
    void tick() {
        tick++;
        CombatConfig c = mod.config;
        if (!c.lootRadar) {
            return;
        }
        Object me = Entities.self();
        if (me == null) {
            return;
        }
        List<GroundItems.Item> items = mod.groundItems.items();
        for (int i = 0, n = items.size(); i < n; i++) {
            GroundItems.Item it = items.get(i);
            if (it.announced || !wanted(it)) {
                continue;
            }
            it.announced = true;
            if (c.radarSound
                    && tick - lastSound >= SOUND_GAP
                    && Entities.distance(me, it.entity) <= c.radarDistance) {
                lastSound = tick;
                Game.playSound("random.orb", 1.4F);
                if (Boolean.getBoolean("steincombat.debugItems")) {
                    mod.log().info("[radar] sound for " + Stacks.id(it.stack));
                }
            }
        }
    }

    /** In the world, every frame: each valuable item in its style, the higher priority on top. */
    void frame(float partialTicks) {
        CombatConfig c = mod.config;
        if (!c.lootRadar || Entities.self() == null) {
            shown.clear();
            return;
        }
        pick(c);
        for (int i = 0, n = shown.size(); i < n; i++) {
            GroundItems.Item it = shown.get(i);
            double d = shownDistance[i];
            drawItem = it;
            drawX = Entities.x(it.entity, partialTicks);
            drawY = Entities.y(it.entity, partialTicks);
            drawZ = Entities.z(it.entity, partialTicks);
            // fainter the farther, never so faint it is lost
            float a = (float) Math.max(0.35, 1 - d / c.radarDistance * 0.65);
            drawColor = Render.alpha(0xFF000000 | Option.rgb(String.valueOf(it.rule.color)), a);
            switch (it.rule.style) {
                case Valuables.BEACON -> WorldRender.blend(Blend.ADDITIVE, beamThroughWalls);
                case Valuables.MARKER -> {
                    int meters = (int) Math.round(d);
                    if (meters != it.labelDistance || it.label == null) {
                        it.labelDistance = meters;
                        it.label = itemText(it.stack) + " · " + meters + " m";
                    }
                    WorldRender.throughWalls(label);
                }
                default -> WorldRender.throughWalls(glow);
            }
        }
    }

    /** On the HUD: the arrows at the edge of the screen for the shown items out of view. */
    void overlay(float partialTicks) {
        CombatConfig c = mod.config;
        Object cam = Entities.camera();
        if (!c.lootRadar || !c.radarArrows || cam == null || shown.isEmpty()) {
            return;
        }
        float yaw = Entities.yaw(cam, partialTicks);
        double cx = Entities.x(cam, partialTicks);
        double cz = Entities.z(cam, partialTicks);
        float sx = Render.guiWidth() / 2F;
        float sy = Render.guiHeight() / 2F;
        float radius = Math.min(sx, sy) * 0.55F;
        for (int i = 0, n = shown.size(); i < n; i++) {
            GroundItems.Item it = shown.get(i);
            double dx = Entities.x(it.entity, partialTicks) - cx;
            double dz = Entities.z(it.entity, partialTicks) - cz;
            float itemYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float diff = wrap(itemYaw - yaw);
            if (Math.abs(diff) < ARROW_ANGLE) {
                continue;
            }
            double t = Math.toRadians(diff);
            float ux = (float) Math.sin(t);
            float uy = (float) -Math.cos(t);
            float px = sx + ux * radius;
            float py = sy + uy * radius;
            int color = 0xFF000000 | Option.rgb(String.valueOf(it.rule.color));
            // a small triangle pointing out from the center
            float tip = 6F;
            float half = 4F;
            Render.triangle(
                    px + ux * tip,
                    py + uy * tip,
                    px - uy * half,
                    py + ux * half,
                    px + uy * half,
                    py - ux * half,
                    color);
        }
    }

    /**
     * The items to draw: wanted and in range, every one of them, in priority order — the lowest
     * first, so the highest are drawn last, on top (the list's order is the priority).
     */
    private void pick(CombatConfig c) {
        shown.clear();
        Object me = Entities.self();
        List<GroundItems.Item> items = mod.groundItems.items();
        List<Valuables.Rule> rules = c.valuables;
        for (int r = rules.size() - 1; r >= 0; r--) {
            Valuables.Rule rule = rules.get(r);
            if (rule.style == Valuables.OFF) {
                continue;
            }
            for (int i = 0, n = items.size(); i < n; i++) {
                GroundItems.Item it = items.get(i);
                if (it.rule != rule) {
                    continue;
                }
                double d = Entities.distance(me, it.entity);
                if (d > c.radarDistance) {
                    continue;
                }
                if (shown.size() == shownDistance.length) {
                    shownDistance =
                            java.util.Arrays.copyOf(shownDistance, shownDistance.length * 2);
                }
                shownDistance[shown.size()] = d;
                shown.add(it);
            }
        }
    }

    private static boolean wanted(GroundItems.Item it) {
        return it.rule != null && it.rule.style != Valuables.OFF;
    }

    /**
     * The marker's text: the item's highest enchantment ("Sharpness VI"), or its name when it has
     * none.
     */
    static String itemText(Object stack) {
        int bestId = -1;
        int bestLevel = 0;
        int bestKey = 0;
        for (int k = 1; k < Valuables.ENCHANTS.length; k++) {
            int level = Stacks.enchantLevel(stack, Valuables.ENCHANTS[k]);
            if (level > bestLevel) {
                bestLevel = level;
                bestId = Valuables.ENCHANTS[k];
                bestKey = k;
            }
        }
        if (bestId < 0) {
            String name = Stacks.name(stack);
            return Stacks.count(stack) > 1 ? Stacks.count(stack) + "x " + name : name;
        }
        return Lang.tr(Valuables.ENCHANT_KEYS[bestKey]) + " " + Valuables.roman(bestLevel);
    }

    /** The marker keeps about the same size on screen far away (up to a limit). */
    private float labelScale() {
        Object cam = Entities.camera();
        double d = cam == null ? 8 : WorldRender.distance(drawX, drawY, drawZ);
        return (float) Math.max(1.5, Math.min(5.0, d / 6.0));
    }

    private static float wrap(float degrees) {
        float d = degrees % 360F;
        if (d > 180F) {
            d -= 360F;
        } else if (d < -180F) {
            d += 360F;
        }
        return d;
    }
}
