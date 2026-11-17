/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Stacks;

import java.util.ArrayList;
import java.util.List;

/**
 * The item modules' part of Combat's page: Treasure List (the list of rules and a row to add one),
 * Item Lock, Auto Stash, Loot Radar (with how each rule of the list shows, and its priority) and
 * Supply Tracker. Built with {@link CombatPage}'s rows (the feature switch with its key and "Show
 * in list", the switch that saves).
 */
final class CombatPageItems {

    private static final String INDENT = CombatPage.INDENT;

    private final SteinCombat mod;
    private final CombatPage page;
    private final CombatConfig c;
    private final CombatKeys keys;

    /** The rule being put together in the "new rule" rows. */
    private final Valuables.Rule draft = new Valuables.Rule(Valuables.SWORD, 2, 6, 0, 'c');

    /** The rule whose look Loot Radar's rows edit (clicked in its list); null: the first one. */
    private Valuables.Rule radarRule;

    CombatPageItems(SteinCombat mod, CombatPage page) {
        this.mod = mod;
        this.page = page;
        this.c = mod.config;
        this.keys = mod.keys;
    }

    void fill(Page p) {
        valuables(p);
        itemLock(p);
        autoTrash(p);
        autoStash(p);
        lootRadar(p);
        supplyForecast(p);
    }

