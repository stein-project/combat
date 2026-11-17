/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Stacks;

/**
 * Auto Refill: when the inventory's armor spares run out (or fall below the target, in constant
 * mode), sends the server's virtual chest command ("/bau vip"), waits for the chest to open and
 * brings the armor into the bag — with the chest screen hidden (the player keeps playing) or in
 * view, as the player chooses.
 *
 * <p>One step every 100 ms, and every step ends with the cursor empty (Smart Effect may touch the
 * inventory in between). Bringing is: with a free space in the bag, pick from the chest and drop
 * there; with a worn piece in the bag (and "return worn pieces" on, or the bag full), <b>swap</b> —
 * the chest's piece goes where the worn one was and the worn one goes to its place in the chest,
 * three clicks. Swapping is what works with a full chest and a full bag. With a full bag, no worn
 * piece and a piece with no spare at all, another bag item is swapped instead (never one from the
 * hotbar; ordinary items first, then a surplus spare of another piece, then items to fight with:
 * {@link ArmorSlots#makeRoom}), so the player is never left without a piece. With nothing to bring,
 * the leftover worn pieces go to the chest, if they fit.
 *
 * <p>The rules are Auto Armor's for each piece ({@link ArmorSlots}): only the pieces turned on, the
 * enchantment filter, and the protection priority (highest or lowest first); worn is at the rule's
 * durability threshold.
 *
 * <p>It works with Auto Armor on or not: it only reads the rules, which apply even with Auto Armor
 * off. Together, Auto Armor puts on and the refill restocks the bag; alone, the refill only keeps
 * the bag stocked with spares.
 *
 * <p>Three modes: when a piece's spares run out, constant and on key — then Auto Refill does not
 * stay on: the key (or the Panel button) makes one trip to the chest right away, up to the target,
 * and it turns itself off at the end.
 *
 * <p><b>Constant</b> keeps no count. A worn piece in the inventory (Auto Armor took it off at the
 * threshold) goes to the chest in exchange for a new one of the same kind, picked by the piece's
 * rule; with no such piece in the chest, the worn one stays. A piece the player is about to lack
 * (its slot empty, or the worn one on at the threshold, and nothing of the kind in the inventory)
 * gets one from the chest; with none that fits and "use worn pieces from the chest" on ({@link
 * CombatConfig#refillUseWorn}), the worn piece with the most durability left in the chest comes
 * instead, so the player never fights without it.
 *
 * <p>In constant mode Auto Armor always goes first: no trip starts while it would act ({@link
 * AutoArmor#wouldAct}), and a trip in progress closes the chest as soon as a piece's slot is empty
 * with a spare at hand ({@link AutoArmor#urgent}; the chest window has no armor slots). A piece the
 * chest did not have is not asked for again for a minute, so a chest without it is not opened over
 * and over.
 *
 * <p><b>Checked with the server</b>, as in Auto Armor: the client applies each click at once, and a
 * click the server silently refuses would leave the client thinking it brought what it did not. So:
 *
 * <ul>
 *   <li>each step checks its own result; three steps in a row with no effect ask for the window's
 *       real contents (Inventory.resync) and wait for them before going on;
 *   <li>before closing, if there was a click since the last check, it checks once more — if the
 *       server undid something, the work goes on;
 *   <li>an item left on the cursor (a click refused in the middle of a swap) goes back to a free
 *       place in the next step;
 *   <li>after closing, the bag is checked with the server (the player's window);
 *   <li>nothing gets stuck: the checks per trip are capped, the trip has a maximum time, and trips
 *       that fail in a row wait longer and longer before the next.
 * </ul>
 */
final class AutoRefill {

    /** {@code -Dsteincombat.debugNotices=true}: the notices (no item, swap, chest) in the log. */
    private static final boolean NOTICES = Boolean.getBoolean("steincombat.debugNotices");

    /** {@code -Dsteincombat.debugRefill=true}: every step in the log. */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugRefill");

