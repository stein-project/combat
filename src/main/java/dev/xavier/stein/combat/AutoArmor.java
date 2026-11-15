/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Stacks;

import java.util.Arrays;

/**
 * Auto Armor: replaces and swaps armor by itself, every tick, with the same clicks a player makes —
 * without opening the inventory, without pausing, without holding the player. It works with the
 * inventory open or closed, in the Esc menu, in the chat, in the Panel: the player's own inventory
 * window exists all the time. The only exception is an open container (chest, furnace, crafting
 * table): the server only accepts clicks in its window, which has no armor slots; when it closes,
 * Auto Armor acts in the same tick.
 *
 * <p>Each piece follows a rule ({@link Rule}) — the same for all in simple mode, or one per piece:
 *
 * <ul>
 *   <li><b>mode</b>: off; <b>replace when broken</b> (lets it break and puts the spare on in the
 *       tick the slot empties); <b>swap before</b> (with durability at the threshold or below it
 *       swaps for a spare that has more, and replaces it if it breaks anyway);
 *   <li><b>priority</b>: the spare with the highest protection first, or the lowest (use up the
 *       weak ones first);
 *   <li><b>unenchanted</b>: whether an unenchanted spare is accepted (off by default).
 * </ul>
 *
 * <p>A piece's protection ({@link #score}) counts everything: the material's armor points,
 * Protection, the specific protections and Unbreaking. The remaining durability only counts if the
 * rule asks ({@link Rule#durability}), and only as a tie-breaker between pieces that protect the
 * same; after it, the bag comes before the hotbar.
 *
 * <p>The shift-click is only for an empty slot (the piece broke). With the piece still on, a swap
 * that never leaves the body without it: a spare on the hotbar goes on with the number key over the
 * armor slot (one packet); one in the bag, with the three clicks of a swap (the new one takes the
 * old one's place and the old one goes where the new one was). Whatever goes through the cursor
 * waits for it to be empty (the player may be holding an item in the inventory).
 *
 * <p><b>Checked with the server.</b> The client applies the click at once; if the server refuses it
 * without sending the slot back (a plugin that cancels the click, an anticheat, lag), the client
 * keeps showing armor the server does not have — and Auto Armor, which only sees the client, thinks
 * all is well. With many hits landing, the durability the server sends for the old piece can also
 * arrive after the click and put it back in the slot, on the client. So in the same tick as the
 * swap, right after its clicks, the real inventory is asked from the server ({@link
 * Inventory#resync}): the server answers after applying the swap, with the right state. Until the
 * answer, the swapped piece is not swapped again (an empty slot is still filled, and the other
 * pieces go on); with it, the swap counts as done or refused. Swapping the same piece again soon
 * after (armor running out fast in a fight) is not a refusal: only the server's answer decides. A
 * piece refused several times in a row waits longer and longer between attempts, without ever
 * stopping for good, and each attempt changes the way of swapping (the fallback, see {@link
 * #swap}). Taking hits while the worn armor's durability does not move is a sign of a client out of
 * step with the server too.
 */
final class AutoArmor {

    /** {@code -Dsteincombat.debugNotices=true}: the notices (no item, swap, chest) in the log. */
    private static final boolean NOTICES = Boolean.getBoolean("steincombat.debugNotices");

    /** {@code -Dsteincombat.debugArmor=true}: every decision in the log. */
    private static final boolean DEBUG = Boolean.getBoolean("steincombat.debugArmor");

    static final int OFF = 0;
    static final int REPLACE = 1;
    static final int SWAP = 2;

    static final int DUR_OFF = 0;
    static final int DUR_HIGHEST = 1;
    static final int DUR_LOWEST = 2;

    static final String[] PIECE_KEYS = {
        "steincombat.armor.boots",
        "steincombat.armor.leggings",
        "steincombat.armor.chestplate",
        "steincombat.armor.helmet"
    };

    /** The game's enchantment ids. */
    private static final int PROTECTION = 0;

    private static final int FIRE_PROTECTION = 1;
    private static final int BLAST_PROTECTION = 3;
    private static final int PROJECTILE_PROTECTION = 4;
    private static final int UNBREAKING = 34;

    /** Ticks a piece waits after a swap the server refused; doubles with each refusal in a row. */
    private static final int COOLDOWN = 4;

    /**
     * How long to wait for the inventory after asking; with no answer, it goes on with what it has.
     */
    private static final int RESYNC_TIMEOUT = 20;

    /** How many times the check is asked again when its answer does not arrive. */
    private static final int MAX_ASK_AGAIN = 2;

