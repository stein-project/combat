/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combat's options, in config/steincombat.json (read and written by {@link ConfigFile}, with the
 * same keys as always). A new instance holds the mod's defaults ({@link ConfigFile#DEFAULTS}).
 */
final class CombatConfig {

    /** The possible colors (the game's formatting codes), in the button's order. */
    static final String COLORS = "abed96fc7";

    static final int AUTOCLICK_MIN = 5;
    static final int AUTOCLICK_MAX = 100;

    /** Paint nicks by relation, in the Tab list, above heads and on the minimap. */
    boolean colorNames = true;

    /** My clan: green. */
    char clanColor = 'a';

    /** An ally outside my clan: blue. */
    char allyColor = '9';

    /** No clan and no alliance: white. */
    char neutralColor = 'f';

    /** Another clan that is not allied: red. */
    char enemyColor = 'c';

    /**
     * Toggle sprint as it was when it lived here (it moved to the Sprint mod). No longer used: kept
     * in the file so Sprint's first start brings the player's choice along.
     */
    boolean toggleSprint = true;

    /**
     * The key the player had chosen for toggle sprint here, copied from options.txt before the game
     * drops the line (this Combat no longer registers that key); null if never seen. Read by
     * Sprint's first start.
     */
    Integer legacySprintKey;

    /** A hit on my clan is cancelled. Allies outside my clan are never protected. */
    boolean protectAllies = true;

    /**
     * The hit cancelled on someone of my clan goes to the first one in the line of aim, behind them
     * and within reach, who is not in my clan (an ally outside it takes the hit). With nobody
     * there, it is only cancelled.
     */
    boolean clanHitThrough = true;

    /**
     * How the hide key hides my clan: {@link ClanGuard#DISAPPEAR}, {@link ClanGuard#GHOST} or
     * {@link ClanGuard#GONE}.
     */
    int hideStyle = ClanGuard.DISAPPEAR;

    /**
     * With the hide key on, an ally outside my clan becomes a ghost instead of staying solid. They
     * are still there for everything else — including hits.
     */
    boolean ghostAllies = true;

    /**
     * The old single switch for both groups: no name above the ghosts' heads. Kept in the file;
     * files without the two choices below start them from it.
     */
    boolean ghostHideName = false;

    /** What a ghost shows above its head: its name, only its clan's tag, or nothing. */
    static final int NAME_SHOWN = 0;

    static final int NAME_CLAN_TAG = 1;
    static final int NAME_HIDDEN = 2;

    /** Above my clan's ghosts (the ghost hide style): NAME_SHOWN, NAME_CLAN_TAG or NAME_HIDDEN. */
    int clanGhostName = NAME_SHOWN;

    /** Above the allies' ghosts ("Ghost allies"): NAME_SHOWN, NAME_CLAN_TAG or NAME_HIDDEN. */
    int allyGhostName = NAME_SHOWN;

    /**
     * Disappear and gone styles: a sign on the ground where each hidden ally is (footprints or a
     * square, {@link #markerStyle}), so that nobody walks into a player they cannot see.
     */
    boolean presenceMarker = true;

    /** {@link PresenceMarker#FOOTPRINTS} or {@link PresenceMarker#SQUARE}. */
    int markerStyle = PresenceMarker.FOOTPRINTS;

    /** The marker in the clan or ally color; off, black. */
    boolean markerColored = true;

    /** The members of my clan (the server's SteinCombat plugin) count as allies. */
    boolean clanAllies = true;

    /** Read the clan from the team prefix ("[ABC] Nick"), for servers with another clan plugin. */
    boolean clanFromPrefix = true;

    /**
     * Treat as an enemy whoever has brackets in the name different from mine. <b>Off</b>: on an
     * ordinary server "[VIP]", "[ADM]" and "[PRO]" have the same three letters as a clan tag, and
     * half the list became an enemy without being one. Turn it on only where the brackets really
     * are everyone's clan.
     */
    boolean enemyFromPrefix = false;

    /** The nick next to the distance, in the nearest enemy element. */
    boolean nearestName = true;

    /** The nick next to the distance, in the nearest ally element. */
    boolean nearestAllyName = true;

    /** Smart Effect on, and what it keeps up. */
    boolean smartEffect = false;

    boolean autoGapple = true;
    boolean autoSpeed = false;
    boolean autoStrength = false;

    /** Only with an enemy (neither ally nor in my clan) within effectEnemyRadius blocks. */
    boolean effectWaitEnemy = false;

    int effectEnemyRadius = 8;

    /** Also in the seconds after taking damage. With neither condition: always. */
    boolean effectOnDamage = false;

    /** Auto Refill: fetch armor from the server's virtual chest. */
    boolean autoRefill = false;

    String refillCommand = "/bau vip";
    boolean refillShowWindow = false;