    static final int WHEN_EMPTY = 0;
    static final int CONSTANT = 1;
    static final int ON_KEY = 2;

    /** 100 ms between steps (2 ticks). */
    private static final int STEP_TICKS = 2;

    /** How long to wait for the chest to open after the command. */
    private static final int OPEN_TIMEOUT = 60;

    /** Maximum time with the chest open on one trip. */
    private static final int SESSION_TIMEOUT = 300;

    /** Steps in a row with no effect before asking for the real contents. */
    private static final int STUCK_STEPS = 3;

    /** Checks with the server per trip, at most. */
    private static final int MAX_RESYNCS = 4;

    /** How long to wait for the requested contents; with no answer, it goes on with what it has. */
    private static final int RESYNC_TIMEOUT = 40;

    /** After closing the chest, the bag is checked with the server in this many ticks. */
    private static final int VERIFY_TICKS = 10;

    /** A step's result. */
    private static final int NOTHING = 0;

    private static final int ACTED = 1;
    private static final int STUCK = 2;

    private enum State {
        IDLE,
        WAITING,
        WORKING
    }

    private final SteinCombat mod;
    private State state = State.IDLE;
    private int timer;
    private int cooldown;
    private int stepWait;

    /** Pieces brought on this trip. */
    private int moved;

    /**
     * Constant mode: how long the chest is known to lack a fitting piece, and any piece, per piece.
     */
    private static final int LACK_TICKS = 1200;

    private final long[] lacksFitUntil = new long[4];
    private final long[] lacksAnyUntil = new long[4];

    /**
     * The spares that fit when the chest opened: the notice counts what the trip gained, not the
     * redone clicks.
     */
    private int startSpares;

    /** This trip's chest window (to tell the server if the player opens another screen over it). */
    private int windowId;

    /** Steps in a row with no effect. */
    private int stuck;

    /** Checks asked for on this trip. */
    private int resyncs;

    /** Waiting for the requested contents for this many ticks (0 not waiting). */
    private int awaiting;

    /** There was a click since the last check. */
    private boolean dirty;

    /** The chest slot of the last item picked (where an item left on the cursor goes back). */
    private int lastFrom = -1;

    /** The bag was full with no worn piece to swap on this trip (the end notice). */
    private boolean bagFull;

    /** The chest did not take a worn piece on this trip: no further try until the next. */
    private boolean depositBlocked;

    /** Ticks until the bag is checked after closing the chest (0 nothing set). */
    private int verifyIn;

    /**
     * Trips in a row that brought nothing because of refusal or time: the wait until the next
     * doubles.
     */
    private int failStreak;

    AutoRefill(SteinCombat mod) {
        this.mod = mod;
    }

    /** Ticks counted by {@link #tick}, and the tick the current trip started. */
    private long now;

    private long tripStart;

    /**
     * The armor pieces (worn and in the bag) when the last trip closed, for the server's answer.
     */
    private int expectSpares = -1;

    /** The bag check after a trip was asked: its answer is compared with {@link #expectSpares}. */
    private boolean verifying;

    /** Is the refill chest open (or about to open)? */
    boolean busy() {
        return state != State.IDLE;
    }

    /** Waiting for the chest to open after the command. */
    boolean opening() {
        return state == State.WAITING;
    }

    /** Ticks since the current trip started (the command was sent). */
    int tripTicks() {
        return state == State.IDLE ? 0 : (int) (now - tripStart);
    }

