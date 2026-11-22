/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Targeting;

import java.util.Locale;

/**
 * Who the target element shows: the last player you hit, while in range and for some seconds after
 * the hit; without a recent hit, the player in your crosshair (the crosshair's ray up to the
 * range).
 *
 * <p>A target that dies stays on screen for a moment as dead, and their nick is not taken again
 * right away: on servers that respawn at once (or fake the death and heal), the same nick comes
 * back with full health, which used to read as "still alive, full health".
 */
final class TargetTracker {

    /**
     * How long the crosshair target stays after the crosshair leaves it: sweeping across someone
     * does not flash the panel.
     */
    private static final long AIM_LINGER = 400_000_000L;

    /** How often the crosshair's ray is cast. */
    private static final long AIM_EVERY = 50_000_000L;

    /** How long a dead target stays on screen. */
    private static final long DEAD_SHOWN = 1_500_000_000L;

    /** How long after a death the same nick is not taken from the crosshair again. */
    private static final long DEAD_QUIET = 2_500_000_000L;

    /** {@code -Dsteinhud.targetLog=true}: who comes in and why they leave. */
    static final boolean LOG = Boolean.getBoolean("steinhud.targetLog");

    private final TargetConfig c;
    private final Log log;

    /** The last player hit (EntityPlayer), or null, and when the hit was (nanoTime). */
    private Object hitTarget;

    private long lastHit;

    /** The crosshair's player, or null, when it was last in it, and when the ray was cast. */
    private Object aimTarget;

    private long lastAim;
    private long aimChecked;

    /** The target that died, their nick, and when. */
    private Object dead;

    private String deadNick;
    private long deadAt;

    TargetTracker(TargetConfig c, Log log) {
        this.c = c;
        this.log = log;
    }

    /** You hit someone (the attack click, before it goes to the server). */
    void hit(Object entity) {
        if (LOG) {
            log.info(
                    "target: hit on "
                            + Entities.name(entity)
                            + " (player: "
                            + Entities.isPlayer(entity)
                            + ")");
        }
        if (!Entities.isPlayer(entity) || entity == Entities.self()) {
            return;
        }
        long now = System.nanoTime();
        if (entity == dead) {
            return;
        }
        deadNick = null; // hitting them again: they are back in the fight
        if (entity != hitTarget
                && hitTarget != null
                && inRange(hitTarget)
                && now - lastHit < (long) (c.switchSeconds * 1e9)) {
            return; // the current target still holds the place
        }
        hitTarget = entity;
        lastHit = now;
    }

    /**
     * A player died (the server's death status, health at 0, or what a respawn looks like: back to
     * full at once, or the same nick in another entity). Only the one on screen matters.
     */
    void died(Object entity) {
        if (entity == null || entity == dead || (entity != hitTarget && entity != aimTarget)) {
            return;
        }
        if (LOG) {
            log.info("target " + Entities.name(entity) + " died");
        }
        dead = entity;
        deadNick = Entities.name(entity);
        deadAt = System.nanoTime();
        hitTarget = null;
        aimTarget = null;
    }

    /** Is this the target that just died? */
    boolean isDead(Object entity) {
        return entity != null && entity == dead;
    }

    /** Alive, still in the client's world and in range. */
    private boolean inRange(Object t) {
        Object me = Entities.self();
        return me != null
                && Entities.isAlive(t)
                && Entities.player(Entities.name(t)) == t
                && Entities.distance(me, t) <= c.range;
    }

    /**
     * The player on the crosshair's line, up to the range (blocks stop the ray); cast at most every
     * {@link #AIM_EVERY}.
     */
    private void aim(long now) {
        if (!c.aim || now - aimChecked < AIM_EVERY) {
            return;
        }
        aimChecked = now;
        Object me = Entities.self();
        if (me == null) {
            return;
        }
        Targeting.Ray ray =
                Targeting.raycast(
                        Targeting.query()
                                .reach(c.range)
                                .living()
                                .visibleOnly()
                                .cutAtBlock()
                                .exclude(me));
        for (Targeting.Hit h : ray.hits) {
            if (Entities.isPlayer(h.entity) && !quiet(h.entity, now)) {
                if (LOG && h.entity != aimTarget) {
                    log.info(
                            String.format(
                                    Locale.ROOT,
                                    "target: aiming at %s, %.1f blocks",
                                    Entities.name(h.entity),
                                    h.distance));
                }
                aimTarget = h.entity;
                lastAim = now;
                return;
            }
        }
    }

    /** Just died: the same nick is left alone for a moment (their respawn). */
    private boolean quiet(Object entity, long now) {
        return deadNick != null
                && now - deadAt < DEAD_QUIET
                && deadNick.equals(Entities.name(entity));
    }

    /**
     * The same nick in another entity of the world: the server removed the old one and spawned a
     * new one, which is what a respawn is.
     */
    private static boolean respawned(Object t) {
        Object now = Entities.player(Entities.name(t));
        return now != null && now != t;
    }

    /** Who goes on screen: the last one hit, if still valid; otherwise the crosshair's. */
    Object choose(long now) {
        if (dead != null) {
            if (now - deadAt < DEAD_SHOWN) {
                return dead;
            }
            dead = null;
        }
        if (hitTarget != null && respawned(hitTarget)) {
            died(hitTarget);
            return dead;
        }
        if (hitTarget != null
                && (!inRange(hitTarget) || now - lastHit >= (long) (c.seconds * 1e9))) {
            if (LOG) {
                log.info(
                        "target "
                                + Entities.name(hitTarget)
                                + " left: in range "
                                + inRange(hitTarget)
                                + ", seconds "
                                + (now - lastHit) / 1e9);
            }
            hitTarget = null;
        }
        if (hitTarget != null) {
            return hitTarget;
        }
        aim(now);
        if (aimTarget != null && (!c.aim || now - lastAim >= AIM_LINGER || !inRange(aimTarget))) {
            aimTarget = null;
        }
        return aimTarget;
    }
}
