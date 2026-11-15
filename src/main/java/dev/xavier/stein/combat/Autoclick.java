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
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Screens;
import dev.xavier.stein.loader.api.Targeting;

/**
 * Autoclick: clicks at the chosen rate ({@link CombatConfig#autoclickCps}), while holding the left
 * button (the default) or constant, without holding anything, while it is on ({@link
 * CombatConfig#autoclickMode}).
 *
 * <p><b>Like an external autoclicker:</b> each click is a press and a release in the mouse event
 * queue ({@link Keys#click}), the same one the physical mouse feeds. The game reads them in the
 * next tick, in order with the others: the hit or the block, the arm, the packets in the usual
 * order, the game's own hit delay and the HUD's CPS counter (which counts the events). No hit
 * called from outside. While holding, the physical button stays pressed so the game knows the
 * finger is there; the real press is already a click, and the first one from here comes one
 * interval later.
 *
 * <p><b>Rate:</b> each click's time comes from the clock (nanoTime), checked every frame: the CPS
 * is the chosen one at any FPS, with no variation at all; with more than one click per tick (CPS
 * above 20), the game processes the tick's clicks one after the other, as it would the mouse's. A
 * stall (a stuck frame, a reload) does not become a burst: the rate starts over from where it is.
 *
 * <p><b>When:</b> only with no screen open, and last in Combat's order — it waits for armor (Auto
 * Armor), the refill (Auto Refill), food and potions (Smart Effect) and the blocking sword (Auto
 * Protect): while they handle the hand or the inventory, no click goes out (the hit would go with
 * the wrong item, in the middle of a swap).
 *
 * <p><b>Timing</b> ({@link CombatConfig#autoclickTiming}): <i>normal</i> is the clock above. The
 * game reads the mouse queue once per tick (20 a second), so above 20 CPS some ticks get two clicks
 * and the second adds nothing. <i>Tick Sync</i> clicks at most once per tick, at the end of the
 * tick (the next one reads it first).
 *
 * <p><i>Precise</i> clicks like Tick Sync — the arm never stops swinging and the HUD's CPS stays
 * the chosen one — but lets an attack reach the server only when it can land: a hit target cannot
 * take damage again for a while (10 ticks in 1.8, less on "reduced delay" servers). The delay is
 * <b>measured on the server</b>: the gaps between the target's hits (its hurt animation and its
 * health) while attacks come every tick, the shortest one that repeats. Then each window opens a
 * round trip (ping plus a tick each way) before the immunity ends; the attacks before it are
 * cancelled in {@link #allowAttack}, so the client is not slowed by hits that would do nothing
 * (each hit while sprinting cuts the speed to 60%, vanilla).
 *
 * <p>The server drops the attacker's sprinting on every hit that knocks back, and it only comes
 * back when the client stops and starts sprinting again: with no W-tap, only the first hit of a
 * fight has the sprint knockback (measured: 1 in 60). Precise stops and restarts sprinting before
 * the window ({@link ToggleSprint#reset}) — before every hit when the delay leaves room (5 ticks or
 * more here), every few hits on a shorter delay (trading some hits for knockback, by the distance
 * to the target), or never on a short delay with "short reset" off.
 */
final class Autoclick {

    /** The clock below, checked every frame (the default). */
    static final int NORMAL = 0;

    /** At most one click per tick, at the end of the tick. */
    static final int TICK_SYNC = 1;

    /** Tick Sync whose attacks wait out the target's damage immunity (measured on the server). */
    static final int PRECISE = 2;

    /** {@code -Dsteincombat.debugPrecise=true}: what Precise learns about the server's delay. */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugPrecise");

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

    /** The 1.8 immunity after a hit, in ticks: where the estimate starts. */
    private static final int DEFAULT_IMMUNITY = 10;

    /** The estimate never goes past this (a server with a longer delay than vanilla). */
    private static final int MAX_IMMUNITY = 20;

    /** The hurt animation's first value: it restarts there each time damage arrives. */
    private static final int HURT_START = 10;

    /** Hits at the same estimate before it counts as confirmed (and probing gets rare). */
    private static final int CONFIRM_HITS = 3;

