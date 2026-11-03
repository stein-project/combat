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
import dev.xavier.stein.loader.api.Panel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Combat's page in the Panel, the only place to change it: options and the lists, all there. The
 * mod has no settings screen of its own (the Loader menu's "Configure" stays off for it). Option
 * labels, order, sections and the "Show in list" ids are what the player knows: they stay as they
 * are.
 */
final class CombatPage {

    /** "Show in list" next to each feature. */
    private static final String SHOW_IN_LIST = "steincombat.showInList";

    /** Sub-options are indented under their option. */
    private static final String INDENT = "  ";

    private final SteinCombat mod;
    private final CombatKeys keys;
    private final CombatConfig c;

    private CombatPage(SteinCombat mod) {
        this.mod = mod;
        this.keys = mod.keys;
        this.c = mod.config;
    }

    /** Builds the page; the Panel calls it every time it opens. */
    static void build(SteinCombat mod, Page page) {
        new CombatPage(mod).fill(page);
    }

    private void fill(Page page) {
        page.icon("combat");
        combat(page);
        movement(page);
        team(page);
        autoArmor(page);
        autoRefill(page);
        smartEffect(page);
        autoProtect(page);
        colors(page);
        // The list from the internet comes before the lists: it decides whether the ones below
        // apply.
        remote(page);
        lists(page);
    }

    /** A feature row: the switch, its key and "Show in list". */
    private Option feature(
            String nameKey,
            BooleanSupplier get,
            java.util.function.Consumer<Boolean> set,
            Keys.Binding key,
            String listId) {
        return Option.toggle(tr(nameKey), get, set)
                .withKey(key::key, key::setKey)
                .withCheck(tr(SHOW_IN_LIST), () -> listed(listId), v -> setListed(listId, v));
    }

    /** Combat: the hit. */
    private void combat(Page page) {
        page.section(tr("steincombat.section.combat"));
        page.option(
                feature(
                        "steincombat.noHitDelay",
                        () -> c.noHitDelay,
                        v -> {
                            c.noHitDelay = v;
                            mod.save();
                        },
                        keys.noHitDelay,
                        "noHitDelay"));
        // Autoclick: last in line (it waits for armor, refill, effects and blocking), only with no
        // screen open.
        page.option(
                feature(
                        "steincombat.autoclick",
                        () -> c.autoclick,
                        v -> {
                            c.autoclick = v;
                            mod.save();
                        },
                        keys.autoclick,
                        "autoclick"));
        page.option(
                Option.cycle(
                                INDENT + tr("steincombat.autoclick.mode"),
                                new String[] {
                                    tr("steincombat.autoclick.mode.hold"),
                                    tr("steincombat.autoclick.mode.always")
                                },
                                () -> c.autoclickMode,
                                i -> {
                                    c.autoclickMode = i;
                                    mod.save();
                                })
                        .visibleWhen(() -> c.autoclick));
        // Always shown: the rate can be set before the autoclick is turned on by its key.
        page.option(
                Option.slider(
                        INDENT + tr("steincombat.autoclick.cps"),
                        CombatConfig.AUTOCLICK_MIN,
                        CombatConfig.AUTOCLICK_MAX,
                        1,
                        () -> c.autoclickCps,
                        v -> {
                            c.autoclickCps = (int) Math.round(v);
                            mod.save();
                        },
                        v -> (int) Math.round(v) + " CPS"));
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.noHitDelay = d.noHitDelay;
                    c.autoclick = d.autoclick;
                    c.autoclickMode = d.autoclickMode;
                    c.autoclickCps = d.autoclickCps;
                    mod.apply();
                    mod.save();
                    keys.noHitDelay.setKey(Keys.KEY_NONE);
                    keys.autoclick.setKey(Keys.KEY_NONE);
                });
    }

    /** Movement: sprinting and walking. */
    private void movement(Page page) {
        page.section(tr("steincombat.section.movement"));
        // Toggle sprint: the key (G) turns it on and off, and so does the row.
        page.option(
                feature(
                        "steincombat.toggleSprintName",
                        () -> c.toggleSprint,
                        v -> {
                            c.toggleSprint = v;
                            mod.save();
                        },
                        keys.toggleSprint,
                        "toggleSprint"));
        // Auto Walk: its key (J) starts and stops it; with toggle sprint on, it sprints.
        AutoWalk walk = mod.autoWalk;
        page.option(
                feature(
                        "steincombat.autoWalk",
                        () -> walk.on,
                        v -> walk.on = v,
                        keys.autoWalk,
                        "autoWalk"));
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.toggleSprint = d.toggleSprint;
                    mod.save();
                    keys.toggleSprint.setKey(Keys.KEY_G);
                    keys.autoWalk.setKey(Keys.KEY_J);
                });
    }

    /** Team: my clan, the allies and the enemies by tag. */
    private void team(Page page) {
        page.section(tr("steincombat.section.team"));
        page.option(
                feature(
                        "steincombat.protect",
                        () -> c.protectAllies,
                        v -> {
                            c.protectAllies = v;
                            mod.save();
                        },
                        keys.clanProtect,
                        "clanProtect"));
        // Clan Protect's sub-option: the hit cancelled on my clan goes through to whoever is
        // behind.
        page.option(
                toggle(
                                INDENT + tr("steincombat.clanHitThrough"),
                                () -> c.clanHitThrough,
                                v -> c.clanHitThrough = v)
                        .visibleWhen(() -> c.protectAllies));
        ClanGuard guard = mod.guard;
        page.option(
                feature(
                        "steincombat.hide",
                        () -> guard.hiding,
                        v -> guard.hiding = v,
                        keys.hide,
                        "hiddenAllies"));
        // How the hide key hides my clan: disappearing (the aim still finds them), as ghosts (hits
        // go through) or as if they did not exist.
        page.option(
                Option.cycle(
                        INDENT + tr("steincombat.hideStyle"),
                        new String[] {
                            tr("steincombat.hideStyle.disappear"),
                            tr("steincombat.hideStyle.ghost"),
                            tr("steincombat.hideStyle.gone")
                        },
                        () -> c.hideStyle,
                        i -> {
                            c.hideStyle = i;
                            mod.save();
                        }));
        // The other allies are never hidden nor protected: at most ghosts that still take hits.
        page.option(
                toggle(
                        INDENT + tr("steincombat.ghostAllies"),
                        () -> c.ghostAllies,
                        v -> c.ghostAllies = v));
        page.option(
                toggle(
                                INDENT + tr("steincombat.ghostHideName"),
                                () -> c.ghostHideName,
                                v -> c.ghostHideName = v)
                        .visibleWhen(() -> c.hideStyle == ClanGuard.GHOST || c.ghostAllies));
        page.option(
                toggle(
                                INDENT + tr("steincombat.presenceMarker"),
                                () -> c.presenceMarker,
                                v -> c.presenceMarker = v)
                        .visibleWhen(() -> c.hideStyle != ClanGuard.GHOST));
        page.option(
                Option.cycle(
                                INDENT + INDENT + tr("steincombat.markerStyle"),
                                new String[] {
                                    tr("steincombat.markerStyle.footprints"),
                                    tr("steincombat.markerStyle.square")
                                },
                                () -> c.markerStyle,
                                i -> {
                                    c.markerStyle = i;
                                    mod.save();
                                })
                        .visibleWhen(() -> c.hideStyle != ClanGuard.GHOST && c.presenceMarker));
        page.option(
                toggle(
                                INDENT + INDENT + tr("steincombat.markerColored"),
                                () -> c.markerColored,
                                v -> c.markerColored = v)
                        .visibleWhen(() -> c.hideStyle != ClanGuard.GHOST && c.presenceMarker));
        page.option(
                toggle(tr("steincombat.clanAllies"), () -> c.clanAllies, v -> c.clanAllies = v));
        // Off by default: it is what made "[VIP]" and "[ADM]" enemies on a server that is not about
        // clans.
        page.option(
                toggle(
                                tr("steincombat.enemyFromPrefix"),
                                () -> c.enemyFromPrefix,
                                v -> c.enemyFromPrefix = v)
                        .advanced());
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.protectAllies = d.protectAllies;
                    c.clanHitThrough = d.clanHitThrough;
                    c.hideStyle = d.hideStyle;
                    c.ghostAllies = d.ghostAllies;
                    c.ghostHideName = d.ghostHideName;
                    c.presenceMarker = d.presenceMarker;
                    c.markerStyle = d.markerStyle;
                    c.markerColored = d.markerColored;
                    c.clanAllies = d.clanAllies;
                    c.enemyFromPrefix = d.enemyFromPrefix;
                    mod.save();
                    keys.clanProtect.setKey(Keys.KEY_NONE);
                    keys.hide.setKey(Keys.KEY_H);
                });
    }

    /** Auto Armor: on, the key, each piece's way and the swap threshold. */
    private void autoArmor(Page page) {
        page.section(tr("steincombat.autoArmor"));
        page.option(
                feature(
                        "steincombat.autoArmor",
                        () -> c.autoArmor,
                        v -> {
                            c.autoArmor = v;
                            mod.save();
                        },
                        keys.autoArmor,
                        "autoArmor"));
        page.option(
                Option.cycle(
                        tr("steincombat.armor.config"),
                        new String[] {
                            tr("steincombat.armor.simple"), tr("steincombat.armor.perPiece")
                        },
                        () -> c.armorPerPiece ? 1 : 0,
                        i -> {
                            c.armorPerPiece = i == 1;
                            mod.save();
                        }));
        // Simple mode: one rule for the four pieces.
        armorRule(page, c.armorAll, "", () -> !c.armorPerPiece);
        // Per piece: the same rule, one for each piece, with its name in front.
        int[] pieces = {3, 2, 1, 0};
        for (int piece : pieces) {
            armorRule(
                    page,
                    c.armorRules[piece],
                    tr(AutoArmor.PIECE_KEYS[piece]) + ": ",
                    () -> c.armorPerPiece);
        }
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.autoArmor = d.autoArmor;
                    c.armorPerPiece = d.armorPerPiece;
                    c.armorAll.copyFrom(d.armorAll);
                    for (int i = 0; i < 4; i++) {
                        c.armorRules[i].copyFrom(d.armorRules[i]);
                    }
                    mod.save();
                    keys.autoArmor.setKey(Keys.KEY_NONE);
                });
    }

    /**
     * The options of one Auto Armor rule (mode, threshold, priority, durability, unenchanted,
     * keep).
     */
    private void armorRule(Page page, AutoArmor.Rule r, String prefix, BooleanSupplier when) {
        String[] modes = {
            tr("steincombat.armor.off"),
            tr("steincombat.armor.replace"),
            tr("steincombat.armor.swap")
        };
        page.option(
                Option.cycle(
                                prefix + tr("steincombat.armor.mode"),
                                modes,
                                () -> r.mode,
                                m -> {
                                    r.mode = m;
                                    mod.save();
                                })
                        .visibleWhen(when));
        // As Option (and not Option.Slider): visibleWhen is the base class's.
        Option threshold =
                Option.slider(
                        prefix + tr("steincombat.armor.threshold"),
                        5,
                        150,
                        5,
                        () -> r.threshold,
                        v -> {
                            r.threshold = (int) Math.round(v);
                            mod.save();
                        },
                        v -> String.valueOf(Math.round(v)));
        page.option(threshold.visibleWhen(when));
        page.option(
                Option.cycle(
                                prefix + tr("steincombat.armor.priority"),
                                new String[] {
                                    tr("steincombat.armor.highest"), tr("steincombat.armor.lowest")
                                },
                                () -> r.highest ? 0 : 1,
                                i -> {
                                    r.highest = i == 0;
                                    mod.save();
                                })
                        .visibleWhen(when));
        page.option(
                Option.cycle(
                                prefix + tr("steincombat.armor.durability"),
                                new String[] {
                                    tr("steincombat.armor.durability.off"),
                                    tr("steincombat.armor.durability.highest"),
                                    tr("steincombat.armor.durability.lowest")
                                },
                                () -> r.durability,
                                i -> {
                                    r.durability = i;
                                    mod.save();
                                })
                        .visibleWhen(when));
        page.option(
                toggle(
                                prefix + tr("steincombat.armor.unenchanted"),
                                () -> r.unenchanted,
                                v -> r.unenchanted = v)
                        .visibleWhen(when));
        // Do not let it break: off, or Protection IV to X.
        String[] keep = {tr("steincombat.armor.off"), "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        page.option(
                Option.cycle(
                                prefix + tr("steincombat.armor.keep"),
                                keep,
                                () -> r.keepLevel <= 0 ? 0 : r.keepLevel - 3,
                                i -> {
                                    r.keepLevel = i == 0 ? 0 : i + 3;
                                    mod.save();
                                })
                        .visibleWhen(when));
    }

    /** Auto Refill: works without Auto Armor; which pieces and armor fit are Auto Armor's rules. */
    private void autoRefill(Page page) {
        page.section(tr("steincombat.autoRefill"));
        page.option(
                feature(
                        "steincombat.autoRefill",
                        () -> c.autoRefill,
                        v -> {
                            c.autoRefill = v;
                            mod.save();
                        },
                        keys.autoRefill,
                        "autoRefill"));
        page.option(
                Option.cycle(
                        tr("steincombat.refill.mode"),
                        new String[] {
                            tr("steincombat.refill.mode.empty"),
                            tr("steincombat.refill.mode.constant"),
                            tr("steincombat.refill.mode.key")
                        },
                        () -> c.refillMode,
                        i -> {
                            c.refillMode = i;
                            if (i == AutoRefill.ON_KEY) {
                                c.autoRefill = false; // on key it only turns on when asked
                            }
                            mod.save();
                        }));
        // "/bau vip" is the hint: the command of the server it was made for.
        page.option(
                new Option.Text(
                        tr("steincombat.refill.command"),
                        "/bau vip",
                        () -> c.refillCommand,
                        v -> {
                            if (!v.trim().isEmpty()) {
                                c.refillCommand = v;
                                mod.save();
                            }
                        }));
        Option target =
                Option.slider(
                        tr("steincombat.refill.target"),
                        1,
                        9,
                        1,
                        () -> c.refillTarget,
                        v -> {
                            c.refillTarget = (int) Math.round(v);
                            mod.save();
                        },
                        v -> String.valueOf(Math.round(v)));
        page.option(target.visibleWhen(() -> c.refillMode != AutoRefill.WHEN_EMPTY));
        page.option(
                toggle(
                        tr("steincombat.refill.deposit"),
                        () -> c.refillDeposit,
                        v -> c.refillDeposit = v));
        page.option(
                toggle(
                        tr("steincombat.refill.window"),
                        () -> c.refillShowWindow,
                        v -> c.refillShowWindow = v));
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.autoRefill = d.autoRefill;
                    c.refillCommand = d.refillCommand;
                    c.refillShowWindow = d.refillShowWindow;
                    c.refillMode = d.refillMode;
                    c.refillTarget = d.refillTarget;
                    c.refillDeposit = d.refillDeposit;
                    keys.autoRefill.setKey(Keys.KEY_NONE);
                    mod.save();
                });
    }

    /** Smart Effect: what to keep up and when. */
    private void smartEffect(Page page) {
        page.section(tr("steincombat.smartEffect"));
        page.option(
                feature(
                        "steincombat.smartEffect",
                        () -> c.smartEffect,
                        v -> {
                            c.smartEffect = v;
                            mod.save();
                        },
                        keys.smartEffect,
                        "smartEffect"));
        page.option(
                toggle(
                        tr("steincombat.effect.autoGapple"),
                        () -> c.autoGapple,
                        v -> c.autoGapple = v));
        page.option(
                toggle(
                        tr("steincombat.effect.autoSpeed"),
                        () -> c.autoSpeed,
                        v -> c.autoSpeed = v));
        page.option(
                toggle(
                        tr("steincombat.effect.autoStrength"),
                        () -> c.autoStrength,
                        v -> c.autoStrength = v));
        page.option(
                toggle(
                        tr("steincombat.effect.waitEnemy"),
                        () -> c.effectWaitEnemy,
                        v -> c.effectWaitEnemy = v));
        Option radius =
                Option.slider(
                        INDENT + tr("steincombat.effect.radius"),
                        2,
                        32,
                        1,
                        () -> c.effectEnemyRadius,
                        v -> {
                            c.effectEnemyRadius = (int) Math.round(v);
                            mod.save();
                        },
                        v -> Math.round(v) + " " + tr("steincombat.effect.blocks"));
        page.option(radius.visibleWhen(() -> c.effectWaitEnemy));
        page.option(
                toggle(
                        tr("steincombat.effect.onDamage"),
                        () -> c.effectOnDamage,
                        v -> c.effectOnDamage = v));
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.smartEffect = d.smartEffect;
                    c.autoGapple = d.autoGapple;
                    c.autoSpeed = d.autoSpeed;
                    c.autoStrength = d.autoStrength;
                    c.effectWaitEnemy = d.effectWaitEnemy;
                    c.effectEnemyRadius = d.effectEnemyRadius;
                    c.effectOnDamage = d.effectOnDamage;
                    mod.save();
                    keys.smartEffect.setKey(Keys.KEY_NONE);
                });
    }

    /**
     * Auto Protect: works without Smart Effect; in the order, after Regeneration and before Speed
     * and Strength.
     */
    private void autoProtect(Page page) {
        page.section(tr("steincombat.autoProtect"));
        page.option(
                feature(
                        "steincombat.autoProtect",
                        () -> c.autoProtect,
                        v -> {
                            c.autoProtect = v;
                            mod.save();
                        },
                        keys.autoProtect,
                        "autoProtect"));
        page.option(
                toggle(
                        tr("steincombat.protect.defend"),
                        () -> c.protectDefend,
                        v -> c.protectDefend = v));
        page.option(
                toggle(
                        tr("steincombat.protect.survive"),
                        () -> c.protectSurvive,
                        v -> c.protectSurvive = v));
        // In whole hearts (the file keeps health points: 2 per heart).
        Option low =
                Option.slider(
                        INDENT + tr("steincombat.protect.low"),
                        1,
                        8,
                        1,
                        () -> c.protectLowHealth / 2.0,
                        v -> {
                            c.protectLowHealth = 2 * (int) Math.round(v);
                            c.fixProtectHealth(); // the exit goes up too, if it fell below
                            mod.save();
                        },
                        CombatPage::hearts);
        page.option(low.visibleWhen(() -> c.protectSurvive));
        Option resume =
                Option.slider(
                        INDENT + tr("steincombat.protect.resume"),
                        2,
                        10,
                        1,
                        () -> c.protectResumeHealth / 2.0,
                        v -> {
                            c.protectResumeHealth = 2 * (int) Math.round(v);
                            if (c.protectResumeHealth < c.protectLowHealth + 2) {
                                // The entry goes down too, if it ended up above.
                                c.protectLowHealth = c.protectResumeHealth - 2;
                            }
                            mod.save();
                        },
                        CombatPage::hearts);
        page.option(resume.visibleWhen(() -> c.protectSurvive));
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.autoProtect = d.autoProtect;
                    c.protectDefend = d.protectDefend;
                    c.protectSurvive = d.protectSurvive;
                    c.protectLowHealth = d.protectLowHealth;
                    c.protectResumeHealth = d.protectResumeHealth;
                    mod.save();
                    keys.autoProtect.setKey(Keys.KEY_NONE);
                });
    }

    /** The name colors by relation. */
    private void colors(Page page) {
        page.section(tr("steincombat.colorsSection"));
        page.option(
                toggle(tr("steincombat.colorNames"), () -> c.colorNames, v -> c.colorNames = v));
        page.option(
                Option.color(
                                tr("steincombat.clanColor"),
                                () -> String.valueOf(c.clanColor),
                                v -> {
                                    c.clanColor = v.charAt(0);
                                    mod.save();
                                })
                        .advanced());
        page.option(
                Option.color(
                                tr("steincombat.allyColor"),
                                () -> String.valueOf(c.allyColor),
                                v -> {
                                    c.allyColor = v.charAt(0);
                                    mod.save();
                                })
                        .advanced());
        page.option(
                Option.color(
                                tr("steincombat.enemyColor"),
                                () -> String.valueOf(c.enemyColor),
                                v -> {
                                    c.enemyColor = v.charAt(0);
                                    mod.save();
                                })
                        .advanced());
        page.option(
                Option.color(
                                tr("steincombat.neutralColor"),
                                () -> String.valueOf(c.neutralColor),
                                v -> {
                                    c.neutralColor = v.charAt(0);
                                    mod.save();
                                })
                        .advanced());
        page.restore(
                () -> {
                    CombatConfig d = new CombatConfig();
                    c.colorNames = d.colorNames;
                    c.clanColor = d.clanColor;
                    c.allyColor = d.allyColor;
                    c.enemyColor = d.enemyColor;
                    c.neutralColor = d.neutralColor;
                    mod.save();
                });
    }

    /** The list from the internet: URL mode, the address and "Refresh now". */
    private void remote(Page page) {
        RemoteList remote = mod.remote;
        page.section(tr("steincombat.remoteSection"));
        page.option(
                Option.toggle(
                        tr("steincombat.remoteOn"),
                        () -> c.remote,
                        v -> {
                            c.remote = v;
                            mod.save();
                            if (v) {
                                remote.fetch(false);
                            }
                            // The page changes shape (the lists become read-only, or get their
                            // fields back), and it is built when the Panel opens: without
                            // reopening,
                            // the player would turn the mode off and keep seeing the list without
                            // the fields, not understanding why they cannot write in it.
                            Panel.open(null, SteinCombat.NAME);
                        }));
        page.option(
                new Option.Text(
                        tr("steincombat.remoteUrl"),
                        tr("steincombat.remoteUrlHint"),
                        () -> c.remoteUrl,
                        v -> {
                            c.url(v);
                            remote.clearFailure();
                            mod.save();
                        }));
        page.option(
                new LiveLine(
                        tr("steincombat.remoteRefresh"), remote::status, () -> remote.fetch(true)));
        page.restore(
                () -> {
                    c.remote = false;
                    c.remoteUrl = "";
                    c.remoteFeeds.clear();
                    remote.clearFailure();
                    mod.save();
                });
    }

    /**
     * The allies and enemies lists: read-only in URL mode (the file rules them), editable
     * otherwise.
     */
    private void lists(Page page) {
        boolean url = c.remote;
        Marks marks = mod.marks;
        page.section(tr("steincombat.alliesShort"));
        if (url) {
            page.items(
                    () -> chips(mod.remote.feed().allyClans, mod.remote.feed().allies),
                    s -> {},
                    "",
                    false);
        } else {
            page.option(
                    new Option.Text(
                            tr("steincombat.addLabel"),
                            "",
                            () -> marks.pendingAlly,
                            v -> marks.pendingAlly = v));
            page.option(
                    Option.action(tr("steincombat.addPlayer"), "", () -> marks.addAlly(false))
                            .withKey(keys.ally::key, keys.ally::setKey));
            page.option(Option.action(tr("steincombat.addClan"), "", () -> marks.addAlly(true)));
            page.items(
                    () -> chips(c.allyClans, c.allies),
                    s -> {
                        String t = clanChip(s);
                        if (t != null) {
                            c.allyClans.remove(t);
                        } else {
                            c.removeAlly(s);
                        }
                        mod.save();
                    },
                    "",
                    true);
            // The default is no marked ally.
            page.restore(
                    () -> {
                        c.allies.clear();
                        c.allyClans.clear();
                        mod.save();
                    });
        }
        page.section(tr("steincombat.enemiesShort"));
        if (url) {
            page.items(
                    () -> chips(mod.remote.feed().enemyClans, mod.remote.feed().enemies),
                    s -> {},
                    "",
                    false);
        } else {
            page.option(
                    new Option.Text(
                            tr("steincombat.addLabel"),
                            "",
                            () -> marks.pendingEnemy,
                            v -> marks.pendingEnemy = v));
            page.option(
                    Option.action(tr("steincombat.addPlayer"), "", () -> marks.addEnemy(false))
                            .withKey(keys.enemy::key, keys.enemy::setKey));
            page.option(Option.action(tr("steincombat.addClan"), "", () -> marks.addEnemy(true)));
            page.items(
                    () -> chips(c.enemyClans, c.enemies),
                    s -> {
                        String t = clanChip(s);
                        if (t != null) {
                            c.enemyClans.remove(t);
                        } else {
                            c.removeEnemy(s);
                        }
                        mod.save();
                    },
                    "",
                    true);
            page.restore(
                    () -> {
                        c.enemies.clear();
                        c.enemyClans.clear();
                        mod.save();
                    });
        }
    }

    /** A plain switch that saves on change. */
    private Option toggle(
            String label, BooleanSupplier get, java.util.function.Consumer<Boolean> set) {
        return Option.toggle(
                label,
                get,
                v -> {
                    set.accept(v);
                    mod.save();
                });
    }

    /**
     * Does the feature show in the HUD's active feature list ({@link ActiveFeatures})? It is the
     * checkbox on its row in the Panel (Option.withCheck).
     */
    private boolean listed(String id) {
        return !c.featuresHidden.contains(id);
    }

    private void setListed(String id, boolean shown) {
        c.featuresHidden.remove(id);
        if (!shown) {
            c.featuresHidden.add(id);
        }
        mod.save();
    }

    /**
     * A list for the Panel: the clans first, in brackets, then the nicks. The brackets are not
     * decoration — they tell the click whether it removed a clan or a player, and a three-letter
     * clan looks just like a three-letter nick.
     */
    private static List<String> chips(List<String> clans, List<String> nicks) {
        List<String> all = new ArrayList<>();
        for (String tag : clans) {
            all.add("[" + tag + "]");
        }
        all.addAll(nicks);
        return all;
    }

    /** The tag of a list item that is a clan ("[ABC]" -> "ABC"), or null if it is a nick. */
    private static String clanChip(String item) {
        return item.startsWith("[") ? Clans.tag(item) : null;
    }

    /** The hearts of the slider: "1 heart", "3 hearts". */
    private static String hearts(double v) {
        long n = Math.round(v);
        return n == 1 ? tr("steincombat.protect.heart") : Lang.tr("steincombat.protect.hearts", n);
    }

    private static String tr(String key) {
        return Lang.tr(key);
    }
}
