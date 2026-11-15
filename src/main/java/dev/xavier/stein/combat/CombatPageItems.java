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

import java.util.ArrayList;
import java.util.List;

/**
 * The item modules' part of Combat's page: Valuable Items (the list of rules and a row to add one),
 * Item Lock, Auto Stash, Loot Radar, Loot Swap and Supply Forecast. Built with {@link CombatPage}'s
 * rows (the feature switch with its key and "Show in list", the switch that saves).
 */
final class CombatPageItems {

    private static final String INDENT = CombatPage.INDENT;

    private final SteinCombat mod;
    private final CombatPage page;
    private final CombatConfig c;
    private final CombatKeys keys;

    /** The rule being put together in the "new rule" rows. */
    private final Valuables.Rule draft = new Valuables.Rule(Valuables.SWORD, 2, 6, 0, 'c');

    CombatPageItems(SteinCombat mod, CombatPage page) {
        this.mod = mod;
        this.page = page;
        this.c = mod.config;
        this.keys = mod.keys;
    }

    void fill(Page p) {
        valuables(p);
        itemLock(p);
        autoStash(p);
        lootRadar(p);
        lootSwap(p);
        supplyForecast(p);
    }

    /** Valuable Items: the rules, a click cycles the radar style, the trash can removes. */
    private void valuables(Page p) {
        p.section(tr("steincombat.section.valuables"));
        // first, so the closed section's summary says how many rules there are
        p.option(
                new LiveLine(
                        tr("steincombat.valuables.rules"),
                        () -> Lang.tr("steincombat.valuables.count", c.valuables.size()),
                        () -> {}));
        p.rows(
                () -> {
                    List<Page.Item> rows = new ArrayList<>();
                    for (Valuables.Rule r : c.valuables) {
                        rows.add(
                                new Page.Item(
                                        "§" + r.color + "■ §r" + r.describe(),
                                        tr(Valuables.STYLE_KEYS[r.style]),
                                        false,
                                        () -> {
                                            r.style = (r.style + 1) % Valuables.STYLE_KEYS.length;
                                            mod.save();
                                        },
                                        () -> {
                                            c.valuables.remove(r);
                                            mod.save();
                                        }));
                    }
                    return rows;
                },
                tr("steincombat.valuables.empty"));
        p.option(
                Option.cycle(
                        INDENT + tr("steincombat.valuables.kind"),
                        names(Valuables.KIND_KEYS),
                        () -> draft.kind,
                        i -> draft.kind = i));
        p.option(
                Option.cycle(
                        INDENT + tr("steincombat.valuables.enchant"),
                        names(Valuables.ENCHANT_KEYS),
                        () -> draft.enchant,
                        i -> draft.enchant = i));
        p.option(
                Option.slider(
                                INDENT + tr("steincombat.valuables.level"),
                                1,
                                Valuables.MAX_LEVEL,
                                1,
                                () -> draft.level,
                                v -> draft.level = (int) Math.round(v),
                                v -> Valuables.roman((int) Math.round(v)) + "+")
                        .visibleWhen(() -> Valuables.ENCHANTS[draft.enchant] >= 0));
        p.option(
                Option.cycle(
                        INDENT + tr("steincombat.valuables.style"),
                        names(Valuables.STYLE_KEYS),
                        () -> draft.style,
                        i -> draft.style = i));
        p.option(
                Option.color(
                        INDENT + tr("steincombat.valuables.color"),
                        () -> String.valueOf(draft.color),
                        v -> draft.color = v.isEmpty() ? '6' : v.charAt(0)));
        p.option(
                Option.action(
                        INDENT + tr("steincombat.valuables.add"),
                        "+",
                        () -> {
                            c.valuables.add(draft.copy());
                            mod.save();
                        }));
        p.restore(
                () -> {
                    Valuables.defaults(c.valuables);
                    mod.save();
                });
    }