    /**
     * The wait doubles with each refusal in a row, up to 4 << 3 = 32 ticks (1.6 s): in a fight, try
     * again soon.
     */
    private static final int MAX_BACKOFF = 3;

    /**
     * Hits in a row without the worn armor's durability going down, before asking for the real
     * inventory.
     */
    private static final int STILL_HITS = 3;

    /** Minimum interval between two requests because of unmoving durability (10 s). */
    private static final int STILL_RESYNC_GAP = 200;

    /** The rule of one piece (or of all, in simple mode). */
    static final class Rule {
        int mode = SWAP;

        /** Swap with this remaining durability, or less. */
        int threshold = 35;

        /** The spare with the highest protection first (false: the lowest). */
        boolean highest = true;

        /** Accept an unenchanted spare. */
        boolean unenchanted = false;

        /**
         * Durability tie-breaker: {@link #DUR_OFF} (ignored, the default), highest or lowest first.
         */
        int durability = DUR_OFF;

        /**
         * Do not let a piece with Protection of this level or more break (4 to 10; 0 off): at the
         * durability threshold it comes off — swapped for the spare, or kept in the inventory if
         * there is none —, even in replace mode.
         */
        int keepLevel = 0;

        void copyFrom(Rule o) {
            keepLevel = o.keepLevel;
            mode = o.mode;
            threshold = o.threshold;
            highest = o.highest;
            unenchanted = o.unenchanted;
            durability = o.durability;
        }
    }

    private final SteinCombat mod;
    private final int[] cooldown = new int[4];

    /** Already warned that there is no spare for the piece (warns again once there is one). */
    private final boolean[] warned = new boolean[4];

    private int now;

    /**
     * Waiting for the requested inventory until this tick (-1 not waiting): only empty slots are
     * filled meanwhile.
     */
    private int awaitUntil = -1;

    /** Inventory requests not answered yet: the check only counts with the last one's answer. */
    private int outstanding;

    /** Times the check was asked again because the answer did not arrive. */
    private int askAgain;

    private int lastResync = -STILL_RESYNC_GAP;

    /**
     * The piece was swapped and waits for the check (it is not acted on again until the answer).
     */
    private final boolean[] pending = new boolean[4];

    /** The spare put on was above the threshold: at the check, the slot must be like that. */
    private final boolean[] expectGood = new boolean[4];

    /** Swaps of the piece in a row that the server refused. */
    private final int[] fails = new int[4];

    /** Already warned that the server is refusing the piece. */
    private final boolean[] warnedRefused = new boolean[4];

    /** A hit to check: in this tick the worn durability is compared with the one when it landed. */
    private int hitCheckAt = -1;

    private long hitWear;
    private int stillHits;

    /** Whether it acted in this tick (Smart Effect starts nothing in the same tick). */
    private boolean acted;

    AutoArmor(SteinCombat mod) {
        this.mod = mod;
    }

    /**
     * The server sent a whole window (SteinMod.onWindowItems): the inventory one is what the
     * request waited for.
     */
    void windowItems(int windowId) {
        if (windowId == 0 && awaitUntil >= 0 && --outstanding <= 0) {
            awaitUntil = -1;
            outstanding = 0;
            log("real inventory arrived");
            askAgain = 0;
            verify();
        }
    }

    /** The real inventory arrived: each swapped piece counts as done or refused. */
    private void verify() {
        CombatConfig c = mod.config;
        for (int piece = 0; piece < 4; piece++) {
            if (!pending[piece]) {
                continue;
            }
            pending[piece] = false;
            Object s = Inventory.armor(piece);
            boolean ok =
                    s != null
                            && (!expectGood[piece]
                                    || !Stacks.isDamageable(s)
                                    || Stacks.maxDamage(s) - Stacks.damage(s)
                                            > c.armorRule(piece).threshold);
            if (ok) {
                fails[piece] = 0;
                warnedRefused[piece] = false;
                continue;
            }
            fails[piece]++;
            cooldown[piece] = COOLDOWN << Math.min(fails[piece], MAX_BACKOFF);
            log("piece " + piece + " refused by the server, " + fails[piece] + " in a row");
            if (fails[piece] >= 3 && !warnedRefused[piece]) {
                warnedRefused[piece] = true;
                notice(Lang.tr("steincombat.armor.refused", Lang.tr(PIECE_KEYS[piece])));
            }
        }
    }

    /**
     * The player's health went down: the worn armor should wear. Checked a few ticks later, when
     * the slot arrives.
     */
    void hurt() {
        CombatConfig c = mod.config;
        if ((c.autoArmor || c.autoRefill) && hitCheckAt < 0 && wornWear() >= 0) {
            hitWear = wornWear();
            hitCheckAt = now + 10;
        }
    }

