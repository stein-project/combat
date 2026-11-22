/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;

/**
 * Armor Gap's section of Combat's page: the switch with its key, how many pieces must be missing,
 * the Protection level that leaves a player out, the look (glow, hit box or both), the color and
 * the range.
 */
final class ArmorGapPage {

    private static final String INDENT = CombatPage.INDENT;

    private ArmorGapPage() {}

    static void fill(SteinCombat mod, CombatPage page, Page p) {
        CombatConfig c = mod.config;
        CombatKeys keys = mod.keys;
        p.section(tr("steincombat.armorGap"));
        p.option(
                page.feature(
                        "steincombat.armorGap",
                        () -> c.armorGap,
                        v -> {
                            c.armorGap = v;
                            mod.save();
                        },
                        keys.armorGap,
                        "armorGap"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.armorGap.missing"),
                        1,
                        4,
                        1,
                        () -> c.armorGapMissing,
                        v -> {
                            c.armorGapMissing = (int) Math.round(v);
                            mod.save();
                        },
                        v -> String.valueOf(Math.round(v))));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.armorGap.protection"),
                        0,
                        5,
                        1,
                        () -> c.armorGapProtection,
                        v -> {
                            c.armorGapProtection = (int) Math.round(v);
                            mod.save();
                        },
                        v ->
                                Math.round(v) == 0
                                        ? tr("steincombat.armorGap.protection.never")
                                        : Lang.tr(
                                                "steincombat.armorGap.protection.level",
                                                Math.round(v))));
        p.option(
                Option.cycle(
                        INDENT + tr("steincombat.armorGap.style"),
                        new String[] {
                            tr("steincombat.armorGap.style.glow"),
                            tr("steincombat.armorGap.style.hitbox"),
                            tr("steincombat.armorGap.style.both")
                        },
                        () -> c.armorGapStyle,
                        i -> {
                            c.armorGapStyle = i;
                            mod.save();
                        }));
        p.option(
                Option.color(
                        INDENT + tr("steincombat.armorGap.color"),
                        () -> String.valueOf(c.armorGapColor),
                        v -> {
                            c.armorGapColor = v.isEmpty() ? '6' : v.charAt(0);
                            mod.save();
                        }));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.armorGap.range"),
                        8,
                        64,
                        4,
                        () -> c.armorGapRange,
                        v -> {
                            c.armorGapRange = (int) Math.round(v);
                            mod.save();
                        },
                        v -> Math.round(v) + " m"));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.armorGap = d.armorGap;
                    c.armorGapMissing = d.armorGapMissing;
                    c.armorGapProtection = d.armorGapProtection;
                    c.armorGapStyle = d.armorGapStyle;
                    c.armorGapColor = d.armorGapColor;
                    c.armorGapRange = d.armorGapRange;
                    keys.armorGap.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}