    /**
     * Treasure List: only what is valuable (the kind, and the enchantment at a level or higher);
     * the trash can removes. How Loot Radar shows each rule is in Loot Radar's section.
     */
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
                                        r.describe(),
                                        "",
                                        false,
                                        null,
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
                Option.action(
                        INDENT + tr("steincombat.valuables.add"),
                        "+",
                        () -> {
                            // a new rule shows on Loot Radar with the glow, in gold
                            c.valuables.add(
                                    new Valuables.Rule(
                                            draft.kind,
                                            draft.enchant,
                                            draft.level,
                                            Valuables.GLOW,
                                            '6'));
                            mod.save();
                        }));
        p.restore(
                () -> {
                    Valuables.defaults(c.valuables);
                    radarRule = null;
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

    /**
     * Auto Trash: on, the trash list (the trash can removes an item), adding the item in the hand
     * or one typed by its id, and the hotbar.
     */
    private void autoTrash(Page p) {
        p.section(tr("steincombat.autoTrash"));
        p.option(
                page.feature(
                        "steincombat.autoTrash",
                        () -> c.autoTrash,
                        v -> {
                            c.autoTrash = v;
                            mod.save();
                        },
                        keys.autoTrash,
                        "autoTrash"));
        p.option(
                new LiveLine(
                        tr("steincombat.trash.items"),
                        () -> Lang.tr("steincombat.trash.count", c.trashItems.size()),
                        () -> {}));
        p.rows(
                () -> {
                    List<Page.Item> rows = new ArrayList<>();
                    for (String id : new ArrayList<>(c.trashItems)) {
                        rows.add(
                                new Page.Item(
                                        itemName(id),
                                        id.startsWith("minecraft:") ? id.substring(10) : id,
                                        false,
                                        null,
                                        () -> {
                                            c.trashItems.remove(id);
                                            mod.save();
                                        }));
                    }
                    return rows;
                },
                tr("steincombat.trash.empty"));
        p.option(
                Option.action(
                        INDENT + tr("steincombat.trash.addHeld"),
                        "+",
                        () -> {
                            Object held = Inventory.heldItem();
                            String id = held == null ? "" : AutoTrash.normalize(Stacks.id(held));
                            if (!id.isEmpty() && !c.trashItems.contains(id)) {
                                c.trashItems.add(id);
                                mod.save();
                            }
                        }));
        p.option(
                new Option.Text(
                        INDENT + tr("steincombat.trash.addId"),
                        "cobblestone",
                        () -> "",
                        v -> {
                            String id = AutoTrash.normalize(v);
                            if (!id.isEmpty() && !c.trashItems.contains(id)) {
                                c.trashItems.add(id);
                                mod.save();
                            }
                        }));
        p.option(
                page.toggle(
                        tr("steincombat.trash.hotbar"),
                        () -> c.trashHotbar,
                        v -> c.trashHotbar = v));
        p.restore(
                () -> {
                    c.autoTrash = false;
                    c.trashHotbar = false;
                    c.trashItems.clear();
                    c.trashItems.addAll(AutoTrash.DEFAULTS);
                    keys.autoTrash.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** The item's name in the game's language, from its id; the id itself if unknown. */
    private static String itemName(String id) {
        Object s = Stacks.create(id, 1, 0);
        String name = s == null ? "" : Stacks.name(s);
        return name == null || name.isEmpty() ? id : name;
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

    /**
     * Loot Radar: distance, then the Treasure List's rules in priority order — a click picks one,
     * and the rows below set how it shows (style or not marked, color) and move it up — and the
     * arrows and the sound.
     */
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
        p.rows(
                () -> {
                    List<Page.Item> rows = new ArrayList<>();
                    Valuables.Rule picked = radarRule();
                    int n = 1;
                    for (Valuables.Rule r : c.valuables) {
                        String look =
                                r.style == Valuables.OFF
                                        ? "§7" + r.describe()
                                        : "§" + r.color + "■ §r" + r.describe();
                        rows.add(
                                new Page.Item(
                                        n++ + ". " + look,
                                        tr(Valuables.STYLE_KEYS[r.style]),
                                        r == picked,
                                        () -> radarRule = r,
                                        null));
                    }
                    return rows;
                },
                tr("steincombat.valuables.empty"));
        p.option(
                Option.cycle(
                                INDENT + tr("steincombat.radar.style"),
                                names(Valuables.STYLE_KEYS),
                                () -> radarRule() == null ? 0 : radarRule().style,
                                i -> {
                                    if (radarRule() != null) {
                                        radarRule().style = i;
                                        mod.save();
                                    }
                                })
                        .visibleWhen(() -> radarRule() != null));
        p.option(
                Option.color(
                                INDENT + tr("steincombat.radar.color"),
                                () -> radarRule() == null ? "6" : String.valueOf(radarRule().color),
                                v -> {
                                    if (radarRule() != null) {
                                        radarRule().color = v.isEmpty() ? '6' : v.charAt(0);
                                        mod.save();
                                    }
                                })
                        .visibleWhen(
                                () -> radarRule() != null && radarRule().style != Valuables.OFF));
        p.option(
                Option.action(
                                INDENT + tr("steincombat.radar.up"),
                                "↑",
                                () -> {
                                    Valuables.Rule r = radarRule();
                                    int at = r == null ? -1 : c.valuables.indexOf(r);
                                    if (at > 0) {
                                        c.valuables.set(at, c.valuables.get(at - 1));
                                        c.valuables.set(at - 1, r);
                                        mod.save();
                                    }
                                })
                        .visibleWhen(() -> c.valuables.indexOf(radarRule()) > 0));
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
                    c.radarArrows = d.radarArrows;
                    c.radarSound = d.radarSound;
                    keys.lootRadar.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** The rule Loot Radar's rows edit: the one clicked, while it is still on the list. */
    private Valuables.Rule radarRule() {
        if (radarRule == null || !c.valuables.contains(radarRule)) {
            radarRule = c.valuables.isEmpty() ? null : c.valuables.get(0);
        }
        return radarRule;
    }

    /** Supply Tracker: the sources, the armor (set or by piece) and the key for a rush by hand. */
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
        p.option(SupplyForecast.armorOption(c, INDENT, mod::save));
        p.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.supplyForecast = d.supplyForecast;
                    c.forecastWorn = d.forecastWorn;
                    c.forecastInventory = d.forecastInventory;
                    c.forecastChest = d.forecastChest;
                    c.forecastArmorSet = d.forecastArmorSet;
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
