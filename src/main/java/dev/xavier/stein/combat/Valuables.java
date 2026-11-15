/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Stacks;

import java.util.List;

/**
 * Valuable Items: the one list of rules that says what is valuable — "a sword with Sharpness VI or
 * more", "armor with Protection V or more", "enchanted golden apple". Item Lock, Auto Stash, Loot
 * Radar and Loot Swap all read it, so the player says it once.
 *
 * <p>A rule is a kind of item, an enchantment (or none) and its minimum level; the Loot Radar's
 * style and color for what it matches live in the rule too. The first rule that matches an item is
 * the item's rule.
 */
final class Valuables {

    /** Item kinds a rule can name, by index (the config stores the index). */
    static final int ANY = 0;

    static final int SWORD = 1;
    static final int AXE = 2;
    static final int BOW = 3;
    static final int HELMET = 4;
    static final int CHESTPLATE = 5;
    static final int LEGGINGS = 6;
    static final int BOOTS = 7;
    static final int ARMOR = 8;
    static final int GAPPLE = 9;
    static final int POTION = 10;

    /** The lang key of each kind, by index. */
    static final String[] KIND_KEYS = {
        "steincombat.valuables.kind.any",
        "steincombat.valuables.kind.sword",
        "steincombat.valuables.kind.axe",
        "steincombat.valuables.kind.bow",
        "steincombat.valuables.kind.helmet",
        "steincombat.valuables.kind.chestplate",
        "steincombat.valuables.kind.leggings",
        "steincombat.valuables.kind.boots",
        "steincombat.valuables.kind.armor",
        "steincombat.valuables.kind.gapple",
        "steincombat.valuables.kind.potion"
    };

    /** The enchantments a rule can ask for, by the game's id; -1 is "no enchantment needed". */
    static final int[] ENCHANTS = {-1, 0, 16, 34, 19, 20, 48, 49, 50, 51, 7, 4, 1, 3};

    /** The lang key of each enchantment in {@link #ENCHANTS}, in the same order. */
    static final String[] ENCHANT_KEYS = {
        "steincombat.valuables.ench.none",
        "steincombat.valuables.ench.protection",
        "steincombat.valuables.ench.sharpness",
        "steincombat.valuables.ench.unbreaking",
        "steincombat.valuables.ench.knockback",
        "steincombat.valuables.ench.fireAspect",
        "steincombat.valuables.ench.power",
        "steincombat.valuables.ench.punch",
        "steincombat.valuables.ench.flame",
        "steincombat.valuables.ench.infinity",
        "steincombat.valuables.ench.thorns",
        "steincombat.valuables.ench.projectileProtection",
        "steincombat.valuables.ench.fireProtection",
        "steincombat.valuables.ench.blastProtection"
    };

    /** Highest level a rule can ask for (servers go past the game's V). */
    static final int MAX_LEVEL = 10;

    /** Loot Radar styles, by index. */
    static final int GLOW = 0;

    static final int BEACON = 1;
    static final int MARKER = 2;

    /** The lang key of each Loot Radar style. */
    static final String[] STYLE_KEYS = {
        "steincombat.radar.style.glow",
        "steincombat.radar.style.beacon",
        "steincombat.radar.style.marker"
    };