    /** Once confirmed, one hit in this many probes one tick earlier. */
    private static final int PROBE_EVERY = 8;

    /** Attacks let through after the predicted end with no damage: the estimate goes up. */
    private static final int MISSES_TO_RAISE = 4;

    private final SteinCombat mod;

    /**
     * The time (nanoTime) of the next click; 0: stopped (the finger is not on the button, or
     * something is ahead).
     */
    private long next;

    /** Tick Sync and Precise: clicks owed, in fractions (CPS / 20 per tick). */
    private float credit;

    /** Ticks counted by {@link #tick}. */
    private long tick;

    /** Precise: the server's immunity as measured so far, in ticks (the same for every target). */
    private int immunity = DEFAULT_IMMUNITY;

    /** Hits seen at the current estimate, and hits since the last probe. */
    private int confirmed;

    /** The last gaps between hits, in a ring (0 = empty). */
    private final int[] gaps = new int[10];

    private int gapAt;

    private int sinceProbe;

    /** Precise: the target being watched, its last damage (tick) and its hurt animation. */
    private Object target;

    private long lastHit = -1;
    private int lastHurt;

    /** The target's health went down since the last tick. */
    private boolean damaged;

    /** The predicted end of the immunity (when an attack sent now lands), and the window. */
    private long immuneUntil;

    private long open;

    /** The tick of this immunity's sprint reset; -1 with none. */
    private long resetAt = -1;

    /** Attacks let through after the predicted end with no damage. */
    private int misses;

    /** Hits since the last sprint reset (short immunity: a reset every few hits). */
    private int sinceReset;

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

