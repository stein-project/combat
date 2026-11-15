/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Screens;

/**
 * Auto Protect: the sword blocking by itself. Two parts, each with its own switch, under Auto
 * Protect's switch:
 *
 * <ul>
 *   <li><b>Block in menus</b>: with a screen open (inventory, a chest, the Panel), the sword in
 *       hand and blocking — whoever handles the inventory in the middle of a fight does not stand
 *       there with the arm down. Not with the chat, the Esc menu or a screen opened from it ({@link
 *       SmartEffect#menuOpen}): there nothing is used;
 *   <li><b>Low health block</b>: with health at the limit ({@link CombatConfig#protectLowHealth})
 *       in a fight, the sword blocking until health is back ({@link
 *       CombatConfig#protectResumeHealth}). While blocking the hit does not go out — the game does
 *       not hit with an item in use, nor with the autoclick —, so at close range the player stops
 *       taking mcMMO's counterattack (which returns the damage of whoever hits) and takes half the
 *       damage of whoever hits them.
 * </ul>
 *
 * <p><b>How "Low health block" behaves</b>, with two health marks (hysteresis):
 *
 * <ul>
 *   <li>enters with health at the low mark or below, in a fight (damage in the last 3 seconds or an
 *       enemy — neither ally nor in my clan — within 6 blocks) and with a sword in the inventory;
 *   <li>once in, stays until health reaches the high mark (or the maximum health, if lower).
 *       Meanwhile the sword blocks while there is a fight, and Smart Effect's Speed and Strength
 *       wait — health first, then the potions. With no fight (the enemy left), the sword goes down
 *       — the player can run away —, but the potions keep waiting for health;
 *   <li>the player switched hotbar slots in the middle of blocking (wheel or number key): they are
 *       in charge — the sword goes down for 3 seconds, and comes back after that if health is still
 *       low.
 * </ul>
 *
 * <p><b>The order with the rest</b> (the hand and the use key have a single owner, {@link
 * SmartEffect}, which asks here what to do): armor (Auto Armor), Regeneration (the apple), the
 * blocking from here, and only then Speed and Strength. Regeneration comes before blocking: eating
 * the apple is what brings health back, and blocking is what holds until it comes. When
 * Regeneration is missing, the sword goes down to eat and blocks again at the end. Auto Refill does
 * not touch the hand (only the bag, through the virtual chest) and goes on alongside; with its
 * chest open, the sword is only looked for on the hotbar.
 */
final class AutoProtect {

    /** After taking damage, for how many ticks it is still a fight (3 seconds). */
    private static final int COMBAT_WINDOW = 60;

    /** An enemy within this distance is a fight. */
    private static final int ENEMY_RADIUS = 6;

    /**
     * How long the sword stays down when the player switches slots in the middle of blocking (3
     * seconds).
     */
    private static final int OVERRIDE_TICKS = 60;

    private final SteinCombat mod;

    /**
     * "Low health block" acting: entered below the low mark and has not reached the high one yet.
     */
    private boolean holding;

    /** Ticks that still count as a fight after the last damage. */
    private int combatTicks;

    private float lastHealth = -1;

    /** Ticks of the sword down because the player switched slots. */
    private int override;

    /** A fight now (computed once per tick). */
    private boolean fighting;

    AutoProtect(SteinCombat mod) {
        this.mod = mod;
    }

    /**
     * At the start of Smart Effect's tick, before deciding the hand: health, the fight and the
     * marks.
     */
    void tick(Object me) {
        CombatConfig c = mod.config;
        if (me == null || !Entities.isAlive(me)) {
            reset();
            return;
        }
        float h = Entities.health(me);
        if (Entities.hurtTime(me) > 0 || lastHealth >= 0 && h < lastHealth) {
            combatTicks = COMBAT_WINDOW;
        } else if (combatTicks > 0) {
            combatTicks--;
        }
        lastHealth = h;
        if (override > 0) {
            override--;
        }
        if (!c.autoProtect || !c.protectSurvive) {
            holding = false;
            fighting = false;
            return;
        }
        fighting = combatTicks > 0 || SmartEffect.enemyNear(me, ENEMY_RADIUS);
        if (!holding) {
            if (h <= c.protectLowHealth && fighting && HandItems.hasSword()) {
                holding = true;
            }
        } else if (h >= Math.min(c.protectResumeHealth, Entities.maxHealth(me))) {
            holding = false;
            override = 0;
        }
    }

    /** "Block in menus": the switch on and a screen open, but not the Esc menus or the chat. */
    boolean defend() {
        CombatConfig c = mod.config;
        return c.autoProtect && c.protectDefend && Screens.isOpen() && !SmartEffect.menuOpen();
    }

    /**
     * "Low health block" wants the sword blocking now: acting, in a fight and without the player
     * taking the hand.
     */
    boolean survive() {
        return holding && fighting && override == 0;
    }

    /** Either part wants the sword blocking now. */
    boolean wantsBlock() {
        return defend() || survive();
    }

    /** "Low health block" acting (even with no fight now): Speed and Strength wait for health. */
    boolean holdsBack() {
        return holding;
    }

    /** The player switched slots in the middle of blocking: the sword goes down for a while. */
    void playerTookHand() {
        override = OVERRIDE_TICKS;
    }

    void reset() {
        holding = false;
        fighting = false;
        combatTicks = 0;
        override = 0;
        lastHealth = -1;
    }
}
