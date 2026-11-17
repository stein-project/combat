/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import static dev.xavier.stein.combat.HandItems.REGENERATION;
import static dev.xavier.stein.combat.HandItems.SPEED;
import static dev.xavier.stein.combat.HandItems.STRENGTH;

import dev.xavier.stein.loader.api.Effects;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Screens;

import java.util.List;

/**
 * Smart Effect: keeps the combat effects up, eating and drinking by itself, with any screen open or
 * none.
 *
 * <ul>
 *   <li><b>Auto Gapple</b>: Regeneration IV or more, with more than 3 seconds left — without it,
 *       eats the enchanted golden apple (like the reference script: until the effect is back);
 *   <li><b>Auto Speed</b> and <b>Auto Strength</b>: Speed and Strength active, with more than 3
 *       seconds left — drinks the potion (drinkable ones only, never splash).
 * </ul>
 *
 * <p><b>When:</b> with no condition on, always (it eats whenever something is missing). With "wait
 * for an enemy nearby", only with a player who is neither ally nor in my clan within so many
 * blocks; with "after taking damage", in the seconds after a hit. Both: either one.
 *
 * <p><b>Priorities:</b> Regeneration before everything; then Auto Protect's blocking sword ({@link
 * AutoProtect}, which only decides: the hand and the use key are this class's, a single owner);
 * Speed and Strength wait — and, with "Low health block" acting, they wait for health to come back.
 * Armor (Auto Armor) comes first: in the tick it acts, this starts nothing (both touch the
 * inventory) — but a use already started goes on, because its clicks do not take the item out of
 * the hand.
 *
 * <p><b>How:</b> brings the item to the hotbar (if it is in the bag), selects the slot, uses the
 * item and holds the use key until it is done; then goes back to the last weapon used (sword, axe
 * or bow).
 *
 * <p><b>With a window open</b> (the inventory, a chest, the chat, the Esc menu...) everything goes
 * on: the apple, the potions and the sword. The use goes out directly (the game reads no keys with
 * a window open), and a use started goes on until it is done; no window is ever closed.
 */
final class SmartEffect {

    /** {@code -Dsteincombat.debugNotices=true}: the notices (no item, swap, chest) in the log. */
    private static final boolean NOTICES = Boolean.getBoolean("steincombat.debugNotices");

    /** {@code -Dsteincombat.debugEffect=true}: every step in the log (start, finish, block). */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugEffect");

    /** More than this remaining time, in ticks (3 seconds), counts as active. */
    private static final int MIN_TICKS = 60;

    /** After taking damage, for how many ticks the "after taking damage" condition holds. */
    private static final int DAMAGE_WINDOW = 60;

    /** Ticks waiting for the effect after finishing eating, before trying again. */
    private static final int WAIT_EFFECT = 6;

    /** Eating takes 32 ticks; using for longer than this, the server got lost. */
    private static final int MAX_USE = 40;

    private final SteinCombat mod;

    /**
     * When to block with the sword: this class owns the hand and the use key, Auto Protect only
     * decides.
     */
    final AutoProtect protect;

    /** The effect being sought now (0 = idle). */
    private int effect;

    /** The hotbar slot with the item, while using it. */
    private int slot = -1;

    private int ticks;

    /** Ticks idle (not using) since the last use. */
    private int idle;

    private int usingTicks;
    private int retries;

    /** Left the slot for one tick to reset the use on the server. */
    private boolean resyncing;

    /** The last hotbar slot with a weapon in hand. */
    private int weaponSlot = -1;

    private int damageTicks;
    private float lastHealth = -1;
    private boolean holding;

    /** Blocking with the sword (Auto Protect). */
    private boolean blocking;

    /** The hotbar slot where the sword is blocking (to see the player switch in the middle). */
    private int blockSlot = -1;

    /** Ticks in a row that armor deferred the rest. */
    private int deferred;

    /** Already warned that the item is missing (warns again once there is one). */
    private final boolean[] warned = new boolean[3];

    SmartEffect(SteinCombat mod) {
        this.mod = mod;
        this.protect = new AutoProtect(mod);
    }

    /** Using the hand now: eating, drinking, switching slots or blocking with the sword. */
    boolean busy() {
        return effect != 0 || blocking || resyncing;
    }

