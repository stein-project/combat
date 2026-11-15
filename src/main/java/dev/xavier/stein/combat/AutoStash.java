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

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Stash: puts the valuable items (the Valuable Items list) away in the server's virtual chest
 * ("/bau vip") — on its key, or by itself when health falls to the chosen limit, so what is worth
 * the most is not left on the ground if the fight is lost.
 *
 * <p>The trip is Auto Refill's: the command, the chest hidden (or in view), one step every 100 ms
 * with the cursor empty at the end of each step, every step checking its own result and asking the
 * server for the real contents when steps stop working, a maximum time, and the bag checked with
 * the server after closing. Each step is one shift-click of the most valuable item still to go, so
 * if the player dies halfway, what went first is what was worth the most.
 *
 * <p>What stays, so the fight can go on ({@link #toStash}): the item in the hand, a reserve of
 * enchanted golden apples, one spare of each armor piece (the best), and the worn armor — unless
 * the player chose to put it away too, in which case it comes off into the bag before the chest
 * opens (the chest window has no armor slots).
 *
 * <p>With others ({@link Coordinator}): it does not start while Smart Effect, Auto Protect or Auto
 * Armor are acting, and pauses its steps while Smart Effect uses an item. With health low and Auto
 * Refill's chest already open, it takes that same chest over instead of waiting.
 */
final class AutoStash {

    /** {@code -Dsteincombat.debugStash=true}: every step in the log. */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugStash");

    private static final int STEP_TICKS = 2;
    private static final int OPEN_TIMEOUT = 60;
    private static final int SESSION_TIMEOUT = 300;
    private static final int STUCK_STEPS = 3;
    private static final int MAX_RESYNCS = 4;
    private static final int RESYNC_TIMEOUT = 40;
    private static final int VERIFY_TICKS = 10;

    /** Health (in half hearts) above the limit at which the low-health trip arms again. */
    private static final int REARM_MARGIN = 6;

    private enum State {
        IDLE,
        WAITING,
        WORKING
    }

    private final SteinCombat mod;
    private State state = State.IDLE;
    private int timer;
    private int stepWait;
    private int cooldown;
    private int windowId;
    private int stuck;
    private int resyncs;
    private int awaiting;
    private boolean dirty;
    private int verifyIn;

    /** Clicks that put an item away on this trip, as the client saw them. */
    private int moved;

    /** Items to put away when the trip started: what really went is this minus what is left. */
    private int startCount;

    /** The key asked for a trip. */
    private boolean requested;

    private float lastHealth;

    /** The low-health trip went: it arms again only after health comes back. */
    private boolean lowFired;

    AutoStash(SteinCombat mod) {
        this.mod = mod;
    }

    boolean busy() {
        return state != State.IDLE;
    }

    /** The key: one trip now. */
    void request() {
        requested = true;
    }

    /** The server sent a whole window: the chest's is what a check waited for. */
    void windowItems(int id) {
        if (awaiting > 0 && state == State.WORKING && id == windowId) {
            awaiting = 0;
            log("real contents arrived");
        }
    }

    void tick() {
        CombatConfig c = mod.config;
        Object me = Entities.self();
        if (me == null) {
            state = State.IDLE;
            requested = false;
            verifyIn = 0;
            return;
        }
        float health = Entities.health(me) + Entities.absorption(me);
        if (DEBUG && health != lastHealth) {
            log("health " + health + " (limit " + c.stashHealth + ", fired " + lowFired + ")");
        }
        lastHealth = health;
        if (lowFired && health > c.stashHealth + REARM_MARGIN) {
            lowFired = false;
        }
        switch (state) {
            case IDLE -> idle(c, health);
            case WAITING -> waiting(c);
            case WORKING -> work();
        }
    }

    private void idle(CombatConfig c, float health) {
        if (verifyIn > 0 && --verifyIn == 0) {
            if (Inventory.isContainerOpen()) {
                verifyIn = 1;
            } else {
                Inventory.resync();
            }
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        boolean low = c.autoStash && !lowFired && health <= c.stashHealth;
        if (!requested && !low) {
            return;
        }
        if (toStash(c).isEmpty() && !hasWornToStash(c)) {
            if (requested) {
                Say.actionBar(Lang.tr("steincombat.stash.nothing"));
            }
            requested = false;
            lowFired |= low;
            return;
        }
        Coordinator co = mod.coordinator;
        if (co.active(Coordinator.SURVIVE) || co.active(Coordinator.ARMOR)) {
            return; // eating, drinking, blocking or equipping: the trip waits its turn
        }
        if (mod.autoRefill.busy()) {
            // With health low the chest is ours: Auto Refill's open chest is taken over as it is.
            int window = low ? mod.autoRefill.handOver() : -1;
            if (window < 0) {
                return;
            }
            log("took Auto Refill's chest over (window " + window + ")");
            startWorking(window);
            requested = false;
            lowFired |= low;
            return;
        }
        if (Inventory.isContainerOpen() || Inventory.isScreenOpen()) {
            return; // never over one of the player's screens
        }
        if (c.stashWorn) {
            takeOffWorn(c);
        }
        if (!c.stashShowWindow) {
            Inventory.openVirtualNext(3000);
        }
        Chat.send(c.stashCommand.trim());
        log("command sent: " + c.stashCommand.trim());
        state = State.WAITING;
        timer = OPEN_TIMEOUT;
        requested = false;
        lowFired |= low;
    }

    private void waiting(CombatConfig c) {
        if (Inventory.isContainerOpen()) {
            if (!c.stashShowWindow && Inventory.isScreenOpen()) {
                Inventory.hideContainerScreen();
            }
            startWorking(Inventory.windowId());
        } else if (--timer <= 0) {
            Inventory.openVirtualNext(0);
            log("the chest did not open");
            Say.actionBar(Lang.tr("steincombat.stash.noChest"));
            state = State.IDLE;
            cooldown = 100;
        }
    }

    private void startWorking(int window) {
        state = State.WORKING;
        windowId = window;
        timer = SESSION_TIMEOUT;
        stepWait = STEP_TICKS;
        moved = 0;
        startCount = toStash(mod.config).size();
        stuck = 0;
        resyncs = 0;
        awaiting = 0;
        dirty = false;
    }

    private void work() {
        if (!Inventory.isContainerOpen() || Inventory.windowId() != windowId) {
            if (Inventory.windowId() != windowId) {
                Inventory.closeWindowOnServer(windowId);
            }
            log("the chest closed underneath");
            end(false);
            return;
        }
        if (--timer <= 0) {
            close(false);
            return;
        }
        if (awaiting > 0) {
            awaiting--;
            return;
        }
        if (--stepWait > 0) {
            return;
        }
        stepWait = STEP_TICKS;
        if (mod.coordinator.active(Coordinator.SURVIVE)) {
            return; // Smart Effect is using an item: the next step waits for it
        }
        int r = step();
        if (r > 0) {
            dirty = true;
            stuck = 0;
            return;
        }
        if (r < 0) {
            dirty = true;
            if (++stuck >= STUCK_STEPS) {
                stuck = 0;
                if (!resync("steps with no effect")) {
                    close(false);
                }
            }
            return;
        }
        if (moved == 0 && SESSION_TIMEOUT - timer < 20 && ArmorSlots.chestEmpty()) {
            return; // the chest's items come in a packet after it opens
        }
        if (dirty) {
            if (resync("check before closing")) {
                dirty = false;
                return;
            }
            close(false); // the checks ran out with clicks the server never confirmed
            return;
        }
        close(true);
    }

    /** One step: 1 put one away, 0 nothing left to do, -1 a click with no effect. */
    private int step() {
        if (Inventory.cursor() != null) {
            int chest = ArmorSlots.chestFree();
            int to = chest >= 0 ? chest : bagSlot();
            log("item on the cursor goes to slot " + to);
            if (to < 0) {
                return -1;
            }
            Inventory.pickup(to);
            return Inventory.cursor() == null ? 1 : -1;
        }
        List<Integer> list = toStash(mod.config);
        if (list.isEmpty()) {
            return 0;
        }
        if (ArmorSlots.chestFree() < 0) {
            log("chest full");
            return 0;
        }
        int index = list.get(0);
        int slot = Inventory.slotOf(index);
        Object before = Inventory.stack(index);
        log("stores " + Stacks.id(before) + " from slot " + slot);
        if (slot < 0 || !Inventory.quickMove(slot)) {
            return -1;
        }
        if (Inventory.stack(index) == null) {
            moved++;
            return 1;
        }
        return -1;
    }

    private void close(boolean finished) {
        log(
                "closes: "
                        + moved
                        + " clicks, "
                        + (finished ? "finished" : "timed out, refused or not confirmed"));
        mod.supplies.chestSeen();
        Inventory.closeContainer();
        end(finished);
    }

    private void end(boolean finished) {
        state = State.IDLE;
        verifyIn = VERIFY_TICKS;
        cooldown = 40;
        // what really left the bag, by the last contents the server confirmed — not the clicks
        int stored = finished ? Math.max(0, startCount - toStash(mod.config).size()) : 0;
        Say.actionBar(
                stored > 0
                        ? Lang.tr("steincombat.stash.done", stored)
                        : Lang.tr(
                                finished
                                        ? "steincombat.stash.nothing"
                                        : "steincombat.stash.failed"));
    }

    private boolean resync(String why) {
        if (resyncs >= MAX_RESYNCS || !Inventory.resync()) {
            return false;
        }
        resyncs++;
        awaiting = RESYNC_TIMEOUT;
        log("contents asked from the server: " + why);
        return true;
    }

    /**
     * The inventory indexes (hotbar and bag) of what goes to the chest, the most valuable first:
     * what the Valuable Items list matches, minus what stays — the item in the hand, the apple
     * reserve (whole stacks: the ones that cover it stay), and one spare per armor piece.
     */
    static List<Integer> toStash(CombatConfig c) {
        List<Integer> out = new ArrayList<>();
        int held = Inventory.selectedHotbar();
        int apples = c.stashKeepApples;
        int[] spareKept = {-1, -1, -1, -1};
        if (c.stashKeepSet) {
            for (int piece = 0; piece < 4; piece++) {
                spareKept[piece] = bestSpare(c, piece);
            }
        }
        // the apples that stay: hotbar first (where Smart Effect finds them fastest)
        boolean[] keep = new boolean[Inventory.ARMOR_START];
        for (int i = 0; i < Inventory.ARMOR_START && apples > 0; i++) {
            Object s = Inventory.stack(i);
            if (s != null && Valuables.isEnchantedApple(s)) {
                keep[i] = true;
                apples -= Stacks.count(s);
            }
        }
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null || keep[i] || !Valuables.isValuable(c, s)) {
                continue;
            }
            if (i < Inventory.MAIN_START && (!c.stashHotbar || c.stashKeepHeld && i == held)) {
                continue;
            }
            int piece = Inventory.armorPiece(s);
            if (piece >= 0 && spareKept[piece] == i) {
                continue;
            }
            out.add(i);
        }
        out.sort((a, b) -> Long.compare(value(c, b), value(c, a)));
        return out;
    }

    private static long value(CombatConfig c, int index) {
        return Valuables.value(c, Inventory.stack(index));
    }

    /**
     * The spare of the piece that stays, or -1: the best one that is not valuable (the valuable
     * ones are what the trip is for), or the best valuable one when it is the only spare.
     */
    private static int bestSpare(CombatConfig c, int piece) {
        int best = -1;
        long bestScore = 0;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null || Inventory.armorPiece(s) != piece) {
                continue;
            }
            long score =
                    (Valuables.isValuable(c, s) ? 0 : 1L << 40)
                            + AutoArmor.score(s) * 1000
                            + Valuables.durabilityPercent(s);
            if (best < 0 || score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean hasWornToStash(CombatConfig c) {
        if (!c.stashWorn) {
            return false;
        }
        for (int piece = 0; piece < 4; piece++) {
            if (Valuables.isValuable(c, Inventory.armor(piece))) {
                return true;
            }
        }
        return false;
    }

    /** With "Worn armor too": the valuable worn pieces come off into free bag spaces. */
    private void takeOffWorn(CombatConfig c) {
        for (int piece = 0; piece < 4; piece++) {
            if (Valuables.isValuable(c, Inventory.armor(piece))
                    && ArmorSlots.freeSlot() >= 0
                    && Inventory.unequip(piece)) {
                log("took off piece " + piece);
            }
        }
    }

    /** A free bag slot, as a slot of the open chest window, or -1. */
    private static int bagSlot() {
        int free = ArmorSlots.freeSlot();
        return free < 0 ? -1 : Inventory.slotOf(free);
    }

    private void log(String text) {
        if (DEBUG) {
            mod.log().info("[stash] " + text);
        }
    }
}