    /** The summed wear of the worn pieces that wear; -1 with none. */
    private static long wornWear() {
        long sum = -1;
        for (int piece = 0; piece < 4; piece++) {
            Object s = Inventory.armor(piece);
            if (s != null && Stacks.isDamageable(s)) {
                sum = Math.max(sum, 0) + Stacks.damage(s);
            }
        }
        return sum;
    }

    /**
     * Waiting for the server's answer about a swap: Auto Refill opens no chest meanwhile (the
     * server would drop the answer for a window that is no longer the open one).
     */
    boolean checking() {
        return awaitUntil >= 0;
    }

    /** Asks for the real inventory; until it arrives (or the time runs out), nothing is decided. */
    private void resync(String why) {
        if (Inventory.resync()) {
            lastResync = now;
            outstanding++;
            awaitUntil = now + RESYNC_TIMEOUT;
            log("inventory asked from the server: " + why);
        }
    }

    /** The requested inventory did not arrive in time. */
    private void answerTimedOut() {
        awaitUntil = -1;
        outstanding = 0;
        boolean unchecked = false;
        for (boolean p : pending) {
            unchecked |= p;
        }
        if (unchecked && askAgain < MAX_ASK_AGAIN) {
            // A swap with no answer: the client may be showing armor the server does not have,
            // so it asks again rather than trusting it.
            askAgain++;
            resync("the answer did not arrive, asking again");
            return;
        }
        // Still no answer: go on with what the client has, without counting a refusal; the next
        // swap checks again.
        askAgain = 0;
        Arrays.fill(pending, false);
        log("the requested inventory did not arrive");
    }

    /**
     * A hit a few ticks ago should have worn the worn armor: after a few still hits in a row (with
     * a gap: falling, fire and poison do not wear armor), the real inventory is asked for. True if
     * it asked.
     */
    private boolean stillHitCheck() {
        if (hitCheckAt < 0 || now < hitCheckAt) {
            return false;
        }
        hitCheckAt = -1;
        long wear = wornWear();
        stillHits = wear >= 0 && wear == hitWear ? stillHits + 1 : 0;
        if (stillHits >= STILL_HITS && now - lastResync >= STILL_RESYNC_GAP) {
            stillHits = 0;
            resync("hits without the durability going down");
            return true;
        }
        return false;
    }

    /** One tick; returns true if it touched the inventory. */
    boolean tick() {
        acted = false;
        now++;
        CombatConfig c = mod.config;
        if (!c.autoArmor || Entities.self() == null) {
            awaitUntil = -1;
            outstanding = 0;
            Arrays.fill(pending, false);
            // With only Auto Refill on, the still-hits check still runs: the refill trusts the
            // bag, and armor the server does not have (ghost items) must not pass for real.
            if (c.autoRefill && Entities.self() != null && !Inventory.isContainerOpen()) {
                stillHitCheck();
            } else {
                hitCheckAt = -1;
            }
            return false;
        }
        if (Inventory.isContainerOpen()) {
            // The chest window has no armor: it acts when it closes.
            return false;
        }
        if (awaitUntil >= 0 && now >= awaitUntil) {
            answerTimedOut();
        }
        boolean waiting = awaitUntil >= 0;
        if (!waiting && stillHitCheck()) {
            return false;
        }
        if (Inventory.cursor() != null && !Inventory.isScreenOpen() && dropCursor()) {
            resync("item left on the cursor");
            return true;
        }
        // From the helmet to the boots, as the screen shows them. Each piece on its own: a piece
        // waiting for its check does not hold the others back (in a heavy fight the four reach
        // the threshold together).
        for (int piece = Inventory.HELMET; piece >= Inventory.BOOTS; piece--) {
            if (cooldown[piece] > 0) {
                cooldown[piece]--;
                continue;
            }
            Rule r = c.armorRule(piece);
            if (r.mode == OFF) {
                continue;
            }
            Object worn = Inventory.armor(piece);
            if (worn == null) {
                // An empty slot never waits, not even for its own check: without the piece the
                // player is unprotected, and a shift-click into a slot the server has filled only
                // moves the spare elsewhere.
                replaceMissing(piece, r);
            } else if (!pending[piece]
                    && Stacks.isDamageable(worn)
                    && Stacks.maxDamage(worn) - Stacks.damage(worn) <= r.threshold) {
                swapWorn(piece, r, worn);
            }
        }
        if (acted) {
            // In the same tick: the server answers with the swap already applied.
            resync("check the swap");
        }
        return acted;
    }