    /**
     * At the end of every tick, after Auto Armor ({@code armorActed}: it touched the inventory in
     * this tick).
     *
     * <p>The order: armor, Regeneration, blocking with the sword (Auto Protect's "Low health
     * block"), Speed and Strength. Armor defers the rest by one tick only: an Auto Armor that tries
     * and fails to equip (and would try again every tick) must not hold the apple back.
     */
    void tick(boolean armorActed) {
        CombatConfig c = mod.config;
        Object me = Entities.self();
        protect.tick(me);
        if (me == null) {
            reset();
            return;
        }
        trackDamage(me);
        if (blocking && !Screens.isOpen() && Inventory.selectedHotbar() != blockSlot) {
            // The player switched slots in the middle of blocking (wheel or number key): they are
            // in
            // charge. The key is released and the hand stays where they put it; "Low health block"
            // waits a while before taking the sword again.
            release();
            blocking = false;
            protect.playerTookHand();
        }
        if (effect == 0 && !blocking) {
            trackWeapon();
        }
        boolean guard = protect.wantsBlock();
        // "Low health block" acting: health first, then the potions (Speed and Strength wait for
        // health to come back). Blocking only because a window is open waits for the potions.
        boolean wait = protect.survive() || protect.holdsBack();
        boolean defer = armorActed && deferred < 1;
        deferred = armorActed ? deferred + 1 : 0;
        boolean effects = c.smartEffect && active(c, me);
        if (effect != 0) {
            if (!c.smartEffect) {
                finish();
            } else if (effect != REGENERATION && wait) {
                abort(); // Speed and Strength give way to blocking and to health
            } else {
                continueUsing(me);
                return;
            }
        }
        // Regeneration before blocking: the apple is what brings health back. With "Low health
        // block" acting it applies even outside the "when" conditions — that is already a fight,
        // and health is at the limit.
        boolean regen = c.smartEffect && c.autoGapple && (effects || protect.holdsBack());
        if (!defer && regen && missing(me, REGENERATION, 3)) {
            if (blocking) {
                release(); // the sword goes down to eat; blocking comes back when the apple is done
                blocking = false;
            }
            if (start(REGENERATION, 0)) {
                return;
            }
        }
        if (guard && wait) {
            if (!defer) {
                block();
            }
            return;
        }
        if (!defer && effects && !wait && potion(c, me)) {
            return;
        }
        if (guard) {
            if (!defer) {
                block();
            }
            return;
        }
        if (blocking) {
            endBlock();
        }
    }

    /** Speed, then Strength, when missing: true if one started (the sword goes down to drink). */
    private boolean potion(CombatConfig c, Object me) {
        boolean speed = c.autoSpeed && missing(me, SPEED, 0);
        boolean strength = c.autoStrength && missing(me, STRENGTH, 0);
        if (!speed && !strength) {
            return false;
        }
        if (blocking) {
            release();
            blocking = false;
        }
        if (speed && start(SPEED, 1)) {
            return true;
        }
        return strength && start(STRENGTH, 2);
    }

    /**
     * Auto Protect ({@link AutoProtect}): the sword in hand and blocking, with the use key held
     * (without it the game drops the block in the next tick). When it no longer wants it (the
     * screen closed, health came back), the key is released and the hand goes back to the last
     * weapon.
     */
    private void block() {
        int sword = swordSlot();
        if (sword < 0) {
            if (blocking) {
                // The sword left the hand (broke, dropped): no key held with another item.
                endBlock();
            }
            return;
        }
        if (Inventory.selectedHotbar() != sword) {
            Inventory.selectHotbar(sword);
        }
        blockSlot = sword;
        if (!Inventory.isUsingItem()) {
            Inventory.useHeldItem();
            debug("blocks in slot " + sword);
        }
        // The game releases the item use if the use key is not pressed: held only while actually
        // blocking.
        if (Inventory.isUsingItem()) {
            hold();
        } else {
            release();
        }
        blocking = true;
    }

    private void endBlock() {
        blocking = false;
        release();
        if (weaponSlot >= 0
                && HandItems.isWeapon(Inventory.stack(weaponSlot))
                && Inventory.selectedHotbar() != weaponSlot) {
            Inventory.selectHotbar(weaponSlot);
        }
    }