    private static final String[] ROMAN = {
        "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    /** One rule of the list. */
    static final class Rule {
        int kind;

        /** An index into {@link #ENCHANTS}. */
        int enchant;

        int level = 1;

        /** The Loot Radar's style for what it matches ({@link #GLOW}, {@link #BEACON}...). */
        int style = GLOW;

        /** The Loot Radar's color, a color code letter. */
        char color = '6';

        Rule() {}

        Rule(int kind, int enchant, int level, int style, char color) {
            this.kind = kind;
            this.enchant = enchant;
            this.level = level;
            this.style = style;
            this.color = color;
        }

        Rule copy() {
            return new Rule(kind, enchant, level, style, color);
        }

        /** Does the item match: the kind, and the enchantment at the level or more? */
        boolean matches(Object stack) {
            if (stack == null || !kindMatches(kind, stack)) {
                return false;
            }
            int id = ENCHANTS[Math.max(0, Math.min(enchant, ENCHANTS.length - 1))];
            return id < 0 || Stacks.enchantLevel(stack, id) >= level;
        }

        /** "Sword · Sharpness VI+", for the Panel's list. */
        String describe() {
            String kindName = Lang.tr(KIND_KEYS[Math.max(0, Math.min(kind, KIND_KEYS.length - 1))]);
            int e = Math.max(0, Math.min(enchant, ENCHANTS.length - 1));
            if (ENCHANTS[e] < 0) {
                return kindName;
            }
            return kindName + " · " + Lang.tr(ENCHANT_KEYS[e]) + " " + roman(level) + "+";
        }
    }

    private Valuables() {}

    /** A new list with the defaults. */
    static List<Rule> defaultList() {
        List<Rule> out = new java.util.ArrayList<>();
        defaults(out);
        return out;
    }

    /** The defaults: Sharpness VI+ swords, Protection V+ armor and enchanted golden apples. */
    static void defaults(List<Rule> out) {
        out.clear();
        out.add(new Rule(SWORD, 2, 6, BEACON, 'c'));
        out.add(new Rule(ARMOR, 1, 5, BEACON, 'b'));
        out.add(new Rule(GAPPLE, 0, 1, GLOW, '6'));
    }

    /** Is the item of the kind? */
    static boolean kindMatches(int kind, Object s) {
        String id = Stacks.id(s);
        return switch (kind) {
            case SWORD -> id.endsWith("_sword");
            case AXE -> id.endsWith("_axe");
            case BOW -> id.equals("minecraft:bow");
            case HELMET -> Inventory.armorPiece(s) == Inventory.HELMET;
            case CHESTPLATE -> Inventory.armorPiece(s) == Inventory.CHESTPLATE;
            case LEGGINGS -> Inventory.armorPiece(s) == Inventory.LEGGINGS;
            case BOOTS -> Inventory.armorPiece(s) == Inventory.BOOTS;
            case ARMOR -> Inventory.armorPiece(s) >= 0;
            case GAPPLE -> isEnchantedApple(s);
            case POTION -> id.equals("minecraft:potion");
            default -> true;
        };
    }

    static boolean isEnchantedApple(Object s) {
        return Stacks.is(s, "golden_apple") && Stacks.damage(s) == 1;
    }

    /** The first rule the item matches, or null when it is not valuable. */
    static Rule match(CombatConfig c, Object stack) {
        if (stack == null) {
            return null;
        }
        for (Rule r : c.valuables) {
            if (r.matches(stack)) {
                return r;
            }
        }
        return null;
    }

    static boolean isValuable(CombatConfig c, Object stack) {
        return match(c, stack) != null;
    }

    /**
     * How much the item is worth, to put things in order (what Auto Stash stores first, what Loot
     * Swap drops first): valuable above all, then the enchantments, the material and what is left
     * of the durability.
     */
    static long value(CombatConfig c, Object s) {
        if (s == null) {
            return 0;
        }
        long v = isValuable(c, s) ? 1_000_000_000L : 0;
        // each enchantment level weighs the same on armor and on weapons; the material breaks ties
        for (int id : ENCHANTS) {
            if (id >= 0) {
                v += Stacks.enchantLevel(s, id) * (id == 34 ? 5_000L : 20_000L);
            }
        }
        v += (Inventory.armorPiece(s) >= 0 ? Inventory.armorValue(s) : tier(Stacks.id(s))) * 1000L;
        if (isEnchantedApple(s)) {
            v += 15_000L * Stacks.count(s);
        }
        int max = Stacks.maxDamage(s);
        if (max > 0) {
            v += 100L * (max - Stacks.damage(s)) / max;
        }
        return v;
    }

    /** What is left of the durability, 0 to 100 (100 for what does not wear out). */
    static int durabilityPercent(Object s) {
        int max = Stacks.maxDamage(s);
        return max <= 0 ? 100 : Math.round(100F * (max - Stacks.damage(s)) / max);
    }

    private static int tier(String id) {
        if (id.contains("diamond")) {
            return 4;
        }
        if (id.contains("iron")) {
            return 3;
        }
        if (id.contains("chainmail")) {
            return 2;
        }
        if (id.contains("stone") || id.contains("golden")) {
            return 1;
        }
        return 0;
    }

    static String roman(int level) {
        return level >= 0 && level < ROMAN.length ? ROMAN[level] : String.valueOf(level);
    }
}
