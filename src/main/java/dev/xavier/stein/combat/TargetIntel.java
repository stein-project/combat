/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Render;
import dev.xavier.stein.loader.api.Stacks;

import net.minecraft.entity.EntityLivingBase;

/**
 * How much the target element can trust what the server sends about other players, and how old what
 * it shows is.
 *
 * <p>The game sends another player's health on every change and their worn items again whenever one
 * changes, wear included (tested on Spigot 1.8.8: every hit shows). Many servers hide it instead:
 * the health always full, items without their wear. Showing those numbers would lie, so this
 * watches the player's own hits: a hit that landed (the server's hurt status right after the
 * attack) and took no health four times means the health is hidden here; six that wore no worn
 * piece mean the wear is. One real change proves the opposite at once. Both are forgotten on every
 * join (another server).
 *
 * <p>The age: since the shown target's health and items last changed. The server only sends
 * changes, so a still player's age grows and means nothing; right after a hit it says whether
 * anything arrived.
 */
final class TargetIntel {

    /** A hurt status this soon after the player's attack on the same entity is that hit landing. */
    private static final long LANDED_WINDOW = 700_000_000L;

    /** Landed hits without a health drop that prove the health is hidden. */
    private static final int HEALTH_PROOF = 4;

    /**
     * Landed hits without wear that prove the wear is hidden (more than for health: Unbreaking
     * skips some of the wear).
     */
    private static final int WEAR_PROOF = 6;

    /** Below this age nothing is shown: data that just arrived needs no clock. */
    static final long AGE_SHOWN = 2_000_000_000L;

    private boolean healthHidden;
    private boolean wearHidden;

    /** The last entity attacked, and when (nanoTime). */
    private Object attacked;

    private long attackedAt;

    /** The player the evidence is about: landed hits, health drops, and the wear at the first. */
    private Object probe;

    private int probeHits;
    private int probeDrops;
    private int probeWear = -1;
    private boolean probeWore;

    /** The shown target, when its health and items last changed, and what its items were. */
    private Object watched;

    private long healthAt;
    private long wearAt;
    private final Object[] wearStacks = new Object[5];
    private final int[] wearDamage = new int[5];

    // The age texts, built again only when the whole second changes.
    private long healthSecond = -1;
    private String healthText = "";
    private long wearSecond = -1;
    private String wearText = "";

    /** Does this server hide other players' health? */
    boolean healthHidden() {
        return healthHidden;
    }

    /** Does this server hide the wear of other players' items? */
    boolean wearHidden() {
        return wearHidden;
    }

    /** Joined a server: what was learned was about another one. */
    void joined() {
        healthHidden = false;
        wearHidden = false;
        attacked = null;
        probe = null;
        watched = null;
    }

    /** The player attacked an entity (the click, before it reaches the server). */
    void attacked(Object entity) {
        attacked = entity;
        attackedAt = System.nanoTime();
    }

    /** The server's hurt status on an entity (status 2). */
    void hurt(Object entity) {
        if (entity != attacked
                || System.nanoTime() - attackedAt > LANDED_WINDOW
                || !Entities.isPlayer(entity)) {
            return;
        }
        attacked = null; // one hit, one landing
        if (entity != probe) {
            probe = entity;
            probeHits = 0;
            probeDrops = 0;
            probeWear = -1;
            probeWore = false;
        }
        probeHits++;
        int wear = wornDamage(entity);
        if (wear >= 0) {
            if (probeWear < 0) {
                probeWear = wear;
            } else if (wear != probeWear) {
                probeWore = true;
                wearHidden = false;
            }
        }
        if (probeHits >= HEALTH_PROOF && probeDrops == 0) {
            healthHidden = true;
        }
        if (probeHits >= WEAR_PROOF && probeWear >= 0 && !probeWore) {
            wearHidden = true;
        }
    }

    /** Another entity's health changed (the server's data, already applied). */
    void healthChanged(Object entity, float oldTotal, float newTotal) {
        if (entity == watched) {
            healthAt = System.nanoTime();
        }
        if (newTotal < oldTotal && Entities.isPlayer(entity)) {
            healthHidden = false; // a real drop: this server sends health
            if (entity == probe) {
                probeDrops++;
            }
        }
    }

    /**
     * The target on screen this frame: a new one starts its ages now (its data came with it), and a
     * change in its items restarts the items' age.
     */
    void watch(Object target, long now) {
        if (target != watched) {
            watched = target;
            healthAt = now;
            wearAt = now;
            healthSecond = -1;
            wearSecond = -1;
        }
        if (!(target instanceof EntityLivingBase l)) {
            return;
        }
        for (int slot = 0; slot < 5; slot++) {
            Object s = l.getEquipmentInSlot(slot);
            int damage = s == null ? 0 : Stacks.damage(s);
            if (s != wearStacks[slot] || damage != wearDamage[slot]) {
                wearStacks[slot] = s;
                wearDamage[slot] = damage;
                wearAt = now;
            }
        }
    }

    /** The age of the target's health ("" when too recent to show). */
    String healthAge(long now) {
        long age = now - healthAt;
        if (age < AGE_SHOWN) {
            return "";
        }
        long second = age / 1_000_000_000L;
        if (second != healthSecond) {
            healthSecond = second;
            healthText = ageText(second);
        }
        return healthText;
    }

    /** The age of the target's items ("" when too recent to show). */
    String wearAge(long now) {
        long age = now - wearAt;
        if (age < AGE_SHOWN) {
            return "";
        }
        long second = age / 1_000_000_000L;
        if (second != wearSecond) {
            wearSecond = second;
            wearText = ageText(second);
        }
        return wearText;
    }

    private static String ageText(long seconds) {
        return seconds < 60 ? seconds + "s" : seconds / 60 + "m";
    }

    /**
     * A small clock and the age next to it, at (x, y), 6 pixels high.
     *
     * @return the width drawn
     */
    static int drawAge(String age, float x, float y, float a) {
        int color = Render.alpha(0xFFA0A0A0, a);
        Render.ring(x + 3, y + 3, 3, 0.8F, color);
        Render.line(x + 3, y + 3, x + 3, y + 1.2F, 0.7F, color);
        Render.line(x + 3, y + 3, x + 4.4F, y + 3, 0.7F, color);
        Render.push();
        Render.translate(x + 7.5F, y + 1);
        Render.scale(0.5F);
        Render.text(age, 0, 0, color, true);
        Render.pop();
        return 8 + (Render.textWidth(age) + 1) / 2;
    }

    /** The summed wear of the worn damageable armor, or -1 with none. */
    private static int wornDamage(Object entity) {
        if (!(entity instanceof EntityLivingBase l)) {
            return -1;
        }
        int sum = -1;
        for (int slot = 1; slot <= 4; slot++) {
            Object s = l.getEquipmentInSlot(slot);
            if (s != null && Stacks.isDamageable(s)) {
                sum = Math.max(sum, 0) + Stacks.damage(s);
            }
        }
        return sum;
    }
}
