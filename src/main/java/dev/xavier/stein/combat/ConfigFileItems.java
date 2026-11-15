/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The item modules' part of config/steincombat.json (Valuable Items, Item Lock, Auto Stash, Loot
 * Radar, Loot Swap, Supply Forecast), read and written by {@link ConfigFile} in the same file. The
 * keys are the player's data: they never change. The Valuable Items rules store the enchantment by
 * the game's id, not by its place in the Panel's list, so the list can grow without breaking files.
 */
final class ConfigFileItems {

    private ConfigFileItems() {}

    static void read(CombatConfig c, JsonObject o) {
        if (o.has("valuables") && o.get("valuables").isJsonArray()) {
            c.valuables.clear();
            for (JsonElement e : o.getAsJsonArray("valuables")) {
                if (e.isJsonObject()) {
                    c.valuables.add(readRule(e.getAsJsonObject()));
                }
            }
        }
        c.itemLock = bool(o, "itemLock", c.itemLock);
        c.itemLockAll = bool(o, "itemLockAll", c.itemLockAll);
        c.autoStash = bool(o, "autoStash", c.autoStash);
        c.stashHealth = integer(o, "stashHealth", c.stashHealth, 1, 19);
        if (o.has("stashCommand")) {
            c.stashCommand = o.get("stashCommand").getAsString();
        }
        c.stashShowWindow = bool(o, "stashShowWindow", c.stashShowWindow);
        c.stashHotbar = bool(o, "stashHotbar", c.stashHotbar);
        c.stashKeepHeld = bool(o, "stashKeepHeld", c.stashKeepHeld);
        c.stashKeepApples = integer(o, "stashKeepApples", c.stashKeepApples, 0, 64);
        c.stashKeepSet = bool(o, "stashKeepSet", c.stashKeepSet);
        c.stashWorn = bool(o, "stashWorn", c.stashWorn);
        c.lootRadar = bool(o, "lootRadar", c.lootRadar);
        c.radarDistance = integer(o, "radarDistance", c.radarDistance, 8, 64);
        c.radarMax = integer(o, "radarMax", c.radarMax, 1, CombatConfig.RADAR_MAX_LIMIT);
        c.radarFade = bool(o, "radarFade", c.radarFade);
        c.radarDeathOnly = bool(o, "radarDeathOnly", c.radarDeathOnly);
        c.radarArrows = bool(o, "radarArrows", c.radarArrows);
        c.radarSound = bool(o, "radarSound", c.radarSound);
        c.lootSwap = bool(o, "lootSwap", c.lootSwap);
        c.swapRadius = integer(o, "swapRadius", c.swapRadius, 1, 6);
        c.swapMaxProtection = integer(o, "swapMaxProtection", c.swapMaxProtection, 0, 10);
        c.swapMaxDurability = integer(o, "swapMaxDurability", c.swapMaxDurability, 5, 100);
        c.supplyForecast = bool(o, "supplyForecast", c.supplyForecast);
        c.forecastAutoStart = bool(o, "forecastAutoStart", c.forecastAutoStart);
        c.forecastWorn = bool(o, "forecastWorn", c.forecastWorn);
        c.forecastInventory = bool(o, "forecastInventory", c.forecastInventory);
        c.forecastChest = bool(o, "forecastChest", c.forecastChest);
        c.forecastView = integer(o, "forecastView", c.forecastView, 0, 2);
    }

    static void write(CombatConfig c, JsonObject o) {
        JsonArray rules = new JsonArray();
        for (Valuables.Rule r : c.valuables) {
            rules.add(writeRule(r));
        }
        o.add("valuables", rules);
        o.addProperty("itemLock", c.itemLock);
        o.addProperty("itemLockAll", c.itemLockAll);
        o.addProperty("autoStash", c.autoStash);
        o.addProperty("stashHealth", c.stashHealth);
        o.addProperty("stashCommand", c.stashCommand);
        o.addProperty("stashShowWindow", c.stashShowWindow);
        o.addProperty("stashHotbar", c.stashHotbar);
        o.addProperty("stashKeepHeld", c.stashKeepHeld);
        o.addProperty("stashKeepApples", c.stashKeepApples);
        o.addProperty("stashKeepSet", c.stashKeepSet);
        o.addProperty("stashWorn", c.stashWorn);
        o.addProperty("lootRadar", c.lootRadar);
        o.addProperty("radarDistance", c.radarDistance);
        o.addProperty("radarMax", c.radarMax);
        o.addProperty("radarFade", c.radarFade);
        o.addProperty("radarDeathOnly", c.radarDeathOnly);
        o.addProperty("radarArrows", c.radarArrows);
        o.addProperty("radarSound", c.radarSound);
        o.addProperty("lootSwap", c.lootSwap);
        o.addProperty("swapRadius", c.swapRadius);
        o.addProperty("swapMaxProtection", c.swapMaxProtection);
        o.addProperty("swapMaxDurability", c.swapMaxDurability);
        o.addProperty("supplyForecast", c.supplyForecast);
        o.addProperty("forecastAutoStart", c.forecastAutoStart);
        o.addProperty("forecastWorn", c.forecastWorn);
        o.addProperty("forecastInventory", c.forecastInventory);
        o.addProperty("forecastChest", c.forecastChest);
        o.addProperty("forecastView", c.forecastView);
    }

    private static Valuables.Rule readRule(JsonObject o) {
        Valuables.Rule r = new Valuables.Rule();
        r.kind = integer(o, "kind", Valuables.ANY, 0, Valuables.KIND_KEYS.length - 1);
        int id = o.has("enchant") ? o.get("enchant").getAsInt() : -1;
        r.enchant = 0;
        for (int i = 0; i < Valuables.ENCHANTS.length; i++) {
            if (Valuables.ENCHANTS[i] == id) {
                r.enchant = i;
            }
        }
        r.level = integer(o, "level", 1, 1, Valuables.MAX_LEVEL);
        r.style = integer(o, "style", Valuables.GLOW, 0, Valuables.STYLE_KEYS.length - 1);
        String color = o.has("color") ? o.get("color").getAsString() : "6";
        r.color = color.isEmpty() ? '6' : color.charAt(0);
        return r;
    }

    private static JsonObject writeRule(Valuables.Rule r) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind);
        o.addProperty("enchant", Valuables.ENCHANTS[r.enchant]);
        o.addProperty("level", r.level);
        o.addProperty("style", r.style);
        o.addProperty("color", String.valueOf(r.color));
        return o;
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    private static int integer(JsonObject o, String key, int def, int min, int max) {
        return o.has(key) ? Math.max(min, Math.min(max, o.get(key).getAsInt())) : def;
    }
}
