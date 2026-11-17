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

/**
 * Combat's key bindings (in the game's controls under "Combat", and on each feature's row in the
 * Panel) and what each press does. Their names are the player's data (options.txt): they never
 * change.
 */
final class CombatKeys {

    private static final String CATEGORY = "key.categories.steincombat";

    private final SteinCombat mod;

    final Keys.Binding toggleSprint;
    final Keys.Binding autoWalk;
    final Keys.Binding ally;
    final Keys.Binding enemy;
    final Keys.Binding hide;

    // Each fight feature's on/off (no key by default: the player picks one in the Panel).
    final Keys.Binding noHitDelay;
    final Keys.Binding clanProtect;
    final Keys.Binding autoArmor;
    final Keys.Binding smartEffect;
    final Keys.Binding autoProtect;
    final Keys.Binding autoRefill;
    final Keys.Binding autoclick;

    // The item modules: Auto Stash's key makes a trip; the others turn the module on and off.
    final Keys.Binding autoStash;
    final Keys.Binding itemLock;
    final Keys.Binding autoTrash;
    final Keys.Binding lootRadar;

    /** Supply Tracker: starts a rush, or ends the one running. */
    final Keys.Binding forecastRush;

    /** Registers the bindings with the game; in afterStartGame. */
    CombatKeys(SteinCombat mod) {
        this.mod = mod;
        toggleSprint = Keys.bind("key.steincombat.toggleSprint", Keys.KEY_G, CATEGORY);
        autoWalk = Keys.bind("key.steincombat.autoWalk", Keys.KEY_J, CATEGORY);
        ally = Keys.bind("key.steincombat.ally", Keys.KEY_Y, CATEGORY);
        enemy = Keys.bind("key.steincombat.enemy", Keys.KEY_U, CATEGORY);
        hide = Keys.bind("key.steincombat.hide", Keys.KEY_H, CATEGORY);
        noHitDelay = Keys.bind("key.steincombat.noHitDelay", Keys.KEY_NONE, CATEGORY);
        clanProtect = Keys.bind("key.steincombat.clanProtect", Keys.KEY_NONE, CATEGORY);
        autoArmor = Keys.bind("key.steincombat.autoArmor", Keys.KEY_NONE, CATEGORY);
        smartEffect = Keys.bind("key.steincombat.smartEffect", Keys.KEY_NONE, CATEGORY);
        autoProtect = Keys.bind("key.steincombat.autoProtect", Keys.KEY_NONE, CATEGORY);
        autoRefill = Keys.bind("key.steincombat.autoRefill", Keys.KEY_NONE, CATEGORY);
        autoclick = Keys.bind("key.steincombat.autoclick", Keys.KEY_NONE, CATEGORY);
        autoStash = Keys.bind("key.steincombat.autoStash", Keys.KEY_NONE, CATEGORY);
        itemLock = Keys.bind("key.steincombat.itemLock", Keys.KEY_NONE, CATEGORY);
        autoTrash = Keys.bind("key.steincombat.autoTrash", Keys.KEY_NONE, CATEGORY);
        lootRadar = Keys.bind("key.steincombat.lootRadar", Keys.KEY_NONE, CATEGORY);
        forecastRush = Keys.bind("key.steincombat.forecastRush", Keys.KEY_NONE, CATEGORY);
    }

    /** Every tick: the presses since the last one, in order. */
    void handle() {
        CombatConfig c = mod.config;
        while (toggleSprint.consumeClick()) {
            c.toggleSprint = !c.toggleSprint;
            mod.save();
            Say.actionBar(
                    Lang.tr(c.toggleSprint ? "steincombat.sprintOn" : "steincombat.sprintOff"));
        }
        while (autoWalk.consumeClick()) {
            mod.autoWalk.toggle();
            Say.actionBar(
                    Lang.tr(
                            mod.autoWalk.on ? "steincombat.featureOn" : "steincombat.featureOff",
                            Lang.tr("steincombat.autoWalk")));
        }
        while (ally.consumeClick()) {
            mod.marks.allyAtCrosshair();
        }
        while (enemy.consumeClick()) {
            mod.marks.enemyAtCrosshair();
        }
        while (hide.consumeClick()) {
            mod.guard.hiding = !mod.guard.hiding;
            Say.chat(Lang.tr(mod.guard.hiding ? "steincombat.hideOn" : "steincombat.hideOff"));
        }
        while (noHitDelay.consumeClick()) {
            c.noHitDelay = !c.noHitDelay;
            toggled("steincombat.noHitDelay", c.noHitDelay);
        }
        while (clanProtect.consumeClick()) {
            c.protectAllies = !c.protectAllies;
            toggled("steincombat.protect", c.protectAllies);
        }
        while (autoArmor.consumeClick()) {
            c.autoArmor = !c.autoArmor;
            toggled("steincombat.autoArmor", c.autoArmor);
        }
        while (autoRefill.consumeClick()) {
            // In on-key mode, pressing asks for one trip to the chest (which turns itself off at
            // the
            // end), and does not toggle: pressing again in the middle of a trip does not cancel it.
            c.autoRefill = c.refillMode == AutoRefill.ON_KEY || !c.autoRefill;
            toggled("steincombat.autoRefill", c.autoRefill);
        }
        while (autoProtect.consumeClick()) {
            c.autoProtect = !c.autoProtect;
            toggled("steincombat.autoProtect", c.autoProtect);
        }
        while (smartEffect.consumeClick()) {
            c.smartEffect = !c.smartEffect;
            toggled("steincombat.smartEffect", c.smartEffect);
        }
        while (autoclick.consumeClick()) {
            c.autoclick = !c.autoclick;
            toggled("steincombat.autoclick", c.autoclick);
        }
        while (autoStash.consumeClick()) {
            mod.autoStash.request();
        }
        while (itemLock.consumeClick()) {
            c.itemLock = !c.itemLock;
            toggled("steincombat.itemLock", c.itemLock);
        }
        while (autoTrash.consumeClick()) {
            c.autoTrash = !c.autoTrash;
            toggled("steincombat.autoTrash", c.autoTrash);
        }
        while (lootRadar.consumeClick()) {
            c.lootRadar = !c.lootRadar;
            toggled("steincombat.lootRadar", c.lootRadar);
        }
        while (forecastRush.consumeClick()) {
            mod.supplies.toggle();
            Say.actionBar(
                    Lang.tr(
                            mod.supplies.running()
                                    ? "steincombat.forecast.started"
                                    : "steincombat.forecast.ended"));
        }
    }

    /** A feature turned on or off by its key: saves and says so on the action bar. */
    private void toggled(String nameKey, boolean on) {
        mod.save();
        Say.actionBar(
                Lang.tr(on ? "steincombat.featureOn" : "steincombat.featureOff", Lang.tr(nameKey)));
    }
}