    /**
     * A sword in the hotbar (the last weapon's, if it is a sword); only in the bag, it comes to the
     * hotbar. -1 with none.
     */
    private int swordSlot() {
        if (weaponSlot >= 0 && HandItems.isSword(Inventory.stack(weaponSlot))) {
            return weaponSlot;
        }
        for (int i = 0; i < 9; i++) {
            if (HandItems.isSword(Inventory.stack(i))) {
                return i;
            }
        }
        if (mod.coordinator.chestTrip()) {
            // With a chest trip's chest open the bag is the trip's: only the sword on the hotbar.
            return -1;
        }
        for (int i = Inventory.MAIN_START; i < Inventory.ARMOR_START; i++) {
            if (HandItems.isSword(Inventory.stack(i))) {
                int hotbar = targetHotbar();
                int from = Inventory.slotOf(i);
                return from >= 0 && Inventory.swapHotbar(from, hotbar) ? hotbar : -1;
            }
        }
        return -1;
    }

    /** Drops a use in the middle (Speed or Strength giving way to blocking). */
    private void abort() {
        release();
        effect = 0;
        slot = -1;
    }

    /** The conditions: none on = always; otherwise, an enemy nearby or recent damage. */
    private boolean active(CombatConfig c, Object me) {
        if (!c.effectWaitEnemy && !c.effectOnDamage) {
            return true;
        }
        if (c.effectOnDamage && damageTicks > 0) {
            return true;
        }
        return c.effectWaitEnemy && enemyNear(me, c.effectEnemyRadius);
    }

    /** A living player who is neither ally nor in my clan within {@code radius} blocks. */
    static boolean enemyNear(Object me, int radius) {
        for (Object p : Entities.players()) {
            if (p == me || !Entities.isAlive(p)) {
                continue;
            }
            // the distance first: it runs every tick over every player, and the relation (a name
            // and a cache lookup) is only needed for the few within reach; both have no side
            // effect, so the answer is the same
            if (Entities.distance(me, p) > radius) {
                continue;
            }
            int r = CombatApi.relation(Entities.name(p));
            if (r != CombatApi.ALLY && r != CombatApi.CLAN) {
                return true;
            }
        }
        return false;
    }

    private void trackDamage(Object me) {
        float h = Entities.health(me);
        if (Entities.hurtTime(me) > 0 || lastHealth >= 0 && h < lastHealth) {
            damageTicks = DAMAGE_WINDOW;
        } else if (damageTicks > 0) {
            damageTicks--;
        }
        lastHealth = h;
    }

    /** Remembers the slot of the weapon in hand (sword, axe or bow), to go back to it later. */
    private void trackWeapon() {
        int sel = Inventory.selectedHotbar();
        if (HandItems.isWeapon(Inventory.stack(sel))) {
            weaponSlot = sel;
        }
    }

