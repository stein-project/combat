/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Stacks;

import java.util.Arrays;

/**
 * Auto Refill's view of the slots: spares, worn pieces and free places, in the bag and in the
 * chest.
 */
final class ArmorSlots {

    private ArmorSlots() {}

    /**
     * The pieces from the one with the fewest spares to the one with the most (on a tie, from the
     * helmet to the boots): every piece gets one spare before any gets a second — with few worn
     * pieces to swap, no piece goes without.
     */
    static Integer[] neediestFirst(CombatConfig c) {
        Integer[] order = {
            Inventory.HELMET, Inventory.CHESTPLATE, Inventory.LEGGINGS, Inventory.BOOTS
        };
        int[] spares = new int[4];
        for (int piece = 0; piece < 4; piece++) {
            spares[piece] = AutoArmor.spares(piece, c.armorRule(piece));
        }
        // A stable sort: the tie order stays.
        Arrays.sort(order, (a, b) -> Integer.compare(spares[a], spares[b]));
        return order;
    }

    /** The spares that fit, summed over the four pieces. */
    static int totalSpares(CombatConfig c) {
        int n = 0;
        for (int piece = 0; piece < 4; piece++) {
            n += AutoArmor.spares(piece, c.armorRule(piece));
        }
        return n;
    }

    /**
     * Items a player fights with: they go to the chest only when nothing else can make room. The
     * damageable ones (weapons, bows, rods, tools) count too, by {@link #isCombatItem}.
     */
    private static final String[] COMBAT_ITEMS = {
        "potion",
        "golden_apple",
        "mushroom_stew",
        "ender_pearl",
        "arrow",
        "golden_carrot",
        "cooked_beef",
        "cooked_porkchop",
        "cooked_chicken",
        "cooked_mutton",
        "cooked_rabbit",
        "cooked_fish",
        "bread",
        "baked_potato",
        "apple",
        "snowball",
        "egg",
        "water_bucket",
        "lava_bucket",
        "web"
    };

