/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.xavier.stein.loader.api.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * config/steincombat.json, read and written by hand: the file has its own shape (the four armor
 * rules by name, the "remote" block, colors as one-letter strings) and its own compatibility rules
 * with older files, which a field-by-field mapping would lose. The keys are the player's data: they
 * never change.
 */
final class ConfigFile {

    /** Combat's defaults (what applies without a file and what "Restore defaults" brings back). */
    static final String DEFAULTS = "/assets/steincombat/defaults.json";

    /** The armor rules of "Per piece" mode, in the game's piece order. */
    private static final String[] RULE_KEYS = {
        "armorBoots", "armorLeggings", "armorChestplate", "armorHelmet"
    };

    /** How many stored lists are kept besides the one in use (see {@link #writeRemote}). */
    private static final int SPARE_FEEDS = 8;

    private static final Log LOG = Log.of(SteinCombat.MOD_ID);

    private ConfigFile() {}

    /** Reads the package defaults over the field values of a new config. */
    static void readDefaults(CombatConfig c) {
        try (InputStream in = ConfigFile.class.getResourceAsStream(DEFAULTS)) {
            if (in != null) {
                read(c, parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            LOG.warn("unreadable package defaults: " + e);
        }
    }

    /** The player's file over the defaults; created with the defaults when missing. */
    static CombatConfig load(File file) {
        CombatConfig c = new CombatConfig();
        if (!file.isFile()) {
            save(c, file);
            return c;
        }
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            read(c, parse(r));
        } catch (Exception e) {
            LOG.warn(file + " unreadable, using the defaults: " + e);
        }
        return c;
    }

    @SuppressWarnings("deprecation") // the game's Gson only has the instance parse
    private static JsonObject parse(Reader r) {
        return new JsonParser().parse(r).getAsJsonObject();
    }

    /**
     * Reads the options of a JSON over the current ones: the player's file, or the package defaults
     * ({@link #DEFAULTS}). What is missing in the JSON stays as it is.
     */
    private static void read(CombatConfig c, JsonObject o) {
        c.toggleSprint = bool(o, "toggleSprint", c.toggleSprint);
        c.protectAllies = bool(o, "protectAllies", c.protectAllies);
        if (o.has("hideStyle")) {
            c.hideStyle = clamp(o.get("hideStyle").getAsInt(), 0, ClanGuard.GONE);
        }
        c.ghostAllies = bool(o, "ghostAllies", c.ghostAllies);
        c.ghostHideName = bool(o, "ghostHideName", c.ghostHideName);
        c.presenceMarker = bool(o, "presenceMarker", c.presenceMarker);
        if (o.has("markerStyle")) {
            c.markerStyle = clamp(o.get("markerStyle").getAsInt(), 0, PresenceMarker.SQUARE);
        }
        c.markerColored = bool(o, "markerColored", c.markerColored);
        c.colorNames = bool(o, "colorNames", bool(o, "colorAllies", c.colorNames));
        // A file from before the four colors (it only had "allyColor", used for allies and for my
        // clan): the new colors all start at the default, otherwise the green from there would
        // become the ally color and nobody would understand why ally and my clan look the same.
        if (o.has("clanColor")) {
            c.clanColor = color(o, "clanColor", c.clanColor);
            c.allyColor = color(o, "allyColor", c.allyColor);
            c.neutralColor = color(o, "neutralColor", c.neutralColor);
            c.enemyColor = color(o, "enemyColor", c.enemyColor);
        }
        c.clanAllies = bool(o, "clanAllies", c.clanAllies);
        c.clanFromPrefix = bool(o, "clanFromPrefix", c.clanFromPrefix);
        c.enemyFromPrefix = bool(o, "enemyFromPrefix", c.enemyFromPrefix);
        c.nearestName = bool(o, "nearestName", c.nearestName);
        c.nearestAllyName = bool(o, "nearestAllyName", c.nearestAllyName);
        c.featuresColor = color(o, "featuresColor", c.featuresColor);
        c.featuresByWidth = bool(o, "featuresByWidth", c.featuresByWidth);
        if (o.has("featuresStyle")) {
            c.featuresStyle = clamp(o.get("featuresStyle").getAsInt(), 0, 2);
        }
        c.featuresBackground = bool(o, "featuresBackground", c.featuresBackground);
        if (o.has("featuresHidden") && o.get("featuresHidden").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("featuresHidden")) {
                c.featuresHidden.add(e.getAsString());
            }
        }
        c.autoArmor = bool(o, "autoArmor", c.autoArmor);
        c.smartEffect = bool(o, "smartEffect", c.smartEffect);
        c.autoGapple = bool(o, "autoGapple", c.autoGapple);
        c.autoSpeed = bool(o, "autoSpeed", c.autoSpeed);
        c.autoStrength = bool(o, "autoStrength", c.autoStrength);
        c.effectWaitEnemy = bool(o, "effectWaitEnemy", c.effectWaitEnemy);
        c.effectOnDamage = bool(o, "effectOnDamage", c.effectOnDamage);
        c.autoProtect = bool(o, "autoProtect", c.autoProtect);
        c.protectDefend = bool(o, "protectDefend", c.protectDefend);
        // A file from before "Low health block": whoever already used Auto Protect keeps it as it
        // was (only blocking with a screen open); the new part is for turning on in the Panel.
        c.protectSurvive = bool(o, "protectSurvive", c.protectSurvive && !c.autoProtect);
        if (o.has("protectLowHealth")) {
            c.protectLowHealth = clamp(o.get("protectLowHealth").getAsInt(), 2, 16);
        }
        if (o.has("protectResumeHealth")) {
            c.protectResumeHealth = clamp(o.get("protectResumeHealth").getAsInt(), 4, 20);
        }
        c.fixProtectHealth();
        c.clanHitThrough = bool(o, "clanHitThrough", c.clanHitThrough);
        c.autoclick = bool(o, "autoclick", c.autoclick);
        if (o.has("autoclickMode")) {
            c.autoclickMode = clamp(o.get("autoclickMode").getAsInt(), 0, 1);
        }
        if (o.has("autoclickCps")) {
            c.autoclickCps =
                    clamp(
                            o.get("autoclickCps").getAsInt(),
                            CombatConfig.AUTOCLICK_MIN,
                            CombatConfig.AUTOCLICK_MAX);
        }
        c.autoRefill = bool(o, "autoRefill", c.autoRefill);
        if (o.has("refillCommand") && !o.get("refillCommand").getAsString().trim().isEmpty()) {
            c.refillCommand = o.get("refillCommand").getAsString();
        }
        c.refillShowWindow = bool(o, "refillShowWindow", c.refillShowWindow);
        if (o.has("refillMode")) {
            int m = o.get("refillMode").getAsInt();
            c.refillMode =
                    m == AutoRefill.CONSTANT || m == AutoRefill.ON_KEY ? m : AutoRefill.WHEN_EMPTY;
        }
        if (o.has("refillTarget")) {
            c.refillTarget = clamp(o.get("refillTarget").getAsInt(), 1, 27);
        }
        c.refillDeposit = bool(o, "refillDeposit", c.refillDeposit);
        if (o.has("effectEnemyRadius")) {
            c.effectEnemyRadius = clamp(o.get("effectEnemyRadius").getAsInt(), 2, 64);
        }
        c.armorPerPiece = bool(o, "armorPerPiece", c.armorPerPiece);
        readRule(o, "armorAll", c.armorAll);
        for (int i = 0; i < 4; i++) {
            readRule(o, RULE_KEYS[i], c.armorRules[i]);
        }
        c.noHitDelay = bool(o, "noHitDelay", c.noHitDelay);
        if (o.has("allies") && o.get("allies").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("allies")) {
                String nick = e.getAsString().trim();
                if (Clans.validNick(nick) && !c.hasAlly(nick)) {
                    c.allies.add(nick);
                }
            }
        }
        readTags(o, "allyClans", c.allyClans);
        readTags(o, "enemyClans", c.enemyClans);
        readRemote(c, o.get("remote"));
        if (o.has("enemies") && o.get("enemies").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("enemies")) {
                String nick = e.getAsString().trim();
                if (Clans.validNick(nick) && !c.hasEnemy(nick) && !c.hasAlly(nick)) {
                    c.enemies.add(nick);
                }
            }
        }
    }

    private static void readTags(JsonObject o, String key, List<String> out) {
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                String tag = Clans.tag(e.getAsString());
                if (tag != null && !out.contains(tag)) {
                    out.add(tag);
                }
            }
        }
    }

    static void save(CombatConfig c, File file) {
        JsonObject o = new JsonObject();
        o.addProperty("toggleSprint", c.toggleSprint);
        o.addProperty("protectAllies", c.protectAllies);
        o.addProperty("hideStyle", c.hideStyle);
        o.addProperty("ghostAllies", c.ghostAllies);
        o.addProperty("ghostHideName", c.ghostHideName);
        o.addProperty("presenceMarker", c.presenceMarker);
        o.addProperty("markerStyle", c.markerStyle);
        o.addProperty("markerColored", c.markerColored);
        o.addProperty("colorNames", c.colorNames);
        o.addProperty("clanColor", String.valueOf(c.clanColor));
        o.addProperty("allyColor", String.valueOf(c.allyColor));
        o.addProperty("neutralColor", String.valueOf(c.neutralColor));
        o.addProperty("enemyColor", String.valueOf(c.enemyColor));
        o.addProperty("clanAllies", c.clanAllies);
        o.addProperty("clanFromPrefix", c.clanFromPrefix);
        o.addProperty("enemyFromPrefix", c.enemyFromPrefix);
        o.addProperty("nearestName", c.nearestName);
        o.addProperty("nearestAllyName", c.nearestAllyName);
        o.addProperty("featuresColor", String.valueOf(c.featuresColor));
        o.addProperty("featuresByWidth", c.featuresByWidth);
        o.addProperty("featuresStyle", c.featuresStyle);
        o.addProperty("featuresBackground", c.featuresBackground);
        o.add("featuresHidden", strings(c.featuresHidden));
        o.addProperty("autoArmor", c.autoArmor);
        o.addProperty("smartEffect", c.smartEffect);
        o.addProperty("autoGapple", c.autoGapple);
        o.addProperty("autoSpeed", c.autoSpeed);
        o.addProperty("autoStrength", c.autoStrength);
        o.addProperty("effectWaitEnemy", c.effectWaitEnemy);
        o.addProperty("effectEnemyRadius", c.effectEnemyRadius);
        o.addProperty("effectOnDamage", c.effectOnDamage);
        o.addProperty("autoProtect", c.autoProtect);
        o.addProperty("protectDefend", c.protectDefend);
        o.addProperty("protectSurvive", c.protectSurvive);
        o.addProperty("protectLowHealth", c.protectLowHealth);
        o.addProperty("protectResumeHealth", c.protectResumeHealth);
        o.addProperty("clanHitThrough", c.clanHitThrough);
        o.addProperty("autoclick", c.autoclick);
        o.addProperty("autoclickMode", c.autoclickMode);
        o.addProperty("autoclickCps", c.autoclickCps);
        o.addProperty("autoRefill", c.autoRefill);
        o.addProperty("refillCommand", c.refillCommand);
        o.addProperty("refillShowWindow", c.refillShowWindow);
        o.addProperty("refillMode", c.refillMode);
        o.addProperty("refillTarget", c.refillTarget);
        o.addProperty("refillDeposit", c.refillDeposit);
        o.addProperty("armorPerPiece", c.armorPerPiece);
        o.add("armorAll", writeRule(c.armorAll));
        for (int i = 0; i < 4; i++) {
            o.add(RULE_KEYS[i], writeRule(c.armorRules[i]));
        }
        o.addProperty("noHitDelay", c.noHitDelay);
        o.add("allies", strings(c.allies));
        o.add("allyClans", strings(c.allyClans));
        o.add("enemies", strings(c.enemies));
        o.add("enemyClans", strings(c.enemyClans));
        o.add("remote", writeRemote(c));
        File dir = file.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        try (Writer w =
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
        } catch (Exception e) {
            LOG.warn("could not write " + file + ": " + e);
        }
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        for (String s : list) {
            a.add(new JsonPrimitive(s));
        }
        return a;
    }

    /** The file's "remote" block: the URL mode options, the URL and the last list of each one. */
    private static void readRemote(CombatConfig c, JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return;
        }
        JsonObject o = raw.getAsJsonObject();
        c.remote = bool(o, "enabled", c.remote);
        c.remoteUrl =
                o.has("url") && o.get("url").isJsonPrimitive()
                        ? cleanUrl(o.get("url").getAsString())
                        : "";
        if (o.has("feeds") && o.get("feeds").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("feeds").entrySet()) {
                Feed f = Feed.fromJson(e.getValue());
                if (f != null && !e.getKey().trim().isEmpty()) {
                    c.remoteFeeds.put(e.getKey().trim(), f);
                }
            }
        }
    }

    /**
     * The "remote" block back into the file. The lists of the URL in use are kept, plus a few of
     * the last downloaded ones: the Panel field saves on every key, and a URL being typed stops
     * being "the one in use" for a moment — keeping only the one in use, deleting a letter would
     * throw away the copy that makes the list work without internet.
     */
    private static JsonObject writeRemote(CombatConfig c) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", c.remote);
        o.addProperty("url", c.remoteUrl);
        List<Map.Entry<String, Feed>> rest = new ArrayList<>();
        JsonObject feeds = new JsonObject();
        for (Map.Entry<String, Feed> e : c.remoteFeeds.entrySet()) {
            if (e.getKey().equals(c.remoteUrl)) {
                feeds.add(e.getKey(), e.getValue().toJson());
            } else {
                rest.add(e);
            }
        }
        // The most recent first.
        rest.sort((a, b) -> Long.compare(b.getValue().at, a.getValue().at));
        for (int i = 0; i < rest.size() && i < SPARE_FEEDS; i++) {
            feeds.add(rest.get(i).getKey(), rest.get(i).getValue().toJson());
        }
        o.add("feeds", feeds);
        return o;
    }

    /**
     * The address as it is stored: only without the surrounding spaces. Whether it is http, https
     * or nothing at all is for the download to say — the field does not fix what the player typed.
     */
    static String cleanUrl(String url) {
        return url == null ? "" : url.trim();
    }

    /** Reads an Auto Armor rule (an object with mode, threshold, priority and the rest). */
    private static void readRule(JsonObject o, String key, AutoArmor.Rule r) {
        if (!o.has(key) || !o.get(key).isJsonObject()) {
            return;
        }
        JsonObject j = o.getAsJsonObject(key);
        if (j.has("mode")) {
            r.mode = clamp(j.get("mode").getAsInt(), AutoArmor.OFF, AutoArmor.SWAP);
        }
        if (j.has("threshold")) {
            r.threshold = clamp(j.get("threshold").getAsInt(), 1, 300);
        }
        r.highest = bool(j, "highest", r.highest);
        r.unenchanted = bool(j, "unenchanted", r.unenchanted);
        if (j.has("keepLevel")) {
            int k = j.get("keepLevel").getAsInt();
            r.keepLevel = k <= 0 ? 0 : clamp(k, 4, 10);
        }
        if (j.has("durability")) {
            r.durability =
                    clamp(j.get("durability").getAsInt(), AutoArmor.DUR_OFF, AutoArmor.DUR_LOWEST);
        }
    }

    private static JsonObject writeRule(AutoArmor.Rule r) {
        JsonObject j = new JsonObject();
        j.addProperty("mode", r.mode);
        j.addProperty("threshold", r.threshold);
        j.addProperty("highest", r.highest);
        j.addProperty("unenchanted", r.unenchanted);
        j.addProperty("durability", r.durability);
        j.addProperty("keepLevel", r.keepLevel);
        return j;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        return o.has(key) ? o.get(key).getAsBoolean() : def;
    }

    /** A color code from the file, if it is one of {@link CombatConfig#COLORS}. */
    private static char color(JsonObject o, String key, char def) {
        if (!o.has(key)) {
            return def;
        }
        String s = o.get(key).getAsString();
        return s.length() == 1 && CombatConfig.COLORS.indexOf(s.charAt(0)) >= 0 ? s.charAt(0) : def;
    }
}