    /**
     * With no screen open nobody holds an item on the cursor: it is left over from a swap the
     * server undid (with the inventory open it may be the player dragging: then it is left alone).
     * An armor piece whose slot is empty goes straight on; anything else, to a free space. With
     * neither, the item stays there and the rest goes on (the shift-click does not use the cursor):
     * it must never stop Auto Armor, as it did with a full bag.
     *
     * @return whether it clicked
     */
    private static boolean dropCursor() {
        Object held = Inventory.cursor();
        int piece = Inventory.armorPiece(held);
        if (piece >= 0 && Inventory.armor(piece) == null) {
            int to = Inventory.slotOf(Inventory.ARMOR_START + piece);
            return to >= 0 && Inventory.pickup(to);
        }
        int free = Inventory.firstEmpty();
        return free >= 0 && Inventory.pickup(Inventory.slotOf(free));
    }

    /** The slot of the piece is empty: put on the best spare. */
    private void replaceMissing(int piece, Rule r) {
        // Replacing in swap mode: first a spare above the threshold (otherwise the almost broken
        // one would be put on and swapped again right after); only with none, any that still has
        // durability.
        int index = r.mode == SWAP ? best(piece, r.threshold + 1, r, false) : -1;
        if (index < 0) {
            index = best(piece, 1, r, false);
        }
        if (index < 0) {
            // Nothing better: the protected pieces kept at the threshold are used again until they
            // break (with no other piece, using up what there is beats having no armor).
            index = best(piece, 1, r, true);
        }
        act(piece, index, "steincombat.armor.equipped");
    }

    /** The worn piece is at the threshold: swap it, if the rule says so. */
    private void swapWorn(int piece, Rule r, Object worn) {
        boolean keep = isProtected(worn, r);
        if (DEBUG) {
            log(
                    "piece "
                            + piece
                            + " at the threshold ("
                            + (Stacks.maxDamage(worn) - Stacks.damage(worn))
                            + "), protected "
                            + keep
                            + ", mode "
                            + r.mode);
        }
        if (r.mode != SWAP && !keep) {
            return;
        }
        int index = best(piece, r.threshold + 1, r, false);
        if (index >= 0) {
            act(piece, index, "steincombat.armor.swapped");
        } else if (keep && !warned[piece]) {
            // No spare at all (and Auto Refill off, or the chest without fitting armor): the piece
            // stays on and is used until it breaks — no armor is worse. When a spare arrives (the
            // refill), it is swapped at once.
            warned[piece] = true;
            notice(Lang.tr("steincombat.armor.noSpareKeep", Lang.tr(PIECE_KEYS[piece])));
        }
    }

    private void act(int piece, int index, String doneKey) {
        if (index < 0) {
            if (!warned[piece]) {
                warned[piece] = true;
                notice(Lang.tr("steincombat.armor.none", Lang.tr(PIECE_KEYS[piece])));
            }
            return;
        }
        warned[piece] = false;
        Object spare = Inventory.stack(index);
        boolean good =
                !Stacks.isDamageable(spare)
                        || Stacks.maxDamage(spare) - Stacks.damage(spare)
                                > mod.config.armorRule(piece).threshold;
        if (!swap(piece, index, fails[piece])) {
            return;
        }
        acted = true;
        pending[piece] = true;
        expectGood[piece] = good;
        log(
                "piece "
                        + piece
                        + " swapped"
                        + (fails[piece] > 0 ? ", attempt " + (fails[piece] + 1) : ""));
        notice(Lang.tr(doneKey, Lang.tr(PIECE_KEYS[piece])));
    }

    private void log(String text) {
        if (DEBUG) {
            mod.log().info("[armor] " + text);
        }
    }

    /** Does the piece have Protection at the level the rule does not let break? */
    static boolean isProtected(Object s, Rule r) {
        return r.keepLevel > 0 && Stacks.enchantLevel(s, PROTECTION) >= r.keepLevel;
    }

    /**
     * Does the piece serve as a spare by the rule (the enchantment filter and durability above the
     * threshold)?
     */
    static boolean usable(Object s, int piece, Rule r) {
        if (s == null
                || Inventory.armorPiece(s) != piece
                || !r.unenchanted && !Stacks.isEnchanted(s)) {
            return false;
        }
        int max = Stacks.maxDamage(s);
        return max <= 0 || max - Stacks.damage(s) > r.threshold;
    }