    /**
     * AutoRefill.WHEN_EMPTY (fetches when a piece runs out of spares), CONSTANT (keeps refillTarget
     * per piece) or ON_KEY (one trip up to refillTarget when the key is pressed, then Auto Refill
     * turns itself off).
     */
    int refillMode = AutoRefill.WHEN_EMPTY;

    int refillTarget = 2;

    /** Put the worn armor from the bag into the chest. */
    boolean refillDeposit = false;

    /**
     * Constant mode: with no fitting piece in the chest and the player about to be without one,
     * bring the chest's worn piece with the most durability left (off: what is in the chest is
     * never used up).
     */
    boolean refillUseWorn = false;

    /** Auto Protect on: the sword blocking by itself with health low in a fight. */
    boolean autoProtect = false;

    /**
     * "Block in menus": with any window open (the inventory, a chest, the chat, the Esc menu, the
     * Panel), the sword blocking — whoever handles the inventory in the middle of a fight does not
     * stand there with the arm down.
     */
    boolean protectDefend = true;

    /**
     * "Low health block": with health at the limit in a fight, the sword blocking until health is
     * back. While blocking the hit does not go out (the game does not hit with an item in use) —
     * which is what saves from mcMMO's counterattack at close range.
     */
    boolean protectSurvive = true;

    /** Enters with health at this or below (health points: 2 = one heart). */
    int protectLowHealth = 6;

    /**
     * Leaves, and the potions come back, with health at this or above. Always at least one heart
     * above protectLowHealth.
     */
    int protectResumeHealth = 14;

    /**
     * Autoclick: with the left button held and no screen open, clicks at {@link #autoclickCps}, in
     * the mouse queue like real ones. Last in line: it waits for armor, refill, food and potions,
     * and blocking.
     */
    boolean autoclick = false;

    /** 0: while the left button is held; 1: always (constant), without holding anything. */
    int autoclickMode = 0;

    /** Autoclick's clicks per second. */
    int autoclickCps = 20;

    /** Auto Armor on. */
    boolean autoArmor = false;

    /**
     * One rule per piece ("Per piece" mode), in the game's order: boots, leggings, chestplate,
     * helmet.
     */
    final AutoArmor.Rule[] armorRules = {
        new AutoArmor.Rule(), new AutoArmor.Rule(), new AutoArmor.Rule(), new AutoArmor.Rule()
    };

    /** The single rule of simple mode, for all four pieces. */
    final AutoArmor.Rule armorAll = new AutoArmor.Rule();

    /** "Per piece" mode (false: simple, one rule for everything). */
    boolean armorPerPiece = false;

    /** The active feature list's color (the game's color code). */
    char featuresColor = 'f';

    /** The list ordered by width (the longest on top); off, in the Panel's order. */
    boolean featuresByWidth = true;

    /** The list's colors: 0 by category, 1 rainbow, 2 the fixed color ({@link #featuresColor}). */
    int featuresStyle = 0;

    /** A dark background behind each line of the list. */
    boolean featuresBackground = true;

    /** Features turned on that do not show in the list (the ids of {@link Features}). */
    final List<String> featuresHidden = new ArrayList<>();

    /** "No Hit Delay": without 1.8's penalty for a missed hit, half a second without hits. */
    boolean noHitDelay = true;

    /** The allies' nicks, as written. */
    final List<String> allies = new ArrayList<>();

    /** Allied clans, by their upper-case tag: everyone with the tag counts as an ally. */
    final List<String> allyClans = new ArrayList<>();

    /**
     * Nicks marked as enemies, as written. They beat everything: a marked enemy is an enemy even
     * with no clan, in an allied clan or in my clan. A nick is never on both lists.
     */
    final List<String> enemies = new ArrayList<>();

    /**
     * Enemy clans, by their upper-case tag: everyone with the tag counts as an enemy, even on a
     * server where I have no clan. A nick marked by hand beats its tag — the mark on a nick is
     * about that person.
     */
    final List<String> enemyClans = new ArrayList<>();

    /**
     * URL mode: the four lists come from a JSON file on the internet ({@link Feed}) and <b>the ones
     * written by hand are frozen</b> — it is always one or the other. Turning it off brings the
     * hand lists back as they were: they stay in the file.
     */
    boolean remote = false;

    /**
     * The JSON address — <b>a single one</b>, for every server: the file itself says which server
     * each list is for, in its "servers" section (see {@link Feed}).
     */
    String remoteUrl = "";

    /**
     * The last list each URL delivered, by the URL itself. It is what applies before the fetch
     * answers, and what keeps applying with no internet or with the file offline.
     */
    final Map<String, Feed> remoteFeeds = new LinkedHashMap<>();

    // ---- Treasure List and the item modules ----