    /**
     * A worn armor piece in the bag or the hotbar (at its rule's threshold): the requested piece
     * first, then any; -1 with none. {@code piece} -1 accepts any piece. The hotbar counts: with a
     * full bag a spare can land there ({@link #freeSlot}), and once worn it must still go back.
     */
    static int worn(CombatConfig c, int piece) {
        int any = -1;
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            int p = s == null ? -1 : Inventory.armorPiece(s);
            if (p < 0 || !Stacks.isDamageable(s)) {
                continue;
            }
            if (Stacks.maxDamage(s) - Stacks.damage(s) <= c.armorRule(p).threshold) {
                if (piece < 0 || p == piece) {
                    return i;
                }
                if (any < 0) {
                    any = i;
                }
            }
        }
        return any;
    }

    /**
     * A worn piece of exactly this kind in the inventory (at its rule's threshold); -1 with none.
     */
    static int wornOf(CombatConfig c, int piece) {
        AutoArmor.Rule r = c.armorRule(piece);
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s != null
                    && Inventory.armorPiece(s) == piece
                    && Stacks.isDamageable(s)
                    && Stacks.maxDamage(s) - Stacks.damage(s) <= r.threshold) {
                return i;
            }
        }
        return -1;
    }

    /** Any piece of the kind in the inventory (hotbar and bag), worn or not; -1 with none. */
    static int anyOf(int piece) {
        for (int i = 0; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s != null && Inventory.armorPiece(s) == piece) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The chest's worn piece of the kind with the most durability left that the rule's enchantment
     * filter accepts; -1 with none.
     */
    static int wornInChest(int piece, AutoArmor.Rule r) {
        int best = -1;
        int bestLeft = 0;
        for (int slot = 0; slot < Inventory.containerSize(); slot++) {
            Object s = Inventory.slotStack(slot);
            if (s == null
                    || Inventory.armorPiece(s) != piece
                    || !r.unenchanted && !Stacks.isEnchanted(s)) {
                continue;
            }
            int left = Stacks.maxDamage(s) - Stacks.damage(s);
            if (left > bestLeft) {
                best = slot;
                bestLeft = left;
            }
        }
        return best;
    }

    /**
     * A bag slot whose item can go to the chest to make room for a spare of {@code piece}, when the
     * bag is full and has no worn piece to swap: without it the piece would stay off until a space
     * opens. Never the hotbar. In order, what costs the player least:
     *
     * <ol>
     *   <li>an ordinary item (blocks, empty bowls, anything not used to fight);
     *   <li>a spare of another piece that has more than it needs (the worst of them by its rule):
     *       full of chestplates and needing a helmet, a chestplate goes;
     *   <li>an item to fight with ({@link #isCombatItem}).
     * </ol>
     *
     * The item is not lost: it takes the spare's place in the chest. -1 with nothing to move.
     */
    static int makeRoom(CombatConfig c, int piece) {
        int combat = -1;
        for (int i = Inventory.MAIN_START; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (s == null || Inventory.armorPiece(s) >= 0) {
                continue;
            }
            if (!isCombatItem(s)) {
                return i;
            }
            if (combat < 0) {
                combat = i;
            }
        }
        int surplus = surplusSpare(c, piece);
        return surplus >= 0 ? surplus : combat;
    }

    /**
     * The worst bag spare of the piece (other than {@code piece}) with the most spares above what
     * Auto Refill keeps, so that giving it up never makes that piece need a trip of its own; -1
     * with none.
     */
    private static int surplusSpare(CombatConfig c, int piece) {
        int keep = Math.max(1, c.refillTarget);
        int bestPiece = -1;
        int most = keep;
        for (int q = 0; q < 4; q++) {
            int n = AutoArmor.spares(q, c.armorRule(q));
            if (q != piece && n > most) {
                most = n;
                bestPiece = q;
            }
        }
        if (bestPiece < 0) {
            return -1;
        }
        AutoArmor.Rule r = c.armorRule(bestPiece);
        int worst = -1;
        long worstScore = 0;
        for (int i = Inventory.MAIN_START; i < Inventory.ARMOR_START; i++) {
            Object s = Inventory.stack(i);
            if (!AutoArmor.usable(s, bestPiece, r)) {
                continue;
            }
            long score = AutoArmor.score(s);
            if (worst < 0 || (r.highest ? score < worstScore : score > worstScore)) {
                worst = i;
                worstScore = score;
            }
        }
        return worst;
    }

    /** Is it something a player fights with (a weapon, a tool, a potion, food, a pearl...)? */
    static boolean isCombatItem(Object s) {
        if (Stacks.isDamageable(s)) {
            return true;
        }
        for (String id : COMBAT_ITEMS) {
            if (Stacks.is(s, id)) {
                return true;
            }
        }
        return false;
    }

    /** The chest slot with the piece's armor that the rule picks (highest or lowest protection). */
    static int bestInChest(int piece, AutoArmor.Rule r) {
        int size = Inventory.containerSize();
        int best = -1;
        long bestScore = 0;
        for (int slot = 0; slot < size; slot++) {
            Object s = Inventory.slotStack(slot);
            if (!AutoArmor.usable(s, piece, r)) {
                continue;
            }
            long score = AutoArmor.score(s);
            if (best < 0 || (r.highest ? score > bestScore : score < bestScore)) {
                best = slot;
                bestScore = score;
            }
        }
        return best;
    }

    /** An empty chest slot; -1 if it is full. */
    static int chestFree() {
        for (int i = 0; i < Inventory.containerSize(); i++) {
            if (Inventory.slotStack(i) == null) {
                return i;
            }
        }
        return -1;
    }

    static boolean chestEmpty() {
        for (int i = 0; i < Inventory.containerSize(); i++) {
            if (Inventory.slotStack(i) != null) {
                return false;
            }
        }
        return true;
    }

    /** A free space in the bag (9 to 35); with none, one in the hotbar. */
    static int freeSlot() {
        for (int i = Inventory.MAIN_START; i < Inventory.ARMOR_START; i++) {
            if (Inventory.stack(i) == null) {
                return i;
            }
        }
        for (int i = 0; i < Inventory.MAIN_START; i++) {
            if (Inventory.stack(i) == null) {
                return i;
            }
        }
        return -1;
    }
}