    /** How many spares for the piece are in the inventory (hotbar and bag). */
    static int spares(int piece, Rule r) {
        int n = 0;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            if (usable(Inventory.stack(i), piece, r)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Puts on the piece at the index. The shift-click only with the slot empty (the piece broke).
     * With the armor still on, a swap that never leaves the body without it: a spare on the hotbar,
     * the number key over the armor slot (one packet); in the bag, the three clicks of a swap (the
     * new one takes the old one's place, and the old one goes where the new one was).
     *
     * <p>Fallback: {@code attempt} is how many times in a row the server refused the piece, and
     * each refusal switches the way — empty slot, between the shift-click and picking up and
     * dropping on the armor slot; worn with the spare on the hotbar, between the number key and the
     * three clicks. Whatever goes through the cursor only with it empty; with an item on it (the
     * player dragging), it waits.
     */
    private static boolean swap(int piece, int index, int attempt) {
        int from = Inventory.slotOf(index);
        int to = Inventory.slotOf(Inventory.ARMOR_START + piece);
        if (from < 0 || to < 0) {
            return false;
        }
        boolean alt = attempt % 2 == 1;
        if (Inventory.armor(piece) == null) {
            if (!alt) {
                return Inventory.quickMove(from);
            }
            return Inventory.cursor() == null && Inventory.pickup(from) && Inventory.pickup(to);
        }
        if (index < Inventory.MAIN_START && !alt) {
            return Inventory.swapHotbar(to, index);
        }
        return Inventory.cursor() == null && Inventory.equip(index);
    }

    /**
     * The index of the piece's spare with at least {@code minLeft} durability that the rule picks:
     * the highest protection, or the lowest. The score always decides; only on a tie the bag comes
     * before the hotbar (what is on the hotbar is what the player wants at hand) — so the bag is
     * read first and only a better score changes the choice.
     *
     * @param allowKept also accept the protected piece that came off at the threshold (the last
     *     resort, with no other)
     */
    private static int best(int piece, int minLeft, Rule r, boolean allowKept) {
        int best = -1;
        long bestScore = 0;
        int bestLeft = 0;
        int bag = Inventory.ARMOR_START - Inventory.MAIN_START;
        for (int n = 0; n < Inventory.ARMOR_START; n++) {
            // The bag (9 to 35), then the hotbar (0 to 8).
            int i = n < bag ? Inventory.MAIN_START + n : n - bag;
            Object s = Inventory.stack(i);
            if (s == null
                    || Inventory.armorPiece(s) != piece
                    || !r.unenchanted && !Stacks.isEnchanted(s)) {
                continue;
            }
            int max = Stacks.maxDamage(s);
            int left = max - Stacks.damage(s);
            if (max > 0 && left < minLeft) {
                continue;
            }
            if (!allowKept && max > 0 && left <= r.threshold && isProtected(s, r)) {
                continue; // the protected piece that came off so as not to break does not go back
                // on
            }
            long score = score(s);
            boolean better = best < 0 || (r.highest ? score > bestScore : score < bestScore);
            if (!better && score == bestScore && r.durability != DUR_OFF) {
                // They protect the same: durability decides, if the rule asks.
                better = r.durability == DUR_HIGHEST ? left > bestLeft : left < bestLeft;
            }
            if (better) {
                bestScore = score;
                bestLeft = left;
                best = i;
            }
        }
        return best;
    }

    /**
     * How much the piece protects, counting everything. The material's armor points and those of
     * Protection (EPF, the game's formula: (6 + level squared) x 0.75 / 3) are worth the same —
     * each takes about 4% of the damage; the specific protections, a little; Unbreaking, a little
     * less. Durability does not count (it is a rule option, as a tie-breaker).
     */
    static long score(Object s) {
        int p = Stacks.enchantLevel(s, PROTECTION);
        long epf = p == 0 ? 0 : (long) Math.floor((6 + p * p) * 0.75 / 3.0);
        long special =
                Stacks.enchantLevel(s, FIRE_PROTECTION)
                        + Stacks.enchantLevel(s, BLAST_PROTECTION)
                        + Stacks.enchantLevel(s, PROJECTILE_PROTECTION);
        return Inventory.armorValue(s) * 1000L
                + epf * 1000L
                + special * 250L
                + Stacks.enchantLevel(s, UNBREAKING) * 120L;
    }

    /**
     * What happened (no item, swap done, empty chest...). Not on the action bar: only turning on
     * and off shows there (the user's request); with {@code -Dsteincombat.debugNotices=true}, in
     * the log.
     */
    private void notice(String text) {
        if (NOTICES) {
            mod.log().info("[Auto Armor] " + text);
        }
    }
}