    /**
     * About how many ticks the current trip still needs: a step per piece still missing, and the
     * check before closing; -1 while the chest has not opened.
     */
    int remainingTicks() {
        if (state != State.WORKING) {
            return -1;
        }
        CombatConfig c = mod.config;
        int missing = 0;
        for (int piece = 0; piece < 4; piece++) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode != AutoArmor.OFF) {
                missing += Math.max(0, Math.max(1, c.refillTarget) - AutoArmor.spares(piece, r));
            }
        }
        return missing * STEP_TICKS * 2 + (dirty ? RESYNC_TIMEOUT / 4 : 0) + STEP_TICKS;
    }

    /**
     * Auto Stash takes the open chest over (health low): this trip ends here, without closing the
     * chest, and gives its window. -1 while the chest is not open yet or an item is on the cursor
     * (not a safe point: Auto Stash waits for the next tick).
     */
    int handOver() {
        if (state != State.WORKING || Inventory.cursor() != null) {
            return -1;
        }
        log("chest handed over to Auto Stash");
        state = State.IDLE;
        cooldown = 100;
        verifyIn = 0;
        done(mod.config);
        return windowId;
    }

    /**
     * The server sent a whole window (SteinMod.onWindowItems): the chest's is what the check waited
     * for.
     */
    void windowItems(int id) {
        if (awaiting > 0 && state == State.WORKING && id == windowId) {
            awaiting = 0;
            log("real contents arrived");
        }
        if (id == 0 && verifying && state == State.IDLE) {
            verifying = false;
            int pieces = armorPieces();
            if (expectSpares >= 0 && pieces < expectSpares) {
                // The client was showing pieces the server never gave (ghost items): the server's
                // bag replaced them, so the next trip may go at once. All the armor is counted,
                // worn and in the bag, so Auto Armor putting a spare on in between is no ghost.
                log("ghost items: " + (expectSpares - pieces) + " pieces were not real");
                cooldown = 0;
                failStreak = 0;
            }
        }
    }

    void tick() {
        now++;
        CombatConfig c = mod.config;
        if (Entities.self() == null) {
            state = State.IDLE;
            verifyIn = 0;
            if (c.autoRefill && c.refillMode == ON_KEY) {
                done(c); // the request does not carry over to the next world
            }
            return;
        }
        switch (state) {
            case IDLE -> idle(c);
            case WAITING -> waiting(c);
            case WORKING -> work(c);
        }
    }

    private void idle(CombatConfig c) {
        boolean onKey = c.refillMode == ON_KEY;
        if (verifyIn > 0 && --verifyIn == 0) {
            if (Inventory.isContainerOpen()) {
                verifyIn = 1; // the player opened another window: check when it closes
            } else {
                Inventory.resync();
                verifying = true;
                log("bag checked with the server");
            }
        }
        if (cooldown > 0 && !onKey) { // on key, whoever asked wants it now
            cooldown--;
            return;
        }
        if (!c.autoRefill || verifyIn > 0) {
            return; // the previous trip's check comes before deciding another
        }
        if (onKey && !needs(c)) {
            notice(Lang.tr("steincombat.refill.full"));
            done(c);
            return;
        }
        if (onKey && !roomToBring(c)) {
            notice(Lang.tr("steincombat.refill.bagFull"));
            done(c);
            return;
        }
        // Never over one of the player's inventory screens: the chest would take its place. With
        // no room to bring anything (bag full and no worn piece to swap), it does not even open:
        // it waits for a free space or a worn piece, and goes in that tick — without the long wait
        // of a wasted trip. Nor with Auto Armor swapping or waiting for its check: the server runs
        // commands in a queue of their own, so the chest could open before the armor clicks and
        // the check sent earlier — and the server drops clicks for a window that is not the open
        // one, without a word.
        if (!Inventory.isContainerOpen()
                && !Inventory.isScreenOpen()
                && !mod.armorSettling()
                && !(c.refillMode == CONSTANT && AutoArmor.wouldAct(c))
                && !mod.autoStash.busy()
                && needs(c)
                && roomToBring(c)) {
            if (!c.refillShowWindow) {
                // The chest comes virtual: open on the server, with no screen on the client — not
                // even a flicker.
                Inventory.openVirtualNext(3000);
            }
            Chat.send(c.refillCommand.trim());
            log("command sent: " + c.refillCommand.trim());
            state = State.WAITING;
            tripStart = now;
            timer = OPEN_TIMEOUT;
        }
    }

    private void waiting(CombatConfig c) {
        if (Inventory.isContainerOpen()) {
            if (!c.refillShowWindow && Inventory.isScreenOpen()) {
                // The virtual one did not take (another way of opening): hide it.
                Inventory.hideContainerScreen();
            }
            log(
                    "chest open: "
                            + Inventory.containerSize()
                            + " slots, window "
                            + (c.refillShowWindow ? "in view" : "hidden"));
            state = State.WORKING;
            windowId = Inventory.windowId();
            timer = SESSION_TIMEOUT;
            stepWait = STEP_TICKS;
            moved = 0;
            startSpares = ArmorSlots.totalSpares(c);
            stuck = 0;
            resyncs = 0;
            awaiting = 0;
            dirty = false;
            lastFrom = -1;
            bagFull = false;
            depositBlocked = false;
        } else if (--timer <= 0) {
            Inventory.openVirtualNext(0);
            log(
                    "the chest did not open (window "
                            + Inventory.windowId()
                            + ", screen "
                            + Inventory.isScreenOpen()
                            + ")");
            notice(Lang.tr("steincombat.refill.noChest"));
            state = State.IDLE;
            cooldown = 200;
            done(c);
        }
    }

    private void work(CombatConfig c) {
        if (!Inventory.isContainerOpen() || Inventory.windowId() != windowId) {
            closedUnderneath(c);
            return;
        }
        if (--timer <= 0) {
            finish(false);
            return;
        }
        if (awaiting > 0) {
            if (--awaiting == 0) {
                log("the requested contents did not arrive"); // go on with what the client has
            }
            return;
        }
        if (--stepWait > 0) {
            return;
        }
        stepWait = STEP_TICKS;
        if (c.refillMode == CONSTANT && Inventory.cursor() == null && AutoArmor.urgent(c)) {
            // A piece's slot is empty and a spare is here: the chest window has no armor slots,
            // so it closes now and Auto Armor puts it on in that tick. (A worn piece on waits for
            // the trip: a few ticks more for it beat another trip.)
            log("closes for Auto Armor");
            finish(true);
            return;
        }
        int r = c.refillMode == CONSTANT ? constantStep(c) : step(c);
        if (r == ACTED) {
            dirty = true;
            stuck = 0;
            return;
        }
        if (r == STUCK) {
            dirty = true;
            if (++stuck >= STUCK_STEPS) {
                stuck = 0;
                if (!resync("steps with no effect")) {
                    finish(false); // checked what it could and the server keeps refusing
                }
            }
            return;
        }
        // The chest opened, but the items come in a packet afterwards: empty in the first ticks
        // means wait, not "done".
        if (moved == 0 && SESSION_TIMEOUT - timer < 20 && ArmorSlots.chestEmpty()) {
            return;
        }
        // Nothing more to do by what the client sees. If there was a click since the last check,
        // check before closing: the server may have silently undone one.
        if (dirty && resync("check before closing")) {
            dirty = false;
            return;
        }
        finish(true);
    }

    /**
     * The chest is no longer the open window: the player opened another one, or the server closed
     * it.
     */
    private void closedUnderneath(CombatConfig c) {
        if (Inventory.isScreenOpen() || Inventory.windowId() != windowId) {
            // The player opened the inventory (or another window) over the hidden chest: the client
            // already switched windows, the server still thinks the chest is open and would refuse
            // the player's clicks. Close the chest there, without touching their screen; the trip
            // comes back when they close it.
            Inventory.closeWindowOnServer(windowId);
            log(
                    "the player opened another window: chest closed on the server (window "
                            + Inventory.windowId()
                            + " instead of "
                            + windowId
                            + ", screen "
                            + Inventory.isScreenOpen()
                            + ")");
            state = State.IDLE;
            cooldown = 20;
            verifyIn = VERIFY_TICKS;
            done(c);
            return;
        }
        log("the server closed the chest");
        state = State.IDLE;
        cooldown = 40;
        verifyIn = VERIFY_TICKS;
        done(c);
    }

    /** Asks for the real contents of the chest and the bag; false if the trip's checks ran out. */
    private boolean resync(String why) {
        if (resyncs >= MAX_RESYNCS || !Inventory.resync()) {
            return false;
        }
        resyncs++;
        awaiting = RESYNC_TIMEOUT;
        log("contents asked from the server: " + why);
        return true;
    }

    /** One step: returns the cursor's item, brings (or swaps) one piece, or stores a worn one. */
    private int step(CombatConfig c) {
        if (Inventory.cursor() != null) {
            int to = placeForCursor();
            log("item on the cursor goes back to slot " + to);
            if (to < 0) {
                return STUCK;
            }
            Inventory.pickup(to);
            return Inventory.cursor() == null ? ACTED : STUCK;
        }
        int free = ArmorSlots.freeSlot();
        for (int piece : ArmorSlots.neediestFirst(c)) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode == AutoArmor.OFF
                    || AutoArmor.spares(piece, r) >= Math.max(1, c.refillTarget)) {
                continue;
            }
            int from = ArmorSlots.bestInChest(piece, r);
            if (from < 0) {
                continue;
            }
            // With a worn piece in the bag, swap: the chest's one in its place and it in the
            // chest's place. Only with "return worn pieces" on or no space in the bag — otherwise
            // the worn piece belongs to the player and stays.
            int give = c.refillDeposit || free < 0 ? ArmorSlots.worn(c, piece) : -1;
            if (give < 0 && free < 0 && AutoArmor.spares(piece, r) == 0) {
                // Full bag, no worn piece and no spare at all: another bag item goes to the
                // chest in its place, rather than leaving the player without the piece. Only
                // for a piece with none, so a second spare never costs the player an item.
                give = ArmorSlots.makeRoom(c, piece);
            }
            lastFrom = from;
            if (give >= 0) {
                return swapIn(piece, r, from, give);
            }
            if (free < 0) {
                bagFull = true; // full bag and nothing to swap: this piece goes without
                continue;
            }
            return pullIn(piece, from, free);
        }
        // Nothing to bring: the leftover worn pieces go to the chest, if it has room.
        if (c.refillDeposit && !depositBlocked) {
            int g = ArmorSlots.worn(c, -1);
            if (g >= 0) {
                return deposit(g);
            }
        }
        return NOTHING;
    }

    /**
     * Constant mode's step: a worn piece in the inventory goes to the chest for a new one of its
     * kind; a piece the player is about to lack gets one (a worn one from the chest, if allowed and
     * there is nothing better).
     */
    private int constantStep(CombatConfig c) {
        if (Inventory.cursor() != null) {
            return step(c); // the item left on the cursor goes back first
        }
        int free = ArmorSlots.freeSlot();
        for (int piece : ArmorSlots.neediestFirst(c)) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode == AutoArmor.OFF) {
                continue;
            }
            int from = ArmorSlots.bestInChest(piece, r);
            int worn = ArmorSlots.wornOf(c, piece);
            if (worn >= 0) {
                if (from < 0) {
                    lacksFitUntil[piece] = now + LACK_TICKS; // the worn one stays
                    continue;
                }
                lastFrom = from;
                return swapIn(piece, r, from, worn);
            }
            // With the chest open anyway, every piece leaves with a spare in the bag.
            if (AutoArmor.spares(piece, r) > 0) {
                continue;
            }
            if (from < 0) {
                lacksFitUntil[piece] = now + LACK_TICKS;
                from = c.refillUseWorn && lacking(c, piece) ? ArmorSlots.wornInChest(piece, r) : -1;
                if (from < 0) {
                    lacksAnyUntil[piece] = now + LACK_TICKS;
                    continue;
                }
            }
            lastFrom = from;
            int give = free < 0 ? ArmorSlots.makeRoom(c, piece) : -1;
            if (give >= 0) {
                return swapIn(piece, r, from, give);
            }
            if (free < 0) {
                bagFull = true;
                continue;
            }
            return pullIn(piece, from, free);
        }
        return NOTHING;
    }

    /**
     * The player is about to be without the piece: its slot is empty or the one on is at the
     * threshold, and the inventory has nothing of the kind left (not even a worn one).
     */
    private static boolean lacking(CombatConfig c, int piece) {
        AutoArmor.Rule r = c.armorRule(piece);
        Object on = Inventory.armor(piece);
        boolean going =
                on == null
                        || Stacks.isDamageable(on)
                                && Stacks.maxDamage(on) - Stacks.damage(on) <= r.threshold;
        return going && ArmorSlots.anyOf(piece) < 0;
    }

    /**
     * The chest's piece into the worn one's place, and the worn one into the chest: three clicks.
     */
    private int swapIn(int piece, AutoArmor.Rule r, int from, int give) {
        int to = Inventory.slotOf(give);
        log("swaps piece " + piece + ": chest " + from + " <-> bag " + to);
        if (to < 0 || !Inventory.pickup(from)) {
            return STUCK;
        }
        Inventory.pickup(to);
        Inventory.pickup(from);
        Object got = Inventory.stack(give);
        boolean ok =
                Inventory.cursor() == null
                        && got != null
                        && Inventory.armorPiece(got) == piece
                        && (AutoArmor.usable(got, piece, r) || c().refillUseWorn);
        if (ok) {
            moved++;
        }
        return ok ? ACTED : STUCK;
    }

    /** The chest's piece into a free space of the bag: two clicks. */
    private int pullIn(int piece, int from, int free) {
        int to = Inventory.slotOf(free);
        log("pulls piece " + piece + " from slot " + from + " to " + to);
        if (to < 0 || !Inventory.pickup(from)) {
            return STUCK;
        }
        Inventory.pickup(to);
        boolean ok = Inventory.cursor() == null && Inventory.stack(free) != null;
        if (ok) {
            moved++;
        }
        return ok ? ACTED : STUCK;
    }

    /** A leftover worn piece into the chest, with a shift-click. */
    private int deposit(int g) {
        if (ArmorSlots.chestFree() < 0) {
            depositBlocked = true;
            log("chest full: the leftover worn pieces stay in the bag");
            return NOTHING;
        }
        int slot = Inventory.slotOf(g);
        log("stores the worn piece of slot " + slot);
        if (slot < 0 || !Inventory.quickMove(slot)) {
            return STUCK;
        }
        return Inventory.stack(g) == null ? ACTED : STUCK;
    }

    private void finish(boolean done) {
        if (DEBUG) {
            int n = 0;
            for (int i = 0; i < Inventory.containerSize(); i++) {
                n += Inventory.slotStack(i) != null ? 1 : 0;
            }
            log(
                    "chest with "
                            + n
                            + " items, window "
                            + Inventory.windowId()
                            + ", free "
                            + ArmorSlots.freeSlot());
        }
        log("closes: " + moved + " brought, " + (done ? "finished" : "timed out or refused"));
        mod.supplies.chestSeen();
        Inventory.closeContainer();
        expectSpares = armorPieces();
        state = State.IDLE;
        verifyIn = VERIFY_TICKS;
        if (moved > 0) {
            notice(
                    Lang.tr(
                            "steincombat.refill.done",
                            Math.max(0, ArmorSlots.totalSpares(mod.config) - startSpares)));
            failStreak = 0;
            cooldown = 40;
        } else if (done) {
            // Nothing to bring. Bag full: the next trip only goes with room (roomToBring), so the
            // wait is short. The chest without fitting armor: 10 s, so as not to send the command
            // over and over.
            notice(Lang.tr(bagFull ? "steincombat.refill.bagFull" : "steincombat.refill.empty"));
            failStreak = 0;
            cooldown = bagFull ? 40 : 200;
        } else {
            // Timed out or the server refusing: the wait doubles with each such trip, from 10 s to
            // 80 s, without stopping.
            notice(Lang.tr("steincombat.refill.timeout"));
            cooldown = 200 << Math.min(failStreak, 3);
            failStreak++;
        }
        done(mod.config);
    }

    /**
     * In on-key mode, the trip is over (brought, found nothing or did not open): Auto Refill turns
     * itself off.
     */
    private void done(CombatConfig c) {
        if (c.refillMode == ON_KEY && c.autoRefill) {
            c.autoRefill = false;
            mod.save();
        }
    }

    private CombatConfig c() {
        return mod.config;
    }

    /**
     * Needs a trip? Constant: a worn piece the chest may have a new one for, or a piece about to be
     * lacking; when empty: a piece with no spare; on key: a piece below the target.
     */
    private boolean needs(CombatConfig c) {
        if (c.refillMode == CONSTANT) {
            return needsConstant(c);
        }
        for (int piece = Inventory.HELMET; piece >= Inventory.BOOTS; piece--) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode == AutoArmor.OFF) {
                continue;
            }
            int spares = AutoArmor.spares(piece, r);
            if (c.refillMode == WHEN_EMPTY ? spares == 0 : spares < c.refillTarget) {
                return true;
            }
        }
        return false;
    }

    private boolean needsConstant(CombatConfig c) {
        for (int piece = Inventory.HELMET; piece >= Inventory.BOOTS; piece--) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode == AutoArmor.OFF) {
                continue;
            }
            if (ArmorSlots.wornOf(c, piece) >= 0 && now >= lacksFitUntil[piece]) {
                return true;
            }
            if (lacking(c, piece)
                    && (now >= lacksFitUntil[piece]
                            || c.refillUseWorn && now >= lacksAnyUntil[piece])) {
                return true;
            }
        }
        return false;
    }

    /**
     * There is a way to bring something: a free space, a worn piece to swap for the chest's, or,
     * for a piece with no spare at all, a bag item to make room ({@link ArmorSlots#makeRoom}).
     */
    private static boolean roomToBring(CombatConfig c) {
        if (ArmorSlots.freeSlot() >= 0 || ArmorSlots.worn(c, -1) >= 0) {
            return true;
        }
        for (int piece = 0; piece < 4; piece++) {
            AutoArmor.Rule r = c.armorRule(piece);
            if (r.mode != AutoArmor.OFF
                    && AutoArmor.spares(piece, r) == 0
                    && ArmorSlots.makeRoom(c, piece) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the item left on the cursor goes: its chest slot, another empty chest slot, or one in
     * the bag.
     */
    private int placeForCursor() {
        if (lastFrom >= 0
                && lastFrom < Inventory.containerSize()
                && Inventory.slotStack(lastFrom) == null) {
            return lastFrom;
        }
        int chest = ArmorSlots.chestFree();
        if (chest >= 0) {
            return chest;
        }
        int free = ArmorSlots.freeSlot();
        return free < 0 ? -1 : Inventory.slotOf(free);
    }

    /** Every armor piece the player has: worn, on the hotbar and in the bag. */
    private static int armorPieces() {
        int n = 0;
        for (int i = 0; i < Inventory.SIZE; i++) {
            Object s = Inventory.stack(i);
            if (s != null && Inventory.armorPiece(s) >= 0) {
                n++;
            }
        }
        return n;
    }

    private void log(String text) {
        if (DEBUG) {
            mod.log().info("[refill] " + text);
        }
    }

    /**
     * What happened (no item, swap done, empty chest...). Not on the action bar: only turning on
     * and off shows there (the user's request); with {@code -Dsteincombat.debugNotices=true}, in
     * the log.
     */
    private void notice(String text) {
        if (NOTICES) {
            mod.log().info("[Auto Refill] " + text);
        }
    }
}