    /** Every frame, on the game thread: the normal timing. */
    void frame() {
        CombatConfig c = mod.config;
        if (c.autoclickTiming != NORMAL || !triggered(c)) {
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

    /** At the end of every tick: Tick Sync and Precise. */
    void tick() {
        tick++;
        CombatConfig c = mod.config;
        if (c.autoclickTiming == NORMAL) {
            return;
        }
        boolean on = triggered(c);
        if (c.autoclickTiming == PRECISE) {
            watchTarget(c, on);
        }
        if (!on) {
            credit = 0;
            return;
        }
        // One click per tick at most. With Precise every click goes too — the arm keeps swinging
        // and the HUD's CPS stays the chosen one — and the attacks that would fall inside the
        // target's immunity are cancelled before they reach the server ({@link #allowAttack}).
        credit = Math.min(1F, credit + Math.min(20, c.autoclickCps) / 20F);
        if (credit < 1F) {
            return;
        }
        credit -= 1F;
        click();
    }

    /** A new server: the delay is measured again from the vanilla value. */
    void serverChanged() {
        immunity = DEFAULT_IMMUNITY;
        confirmed = 0;
        java.util.Arrays.fill(gaps, 0);
        target = null;
        immuneUntil = 0;
    }

    /** Precise: the server's immunity as measured, in ticks (the Panel shows it). */
    int immunity() {
        return immunity;
    }

    /** The server lowered an entity's health (SteinMod.onHealthChanged): a hit, for the target. */
    void healthDropped(Object entity) {
        if (entity == target) {
            damaged = true;
        }
    }

    /**
     * Precise, at the end of each tick: the target in the crosshair, and when it took damage — the
     * hurt animation restarting, or its health going down (with hits every tick the animation does
     * not show a restart). The gap between two hits, while attacks keep coming, is the server's
     * immunity: what the estimate learns.
     */
    private void watchTarget(CombatConfig c, boolean on) {
        Object t = Targeting.pointedEntity();
        if (t != null && !Entities.isLiving(t)) {
            t = null;
        }
        if (t != target) {
            target = t;
            immuneUntil = 0;
            lastHit = -1;
            lastHurt = 0;
            misses = 0;
            damaged = false;
        }
        if (t == null) {
            return;
        }
        int hurt = Entities.hurtTime(t);
        if (damaged || hurt > lastHurt && hurt >= HURT_START - 1) {
            hit(t);
        }
        damaged = false;
        lastHurt = hurt;
        if (on && c.autoclickSprintReset && resetAt >= 0 && tick >= resetAt) {
            resetAt = -1;
            mod.sprint.reset();
        }
    }

    /**
     * The target took damage: learn from the gap and plan the next window. An attack sent now
     * reaches the server a round trip after the damage happened there (the damage came half a ping
     * late, the attack takes another half, and each side reads it on its next tick), so the window
     * opens that much before the immunity's end, a tick early to be safe: an early attack only
     * swings the arm, a late one loses a hit.
     */
    private void hit(Object t) {
        if (lastHit >= 0 && lastHit != tick) {
            learn((int) (tick - lastHit));
        }
        lastHit = tick;
        misses = 0;
        resetAt = -1;
        int ping = Math.max(0, Players.ping(Players.self()));
        int roundTrip = Math.round(ping / 50F) + 2;
        immuneUntil = tick + immunity - roundTrip;
        open = immuneUntil - 1;
        if (confirmed < CONFIRM_HITS) {
            open = tick; // measuring: attacks every tick, so the gap is the server's own delay
        } else if (++sinceProbe >= PROBE_EVERY) {
            sinceProbe = 0;
            open--; // now and then a tick earlier, in case the server got faster
        }
        CombatConfig c = mod.config;
        if (!c.autoclickSprintReset || confirmed < CONFIRM_HITS) {
            return;
        }
        if (open - tick >= 2) {
            // fits before every hit: the stop and the start reach the server before the window
            resetAt = open - 2;
            return;
        }
        // Too short for a reset before every hit: one every few, holding the attacks two ticks,
        // more often with the target close (knockback keeps the fight in reach), none when it is
        // slipping away — or none at all, for whoever wants only the damage.
        if (!c.autoclickShortReset) {
            return;
        }
        double d = Entities.distance(Entities.self(), t);
        int every = d < 2.0 ? 2 : d < 2.8 ? 3 : Integer.MAX_VALUE;
        if (++sinceReset >= every) {
            sinceReset = 0;
            mod.sprint.reset();
            open = Math.max(open, tick + 2);
        }
    }

    /**
     * A measured gap between two hits. Gaps wobble by a tick (where each side's tick falls), so the
     * delay is the shortest gap seen at least twice among the last ones, and it counts as confirmed
     * once a few of them sit at it (or one tick above).
     */
    private void learn(int gap) {
        if (gap <= 0 || gap > MAX_IMMUNITY) {
            return;
        }
        gaps[gapAt] = gap;
        gapAt = (gapAt + 1) % gaps.length;
        int best = Integer.MAX_VALUE;
        for (int a : gaps) {
            if (a <= 0 || a >= best) {
                continue;
            }
            int seen = 0;
            for (int b : gaps) {
                seen += b == a ? 1 : 0;
            }
            if (seen >= 2) {
                best = a;
            }
        }
        if (best != Integer.MAX_VALUE && best != immunity) {
            immunity = best;
        }
        confirmed = 0;
        for (int b : gaps) {
            confirmed += b == immunity || b == immunity + 1 ? 1 : 0;
        }
        if (DEBUG) {
            mod.log()
                    .info(
                            "[precise] gap "
                                    + gap
                                    + ", immunity "
                                    + immunity
                                    + ", confirmed "
                                    + confirmed);
        }
    }

    /**
     * Precise, when an attack is about to go (SteinMod.onAttackEntity, the player's own clicks
     * too): false cancels it before it reaches the server — the arm already swung, so on screen the
     * clicking never stops. Attacks go from the window on.
     */
    boolean allowAttack(Object entity) {
        CombatConfig c = mod.config;
        if (!c.autoclick || c.autoclickTiming != PRECISE || entity != target || lastHit < 0) {
            return true;
        }
        if (tick < open) {
            return false;
        }
        if (confirmed >= CONFIRM_HITS
                && tick > immuneUntil + 2
                && ++misses >= MISSES_TO_RAISE
                && immunity < MAX_IMMUNITY) {
            immunity++; // attacks past the end keep failing: the server is slower than we thought
            confirmed = 0;
            misses = 0;
            if (DEBUG) {
                mod.log().info("[precise] misses, immunity " + immunity);
            }
        }
        return true;
    }

    private void click() {
        Keys.click(0);
    }
}