    /**
     * Is the effect missing, ending in less than 3 seconds, or below the minimum level (amplifier)?
     */
    private static boolean missing(Object me, int id, int minAmplifier) {
        List<Effects.Effect> list = Effects.of(me);
        for (Effects.Effect e : list) {
            if (e.id == id
                    && e.amplifier >= minAmplifier
                    && (e.permanent || e.duration > MIN_TICKS)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the item, brings it to the hotbar, selects it and starts using it. False if there is no
     * item.
     */
    private boolean start(int id, int warnIndex) {
        int index = HandItems.find(id);
        if (index < 0) {
            if (!warned[warnIndex]) {
                warned[warnIndex] = true;
                notice(Lang.tr("steincombat.effect.none", Lang.tr(HandItems.nameKey(id))));
            }
            return false;
        }
        warned[warnIndex] = false;
        int hotbar = index;
        if (index >= Inventory.MAIN_START) {
            // From the bag to the hotbar: an empty slot, or one that is not the weapon's.
            hotbar = targetHotbar();
            int from = Inventory.slotOf(index);
            if (from < 0 || !Inventory.swapHotbar(from, hotbar)) {
                return false;
            }
        }
        debug("starts " + id + " in slot " + hotbar);
        effect = id;
        slot = hotbar;
        ticks = 0;
        idle = 0;
        usingTicks = 0;
        retries = 0;
        resyncing = false;
        Inventory.selectHotbar(slot);
        use();
        return true;
    }

    private void continueUsing(Object me) {
        ticks++;
        if (resyncing) {
            resyncing = false;
            Inventory.selectHotbar(slot);
            use();
            return;
        }
        if (!missing(me, effect, effect == REGENERATION ? 3 : 0)) {
            finish();
            return;
        }
        if (Inventory.selectedHotbar() != slot) {
            Inventory.selectHotbar(slot); // the player switched in the middle: go back
        }
        Object held = Inventory.stack(slot);
        if (held == null || !HandItems.matches(held, effect)) {
            // That slot's item ran out (or the slot changed): look for another of the same effect.
            int id = effect;
            release();
            effect = 0;
            if (!start(id, id == REGENERATION ? 0 : id == SPEED ? 1 : 2)) {
                finish();
            }
            return;
        }
        if (Inventory.isUsingItem()) {
            idle = 0;
            hold();
            if (++usingTicks > MAX_USE) {
                // Eating takes 32 ticks: past that, the server is not keeping up. Start over.
                resync();
            }
            return;
        }
        usingTicks = 0;
        // With no item in use, the use key is not kept pressed (the game would take it as a right
        // click on the crosshair).
        release();
        // It stopped using. Just finished eating: the effect comes from the server a moment later —
        // wait a little before eating another (otherwise it would be two apples for one). The
        // effect
        // did not come: the server did not accept the use (the item came back, or stayed). Start
        // over — switch slots and back, which resets the use on the server — and eat again.
        if (++idle < WAIT_EFFECT && ticks > 1) {
            return;
        }
        idle = 0;
        if (ticks > 1) {
            resync();
            return;
        }
        use();
    }

    /**
     * The server lost the use (eaten on the client and not on the server, or the other way round).
     * Release the key and leave the slot for one tick: the slot change reaches the server and
     * resets the use there; in the next tick it comes back and eats again.
     */
    private void resync() {
        retries++;
        usingTicks = 0;
        release();
        debug("use not confirmed, attempt " + retries);
        int other = slot == 8 ? 7 : slot + 1;
        Inventory.selectHotbar(other);
        resyncing = true;
    }

    private void use() {
        Inventory.useHeldItem();
        if (Inventory.isUsingItem()) {
            hold(); // only held with the item in use
        }
    }

    /** Holds the use key: with no screen open, the game stops using when it is released. */
    private void hold() {
        Keys.game(Keys.GameKey.USE).hold(true);
        holding = true;
    }

    private void release() {
        if (holding) {
            Keys.game(Keys.GameKey.USE).hold(false);
            holding = false;
        }
    }

    /**
     * Done: releases the key and goes back to the last weapon (or the first weapon on the hotbar).
     */
    private void finish() {
        debug("finishes " + effect + ", back to " + weaponSlot);
        release();
        effect = 0;
        slot = -1;
        int back =
                weaponSlot >= 0 && HandItems.isWeapon(Inventory.stack(weaponSlot))
                        ? weaponSlot
                        : HandItems.firstWeapon();
        if (back >= 0) {
            Inventory.selectHotbar(back);
        }
    }

    private void reset() {
        release();
        blocking = false;
        blockSlot = -1;
        effect = 0;
        slot = -1;
        lastHealth = -1;
    }

    /**
     * A hotbar slot to bring the item to: an empty one; otherwise, the first that is not a weapon.
     */
    private int targetHotbar() {
        for (int i = 0; i < 9; i++) {
            if (Inventory.stack(i) == null) {
                return i;
            }
        }
        for (int i = 8; i >= 0; i--) {
            if (i != weaponSlot && !HandItems.isWeapon(Inventory.stack(i))) {
                return i;
            }
        }
        return weaponSlot == 8 ? 7 : 8;
    }

    private void debug(String text) {
        if (DEBUG) {
            mod.log().info("[smart] " + text);
        }
    }

    /**
     * What happened (no item, swap done, empty chest...). Not on the action bar: only turning on
     * and off shows there (the user's request); with {@code -Dsteincombat.debugNotices=true}, in
     * the log.
     */
    private void notice(String text) {
        if (NOTICES) {
            mod.log().info("[Smart Effect] " + text);
        }
    }
}
