/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import java.util.List;
import java.util.function.Predicate;

/**
 * Combat's features that turn on and off, in the Panel's order: the name (the same in the Panel, in
 * the on/off notice and in the HUD list), the category (the color in the list) and whether it is on
 * now. A single place: the HUD list ({@link ActiveFeatures}) and each one's "Show in list" come
 * from here.
 */
final class Features {

    static final int COMBAT = 0;
    static final int MOVEMENT = 1;
    static final int TEAM = 2;
    static final int AUTOMATION = 3;
    static final int ITEMS = 4;

    /**
     * Each category's color in the list: combat red, movement blue, team green, automation violet.
     */
    static final int[] COLORS = {0xFFFF5F5F, 0xFF5FB4FF, 0xFF5FE08A, 0xFFB98CFF, 0xFFFFC14D};

    static final class Feature {
        /** The id stored in {@link CombatConfig#featuresHidden}. */
        final String id;

        final String nameKey;
        final int category;
        final Predicate<SteinCombat> on;

        Feature(String id, String nameKey, int category, Predicate<SteinCombat> on) {
            this.id = id;
            this.nameKey = nameKey;
            this.category = category;
            this.on = on;
        }
    }

    static final List<Feature> ALL =
            List.of(
                    new Feature(
                            "noHitDelay",
                            "steincombat.noHitDelay",
                            COMBAT,
                            m -> m.config.noHitDelay),
                    new Feature(
                            "autoclick", "steincombat.autoclick", COMBAT, m -> m.config.autoclick),
                    new Feature("autoWalk", "steincombat.autoWalk", MOVEMENT, m -> m.autoWalk.on),
                    new Feature(
                            "clanProtect",
                            "steincombat.protect",
                            TEAM,
                            m -> m.config.protectAllies),
                    new Feature("hiddenAllies", "steincombat.hide", TEAM, m -> m.guard.hiding),
                    new Feature(
                            "autoArmor",
                            "steincombat.autoArmor",
                            AUTOMATION,
                            m -> m.config.autoArmor),
                    new Feature(
                            "autoRefill",
                            "steincombat.autoRefill",
                            AUTOMATION,
                            m -> m.config.autoRefill),
                    new Feature(
                            "smartEffect",
                            "steincombat.smartEffect",
                            AUTOMATION,
                            m -> m.config.smartEffect),
                    new Feature(
                            "autoProtect",
                            "steincombat.autoProtect",
                            AUTOMATION,
                            m -> m.config.autoProtect),
                    new Feature("itemLock", "steincombat.itemLock", ITEMS, m -> m.config.itemLock),
                    new Feature(
                            "autoTrash", "steincombat.autoTrash", ITEMS, m -> m.config.autoTrash),
                    new Feature(
                            "autoStash", "steincombat.autoStash", ITEMS, m -> m.config.autoStash),
                    new Feature(
                            "lootRadar", "steincombat.lootRadar", ITEMS, m -> m.config.lootRadar),
                    new Feature("armorGap", "steincombat.armorGap", COMBAT, m -> m.config.armorGap),
                    new Feature(
                            "supplyForecast",
                            "steincombat.supplyForecast",
                            ITEMS,
                            m -> m.config.supplyForecast));

    private Features() {}

    static Feature byId(String id) {
        for (Feature f : ALL) {
            if (f.id.equals(id)) {
                return f;
            }
        }
        return null;
    }
}