    /** What is valuable: read by Item Lock, Auto Stash, Auto Armor and Loot Radar. */
    final List<Valuables.Rule> valuables = Valuables.defaultList();

    /** Item Lock: the drop key never drops a locked item. */
    boolean itemLock = false;

    /** Item Lock for every item, not only the valuable ones. */
    boolean itemLockAll = false;

    /** Auto Trash: throws away the items on {@link #trashItems}. */
    boolean autoTrash = false;

    /** The trash: item ids ("minecraft:dirt"). */
    final List<String> trashItems = new java.util.ArrayList<>(AutoTrash.DEFAULTS);

    /** Auto Trash also empties the hotbar (never the slot in hand). */
    boolean trashHotbar = false;

    /** Auto Stash on (by itself, with health low); its key makes a trip either way. */
    boolean autoStash = false;

    /** Health (half hearts) at or below which the trip goes by itself. */
    int stashHealth = 6;

    String stashCommand = "/bau vip";
    boolean stashShowWindow = false;

    /** Valuables on the hotbar go too (the item in the hand stays with {@link #stashKeepHeld}). */
    boolean stashHotbar = true;

    boolean stashKeepHeld = true;

    /** Enchanted golden apples that stay, at least. */
    int stashKeepApples = 2;

    /** One spare of each armor piece stays. */
    boolean stashKeepSet = true;

    /** The valuable worn armor goes too (it comes off first). */
    boolean stashWorn = false;

    /** Loot Radar: valuable items on the ground stand out (each rule's style is in the rule). */
    boolean lootRadar = false;

    int radarDistance = 32;
    boolean radarArrows = true;
    boolean radarSound = true;

    /**
     * Supply Tracker (the "supplyForecast" key from when it had another name): how long the rush
     * lasts, on the HUD grid. A rush starts by itself in a fight.
     */
    boolean supplyForecast = false;

    boolean forecastWorn = true;
    boolean forecastInventory = true;
    boolean forecastChest = true;

    /** Supply Tracker's armor: the whole set at once (one line), or piece by piece. */
    boolean forecastArmorSet = true;

    /** The fields' values, with the package defaults ({@link ConfigFile#DEFAULTS}) on top. */
    CombatConfig() {
        ConfigFile.readDefaults(this);
    }

    /**
     * "Low health block" always leaves at least one heart above where it enters (or it would do
     * both at once).
     */
    void fixProtectHealth() {
        if (protectResumeHealth < protectLowHealth + 2) {
            protectResumeHealth = Math.min(20, protectLowHealth + 2);
        }
    }

    /** The rule in effect for the piece now. */
    AutoArmor.Rule armorRule(int piece) {
        return armorPerPiece ? armorRules[piece] : armorAll;
    }

    /** Sets the address as it is stored: only without the surrounding spaces. */
    void url(String url) {
        remoteUrl = ConfigFile.cleanUrl(url);
    }

    /** The last list of that URL, or an empty one. */
    Feed feed(String url) {
        Feed f = url == null || url.isEmpty() ? null : remoteFeeds.get(url);
        return f == null ? Feed.EMPTY : f;
    }

    /** Keeps what the URL just delivered. */
    void feed(String url, Feed f) {
        if (url != null && !url.isEmpty() && f != null) {
            remoteFeeds.put(url, f);
        }
    }

    /**
     * Marks the clan as allied, removing it from the enemies: the two clan lists do not overlap
     * either.
     */
    void addAllyClan(String tag) {
        enemyClans.remove(tag);
        if (!allyClans.contains(tag)) {
            allyClans.add(tag);
        }
    }

    /** Marks the clan as enemy, removing it from the allies. */
    void addEnemyClan(String tag) {
        allyClans.remove(tag);
        if (!enemyClans.contains(tag)) {
            enemyClans.add(tag);
        }
    }

    boolean hasAlly(String nick) {
        for (String a : allies) {
            if (a.equalsIgnoreCase(nick)) {
                return true;
            }
        }
        return false;
    }

    void removeAlly(String nick) {
        allies.removeIf(a -> a.equalsIgnoreCase(nick));
    }

    boolean hasEnemy(String nick) {
        for (String e : enemies) {
            if (e.equalsIgnoreCase(nick)) {
                return true;
            }
        }
        return false;
    }

    void removeEnemy(String nick) {
        enemies.removeIf(e -> e.equalsIgnoreCase(nick));
    }

    /** Marks as ally, removing from the enemies: the two lists do not overlap. */
    void addAlly(String nick) {
        removeEnemy(nick);
        if (!hasAlly(nick)) {
            allies.add(nick);
        }
    }

    /** Marks as enemy, removing from the allies. */
    void addEnemy(String nick) {
        removeAlly(nick);
        if (!hasEnemy(nick)) {
            enemies.add(nick);
        }
    }
}
