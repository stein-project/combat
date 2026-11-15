/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Screens;

/**
 * Autoclick: clicks at the chosen rate ({@link CombatConfig#autoclickCps}), while holding the left
 * button (the default) or constant, without holding anything, while it is on ({@link
 * CombatConfig#autoclickMode}), with a W-tap after every hit that lands.
 *
 * <p><b>Like an external autoclicker:</b> each click is a press and a release in the mouse event
 * queue ({@link Keys#click}), the same one the physical mouse feeds. The game reads them in the
 * next tick, in order with the others: the hit or the block, the arm, the packets in the usual
 * order, the game's own hit delay and the HUD's CPS counter (which counts the events). No hit
 * called from outside, and no attack ever held back. While holding, the physical button stays
 * pressed so the game knows the finger is there; the real press is already a click, and the first
 * one from here comes one interval later.
 *
 * <p><b>When:</b> only with no screen open, and last in Combat's order — it waits for armor (Auto
 * Armor), the refill (Auto Refill), food and potions (Smart Effect) and the blocking sword (Auto
 * Protect): while they handle the hand or the inventory, no click goes out (the hit would go with
 * the wrong item, in the middle of a swap).
 *
 * <p><b>Rate:</b> each click's time comes from the clock (nanoTime), checked every frame: the CPS
 * is the chosen one at any FPS; a stall (a stuck frame, a reload) does not become a burst, the rate
 * starts over from where it is. Above 20 CPS the game processes a tick's clicks one after the
 * other, and the server counts every swing.
 *
 * <p><b>W-tap, always:</b> the server drops the attacker's sprinting on every hit that knocks back,
 * and only gives it back when the client stops and starts sprinting again — with no W-tap, only the
 * first hit of a fight has the sprint knockback (measured: 1 in 60). So each time a hit lands (the
 * server's damage status for the entity just attacked, SteinMod.onEntityHurt), sprinting stops for
 * a tick and comes back ({@link ToggleSprint#reset}), and the next hit that lands has the sprint
 * knockback (measured: 92% with the 1.8 delay, no hit lost). Nothing to measure about the server.
 * At most one every {@link #WTAP_MIN_GAP} ticks, so a server with no hit delay does not keep the
 * player from sprinting at all; with a delay that short the reset does not fit before the next hit
 * anyway. It needs Toggle Sprint holding the sprint key (a key held by hand stays the player's).
 */
final class Autoclick {

    /**
     * A delay longer than this (a stall) restarts the rate, instead of sending the late clicks at
     * once.
     */
    private static final long STALL_NS = 100_000_000L;

    /**
     * At most this many clicks in one frame (very low FPS with high CPS): the rest of the delay is
     * forgiven.
     */
    private static final int MAX_PER_FRAME = 8;

    /** The damage status counts for an attack this recent (ticks: up to a second of ping). */
    private static final int WTAP_WINDOW = 20;

    /** Ticks between two W-taps at least (stop, start, and a tick sprinting). */
    static final int WTAP_MIN_GAP = 3;

    private final SteinCombat mod;

    /**
     * The time (nanoTime) of the next click; 0: stopped (the finger is not on the button, or
     * something is ahead).
     */
    private long next;

    /** Ticks counted by {@link #tick}. */
    private long tick;

    /** The entity attacked last and when; a hit on it asks for a W-tap; the last W-tap. */
    private Object lastAttacked;

    private long lastAttackAt = -100;
    private boolean wtapPending;
    private long lastWtap = -100;

    Autoclick(SteinCombat mod) {
        this.mod = mod;
    }

    /** Is it allowed to click now: on, pressed (or constant), no screen and the hand free? */
    private boolean triggered(CombatConfig c) {
        boolean always = c.autoclickMode == 1;
        return c.autoclick
                && !Screens.isOpen()
                && Entities.self() != null
                && (always || Keys.isMouseDown(0))
                && !mod.handBusy();
    }

    /** Every frame, on the game thread: the clicks at the chosen rate. */
    void frame() {
        CombatConfig c = mod.config;
        if (!triggered(c)) {
            next = 0;
            return;
        }
        boolean always = c.autoclickMode == 1;
        long now = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(1, c.autoclickCps);
        if (next == 0) {
            if (!always) {
                next = now + interval; // holding: the real press is the first click
                return;
            }
            next = now;
        }
        if (now - next > STALL_NS) {
            next = now;
        }
        for (int n = 0; now >= next && n < MAX_PER_FRAME; n++) {
            click();
            next += interval;
        }
        if (now >= next) {
            next = now + interval;
        }
    }

    /** At the end of every tick: the W-tap after a hit that landed. */
    void tick() {
        tick++;
        if (wtapPending) {
            wtapPending = false;
            if (triggered(mod.config) && tick - lastWtap >= WTAP_MIN_GAP) {
                lastWtap = tick;
                mod.sprint.reset(); // a hit landed: stop now, sprint again next tick
            }
        }
    }

    /** An attack is about to go (SteinMod.onAttackEntity, the player's own clicks too). */
    void attacked(Object entity) {
        lastAttacked = entity;
        lastAttackAt = tick;
    }

    /**
     * The server said an entity took damage — its damage status (SteinMod.onEntityHurt) or its
     * health going down (SteinMod.onHealthChanged): the attack on it landed, the W-tap follows.
     */
    void targetHurt(Object entity) {
        if (entity == lastAttacked && tick - lastAttackAt <= WTAP_WINDOW) {
            wtapPending = true;
        }
    }

    private void click() {
        Keys.click(0);
    }
}