    /** Item Lock: on, and every item or only the valuable ones. */
    private void itemLock(Page p) {
        p.section(tr("steincombat.itemLock"));
        p.option(
                page.feature(
                        "steincombat.itemLock",
                        () -> c.itemLock,
                        v -> {
                            c.itemLock = v;
                            mod.save();
                        },
                        keys.itemLock,
                        "itemLock"));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.itemLock.all"),
                        () -> c.itemLockAll,
                        v -> c.itemLockAll = v));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.itemLock = d.itemLock;
                    c.itemLockAll = d.itemLockAll;
                    keys.itemLock.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** Auto Stash: on with health low, the key for a trip now, and what stays. */
    private void autoStash(Page p) {
        p.section(tr("steincombat.autoStash"));
        p.option(
                page.feature(
                        "steincombat.autoStash",
                        () -> c.autoStash,
                        v -> {
                            c.autoStash = v;
                            mod.save();
                        },
                        keys.autoStash,
                        "autoStash"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.stash.health"),
                        1,
                        19,
                        1,
                        () -> c.stashHealth,
                        v -> {
                            c.stashHealth = (int) Math.round(v);
                            mod.save();
                        },
                        v -> hearts(v / 2)));
        p.option(
                new Option.Text(
                        tr("steincombat.stash.command"),
                        "/bau vip",
                        () -> c.stashCommand,
                        v -> {
                            if (!v.trim().isEmpty()) {
                                c.stashCommand = v;
                                mod.save();
                            }
                        }));
        p.option(
                page.toggle(
                        tr("steincombat.stash.hotbar"),
                        () -> c.stashHotbar,
                        v -> c.stashHotbar = v));
        p.option(
                page.toggle(
                                INDENT + tr("steincombat.stash.keepHeld"),
                                () -> c.stashKeepHeld,
                                v -> c.stashKeepHeld = v)
                        .visibleWhen(() -> c.stashHotbar));
        p.option(
                Option.slider(
                        tr("steincombat.stash.keepApples"),
                        0,
                        16,
                        1,
                        () -> c.stashKeepApples,
                        v -> {
                            c.stashKeepApples = (int) Math.round(v);
                            mod.save();
                        },
                        v -> String.valueOf(Math.round(v))));
        p.option(
                page.toggle(
                        tr("steincombat.stash.keepSet"),
                        () -> c.stashKeepSet,
                        v -> c.stashKeepSet = v));
        p.option(
                page.toggle(tr("steincombat.stash.worn"), () -> c.stashWorn, v -> c.stashWorn = v)
                        .advanced());
        p.option(
                page.toggle(
                                tr("steincombat.stash.window"),
                                () -> c.stashShowWindow,
                                v -> c.stashShowWindow = v)
                        .advanced());
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.autoStash = d.autoStash;
                    c.stashHealth = d.stashHealth;
                    c.stashCommand = d.stashCommand;
                    c.stashHotbar = d.stashHotbar;
                    c.stashKeepHeld = d.stashKeepHeld;
                    c.stashKeepApples = d.stashKeepApples;
                    c.stashKeepSet = d.stashKeepSet;
                    c.stashWorn = d.stashWorn;
                    c.stashShowWindow = d.stashShowWindow;
                    keys.autoStash.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** Loot Radar: distance, how many, fading, death drops only, arrows and sound. */
    private void lootRadar(Page p) {
        p.section(tr("steincombat.lootRadar"));
        p.option(
                page.feature(
                        "steincombat.lootRadar",
                        () -> c.lootRadar,
                        v -> {
                            c.lootRadar = v;
                            mod.save();
                        },
                        keys.lootRadar,
                        "lootRadar"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.radar.distance"),
                        8,
                        64,
                        4,
                        () -> c.radarDistance,
                        v -> {
                            c.radarDistance = (int) Math.round(v);
                            mod.save();
                        },
                        v -> Math.round(v) + " m"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.radar.max"),
                        1,
                        CombatConfig.RADAR_MAX_LIMIT,
                        1,
                        () -> c.radarMax,
                        v -> {
                            c.radarMax = (int) Math.round(v);
                            mod.save();
                        },
                        v -> String.valueOf(Math.round(v))));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.radar.deathOnly"),
                        () -> c.radarDeathOnly,
                        v -> c.radarDeathOnly = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.radar.fade"),
                        () -> c.radarFade,
                        v -> c.radarFade = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.radar.arrows"),
                        () -> c.radarArrows,
                        v -> c.radarArrows = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.radar.sound"),
                        () -> c.radarSound,
                        v -> c.radarSound = v));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.lootRadar = d.lootRadar;
                    c.radarDistance = d.radarDistance;
                    c.radarMax = d.radarMax;
                    c.radarDeathOnly = d.radarDeathOnly;
                    c.radarFade = d.radarFade;
                    c.radarArrows = d.radarArrows;
                    c.radarSound = d.radarSound;
                    keys.lootRadar.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** Loot Swap: how close, and what armor may be dropped. */
    private void lootSwap(Page p) {
        p.section(tr("steincombat.lootSwap"));
        p.option(
                page.feature(
                        "steincombat.lootSwap",
                        () -> c.lootSwap,
                        v -> {
                            c.lootSwap = v;
                            mod.save();
                        },
                        keys.lootSwap,
                        "lootSwap"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.swap.radius"),
                        1,
                        6,
                        1,
                        () -> c.swapRadius,
                        v -> {
                            c.swapRadius = (int) Math.round(v);
                            mod.save();
                        },
                        v -> Math.round(v) + " m"));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.swap.maxProtection"),
                        0,
                        10,
                        1,
                        () -> c.swapMaxProtection,
                        v -> {
                            c.swapMaxProtection = (int) Math.round(v);
                            mod.save();
                        },
                        v ->
                                Math.round(v) == 0
                                        ? tr("steincombat.swap.unenchanted")
                                        : Valuables.roman((int) Math.round(v))));
        p.option(
                Option.slider(
                        INDENT + tr("steincombat.swap.maxDurability"),
                        5,
                        100,
                        5,
                        () -> c.swapMaxDurability,
                        v -> {
                            c.swapMaxDurability = (int) Math.round(v);
                            mod.save();
                        },
                        v -> Math.round(v) + "%"));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.lootSwap = d.lootSwap;
                    c.swapRadius = d.swapRadius;
                    c.swapMaxProtection = d.swapMaxProtection;
                    c.swapMaxDurability = d.swapMaxDurability;
                    keys.lootSwap.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** Supply Forecast: the sources, the start of the rush and its key. */
    private void supplyForecast(Page p) {
        p.section(tr("steincombat.supplyForecast"));
        p.option(
                Option.toggle(
                                tr("steincombat.supplyForecast"),
                                () -> c.supplyForecast,
                                v -> {
                                    c.supplyForecast = v;
                                    mod.save();
                                })
                        .withKey(keys.forecastRush::key, keys.forecastRush::setKey));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.forecast.autoStart"),
                        () -> c.forecastAutoStart,
                        v -> c.forecastAutoStart = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.forecast.worn"),
                        () -> c.forecastWorn,
                        v -> c.forecastWorn = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.forecast.inventory"),
                        () -> c.forecastInventory,
                        v -> c.forecastInventory = v));
        p.option(
                page.toggle(
                        INDENT + tr("steincombat.forecast.chest"),
                        () -> c.forecastChest,
                        v -> c.forecastChest = v));
        p.option(
                Option.cycle(
                        INDENT + tr("steincombat.forecast.view"),
                        new String[] {
                            tr("steincombat.forecast.view.unified"),
                            tr("steincombat.forecast.view.category"),
                            tr("steincombat.forecast.view.item")
                        },
                        () -> c.forecastView,
                        i -> {
                            c.forecastView = i;
                            mod.save();
                        }));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.supplyForecast = d.supplyForecast;
                    c.forecastAutoStart = d.forecastAutoStart;
                    c.forecastWorn = d.forecastWorn;
                    c.forecastInventory = d.forecastInventory;
                    c.forecastChest = d.forecastChest;
                    c.forecastView = d.forecastView;
                    keys.forecastRush.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    private static String[] names(String[] keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = tr(keys[i]);
        }
        return out;
    }

    private static String hearts(double v) {
        double h = Math.round(v * 2) / 2.0;
        String n =
                h == Math.floor(h) ? String.valueOf((long) h) : String.valueOf(h).replace('.', ',');
        return h == 1 ? tr("steincombat.protect.heart") : Lang.tr("steincombat.protect.hearts", n);
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}
