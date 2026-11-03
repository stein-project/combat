/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Server;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development checks, off unless asked for on the game's command line:
 *
 * <ul>
 *   <li>{@code -Dsteincombat.selfTestAtSec=N}: N seconds after the game opens, checks the marked
 *       enemies with real people from the Tab list — one without a clan, one from another clan and
 *       one from my clan —, through the same paths as the Panel, and puts the configuration back as
 *       it was. The result goes to the log, lines "Combat self-test";
 *   <li>{@code -Dsteincombat.dumpAtSec=N}: N seconds after starting, writes to the log what the
 *       server sends about each player of the Tab list — team name, prefix, suffix, ready Tab name
 *       — and what the clan reading gets from it. It is the only honest way to find out how a
 *       server marks clans: each one does it differently, and without seeing the raw text one only
 *       guesses.
 * </ul>
 */
final class Diagnostics {

    private static final long SELF_TEST_AT = Long.getLong("steincombat.selfTestAtSec", -1L);
    private static final long DUMP_AT = Long.getLong("steincombat.dumpAtSec", -1L);
    private static final String TAG = "Combat self-test: ";

    private final SteinCombat mod;
    private final long started = System.nanoTime();
    private boolean selfTested;
    private boolean dumped;

    /** The checks run and the failures. */
    private int checks;

    private int failures;

    Diagnostics(SteinCombat mod) {
        this.mod = mod;
    }

    /** At the start of every tick, before the keys. */
    void beforeKeys() {
        if (SELF_TEST_AT >= 0 && !selfTested && elapsed(SELF_TEST_AT)) {
            selfTest();
        }
    }

    /** At the end of every tick. */
    void afterTick() {
        if (DUMP_AT >= 0 && !dumped && elapsed(DUMP_AT)) {
            dumpClans();
        }
    }

    private boolean elapsed(long seconds) {
        return System.nanoTime() - started > seconds * 1_000_000_000L;
    }

    private void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            mod.log().info(TAG + "FAILED " + what);
        }
    }

    private void selfTest() {
        selfTested = true;
        if (!Server.isMultiplayer() || Players.self() == null) {
            mod.log().info(TAG + "no server");
            return;
        }
        CombatConfig config = mod.config;
        Teams teams = mod.teams;
        Marks marks = mod.marks;
        String me = Players.self();
        String mine = Clans.mine();
        String noClan = null;
        String other = null;
        String clanmate = null;
        for (String nick : Players.online()) {
            if (nick.equalsIgnoreCase(me) || config.hasAlly(nick) || config.hasEnemy(nick)) {
                continue;
            }
            String clan = Clans.of(nick);
            if (clan == null && noClan == null) {
                noClan = nick;
            } else if (clan != null
                    && !clan.equals(mine)
                    && !teams.isAlliedClan(clan)
                    && other == null) {
                other = nick;
            } else if (clan != null && clan.equals(mine) && clanmate == null) {
                clanmate = nick;
            }
        }
        List<String> savedAllies = new ArrayList<>(config.allies);
        List<String> savedEnemies = new ArrayList<>(config.enemies);
        List<String> savedAllyClans = new ArrayList<>(config.allyClans);
        List<String> savedEnemyClans = new ArrayList<>(config.enemyClans);
        boolean savedRemote = config.remote;
        String savedUrl = config.remoteUrl;
        Map<String, Feed> savedFeeds = new LinkedHashMap<>(config.remoteFeeds);
        checks = 0;
        failures = 0;
        try {
            // The first part is about the hand-written lists: URL mode goes off, otherwise nothing
            // it
            // marks applies and every check would rightly fail. The finally puts the mode back.
            config.remote = false;
            mod.apply();
            mod.log()
                    .info(
                            TAG
                                    + "my clan "
                                    + mine
                                    + "; no clan "
                                    + noClan
                                    + " ("
                                    + CombatApi.relation(noClan)
                                    + "), other clan "
                                    + other
                                    + " ("
                                    + CombatApi.relation(other)
                                    + "), my clan "
                                    + clanmate
                                    + " ("
                                    + CombatApi.relation(clanmate)
                                    + ")");
            for (String nick : new String[] {noClan, other, clanmate}) {
                if (nick != null) {
                    markedEnemy(nick);
                }
            }
            marks.pendingEnemy = me;
            marks.addEnemy(false);
            check(!config.hasEnemy(me), "could mark myself as an enemy");
            marks.pendingEnemy = "name with space";
            marks.addEnemy(false);
            check(!config.hasEnemy("name with space"), "accepted an invalid nick");
            marks.pendingEnemy = "";
            listsTest(other);
            guardTest(other, clanmate);
        } catch (Throwable t) {
            check(false, "stopped with " + t);
            mod.log().error(TAG + "stopped", t);
        } finally {
            config.allies.clear();
            config.allies.addAll(savedAllies);
            config.enemies.clear();
            config.enemies.addAll(savedEnemies);
            config.allyClans.clear();
            config.allyClans.addAll(savedAllyClans);
            config.enemyClans.clear();
            config.enemyClans.addAll(savedEnemyClans);
            config.remote = savedRemote;
            config.remoteUrl = savedUrl;
            config.remoteFeeds.clear();
            config.remoteFeeds.putAll(savedFeeds);
            mod.save();
        }
        mod.log().info(TAG + checks + " checks, " + failures + " failure(s)");
    }

    /**
     * Marks the nick as an enemy through the Panel's button, and checks everything that follows.
     */
    private void markedEnemy(String nick) {
        CombatConfig config = mod.config;
        Marks marks = mod.marks;
        int before = CombatApi.relation(nick);
        marks.pendingEnemy = nick;
        marks.addEnemy(false); // the Panel's button
        check(config.hasEnemy(nick) && marks.pendingEnemy.isEmpty(), nick + ": not on the list");
        check(
                CombatApi.relation(nick) == CombatApi.ENEMY,
                nick + ": relation " + CombatApi.relation(nick) + " after marked");
        check(
                !mod.teams.isAlly(nick),
                nick + ": marked enemy and still an ally (hit would be cancelled)");
        check(
                !config.protectAllies
                        || !mod.guard.onAttackEntity(Entities.player(nick))
                        || Entities.player(nick) == null,
                nick + ": hit cancelled on an enemy");
        check(
                CombatApi.code(nick) == config.enemyColor,
                nick
                        + ": color "
                        + CombatApi.code(nick)
                        + ", the enemy one is "
                        + config.enemyColor);
        // Ally removes from the enemies, and enemy removes from the allies.
        marks.pendingAlly = nick;
        marks.addAlly(false); // the allies' "Player" button
        check(
                config.hasAlly(nick) && !config.hasEnemy(nick),
                nick + ": ally did not remove from enemies");
        check(CombatApi.relation(nick) != CombatApi.ENEMY, nick + ": ally still an enemy");
        marks.pendingEnemy = nick;
        marks.addEnemy(false);
        check(
                config.hasEnemy(nick) && !config.hasAlly(nick),
                nick + ": enemy did not remove from allies");
        // Saved and read again, the list is still there.
        CombatConfig again = ConfigFile.load(configFile());
        check(again.hasEnemy(nick), nick + ": did not come back from the file");
        // Removing through the Panel's list brings the previous relation back.
        config.removeEnemy(nick);
        mod.save();
        check(
                CombatApi.relation(nick) == before,
                nick
                        + ": after removing, relation "
                        + CombatApi.relation(nick)
                        + ", was "
                        + before);
    }

    /**
     * The second part: enemy clans, URL mode (one source <b>or</b> the other) and the per-server
     * sections. All through the Panel's paths and without touching the internet — the sample list
     * is read from text, as if it had just been downloaded.
     */
    private void listsTest(String other) throws Exception {
        CombatConfig config = mod.config;
        Teams teams = mod.teams;
        String clan = other == null ? null : Clans.of(other);
        mod.log()
                .info(TAG + "current server: \"" + RemoteList.host() + "\", other's clan: " + clan);
        if (clan != null) {
            mod.marks.pendingEnemy = clan;
            mod.marks.addEnemy(true); // the enemies' "Add clan" button
            check(config.enemyClans.contains(clan), "clan " + clan + " not among the enemies");
            check(
                    teams.isEnemy(other) && CombatApi.relation(other) == CombatApi.ENEMY,
                    other + ": the marked clan did not make them an enemy");
            check(!teams.isAlly(other), other + ": from an enemy clan and still an ally");
            // The marked nick beats its clan's tag.
            config.addAlly(other);
            mod.save();
            check(
                    teams.isAlly(other) && !teams.isEnemy(other),
                    other + ": hand-marked ally lost to the enemy clan");
            config.removeAlly(other);
            config.addAllyClan(clan);
            check(!config.enemyClans.contains(clan), "the clan stayed on both lists");
            config.enemyClans.clear();
            config.allyClans.clear();
            mod.save();
        }
        Feed f =
                Feed.parse(
                        "{\"allies\":[\"Alfa\",\"invalid nick\"],\"allyClans\":[\"[abc]\"],"
                                + "\"enemies\":[\"Beta\"],\"enemyClans\":[\"xyz\",\"XYZ\"]}");
        check(f.allies.size() == 1 && f.allies.contains("Alfa"), "URL list: allies " + f.allies);
        check(
                f.allyClans.equals(Collections.singletonList("ABC")),
                "URL list: allied clans " + f.allyClans);
        check(
                f.enemies.equals(Collections.singletonList("Beta")),
                "URL list: enemies " + f.enemies);
        check(
                f.enemyClans.equals(Collections.singletonList("XYZ")),
                "URL list: enemy clans " + f.enemyClans);
        config.addAlly("Gama");
        config.remote = true;
        config.remoteUrl = "https://example.invalid/list.json";
        config.feed(config.remoteUrl, f);
        mod.save();
        check(teams.isAlly("Alfa"), "URL mode: the URL's ally did not apply");
        check(teams.isEnemy("Beta"), "URL mode: the URL's enemy did not apply");
        check(!teams.isAlly("Gama"), "URL mode: the hand-written ally still applied");
        check(mod.marks.locked(), "URL mode: the marking key is not locked");
        CombatConfig again = ConfigFile.load(configFile());
        check(
                again.remote && again.feed(config.remoteUrl).allies.contains("Alfa"),
                "URL mode: the stored list did not come back from the file");
        config.remote = false;
        mod.save();
        check(
                teams.isAlly("Gama") && !teams.isAlly("Alfa"),
                "manual mode: the hand-written list did not come back");
        config.removeAlly("Gama");
        // The per-server sections: the file itself says which server each list is for.
        String key = RemoteList.host();
        if (!key.isEmpty()) {
            serverSections(key);
        }
        mod.save();
    }

    private void serverSections(String key) throws Exception {
        CombatConfig config = mod.config;
        String domain = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        Feed byServer =
                Feed.parse(
                        "{\"allies\":[\"Alfa\"],\"enemies\":[\"Beta\"],\"servers\":{"
                                + "\""
                                + domain
                                + "\":{\"allies\":[\"Delta\"],\"enemies\":[\"Alfa\"],"
                                + "\"enemyClans\":[\"QWE\"]},"
                                + "\"other.example.com\":{\"allies\":[\"Epsilon\"]}}}");
        check(byServer.servers.size() == 2, "sections read: " + byServer.servers.keySet());
        Feed here = byServer.forHost(key);
        check(here.allies.contains("Delta"), "server section: its ally did not come in");
        check(
                !here.allies.contains("Alfa") && here.enemies.contains("Alfa"),
                "server section: its enemy did not beat the general ally");
        check(!here.allies.contains("Epsilon"), "another server's section's ally came in");
        check(here.enemyClans.contains("QWE"), "server section: its enemy clan did not come in");
        check(
                byServer.forHost("unrelated.example.org").allies.contains("Alfa"),
                "server without a section: the outer list did not apply alone");
        // Stored and read again, the sections are still there.
        config.feed(config.remoteUrl, byServer);
        mod.save();
        CombatConfig back = ConfigFile.load(configFile());
        check(
                back.feed(config.remoteUrl).forHost(key).allies.contains("Delta"),
                "server section: did not come back from the file");
    }

    /**
     * The third part: my clan is protected and hidden in each hiding style, with "My clan is
     * allied" as its condition; a marked ally outside my clan is never protected nor hidden, and at
     * most becomes a ghost that still takes hits.
     */
    private void guardTest(String other, String clanmate) {
        CombatConfig config = mod.config;
        Teams teams = mod.teams;
        ClanGuard guard = mod.guard;
        boolean savedHiding = guard.hiding;
        boolean savedProtect = config.protectAllies;
        int savedStyle = config.hideStyle;
        boolean savedGhostAllies = config.ghostAllies;
        boolean savedHideName = config.ghostHideName;
        boolean savedClanAllies = config.clanAllies;
        try {
            config.remote = false;
            config.protectAllies = true;
            config.clanAllies = true;
            guard.dryRun = true; // the self-test's hit is idle: no hitting whoever is behind
            guard.hiding = true;
            mod.save();
            if (other != null && !teams.isClanmate(other)) {
                Object e = Entities.player(other);
                config.addAlly(other);
                mod.save();
                check(teams.isAlly(other), other + ": did not become an ally");
                check(
                        CombatApi.code(other) == config.allyColor,
                        other + ": marked ally without the ally color");
                if (e == null) {
                    mod.log()
                            .info(TAG + other + " is not in the world: hit and screen not checked");
                } else {
                    allyChecks(other, e);
                }
                config.removeAlly(other);
                mod.save();
            }
            Object mate = clanmate == null ? null : Entities.player(clanmate);
            if (mate == null) {
                mod.log()
                        .info(
                                TAG
                                        + "nobody from my clan in the world: hiding and protecting not checked");
            } else {
                styleChecks(clanmate, mate);
                clanmateChecks(clanmate, mate);
            }
        } finally {
            guard.dryRun = false;
            guard.hiding = savedHiding;
            config.protectAllies = savedProtect;
            config.hideStyle = savedStyle;
            config.ghostAllies = savedGhostAllies;
            config.ghostHideName = savedHideName;
            config.clanAllies = savedClanAllies;
            mod.save();
        }
    }

    /**
     * A marked ally outside my clan (in the world): in every style, with the protection on, the hit
     * always lands and they never disappear; with "Ghost allies" they become a ghost, and only
     * that.
     */
    private void allyChecks(String nick, Object e) {
        CombatConfig config = mod.config;
        ClanGuard guard = mod.guard;
        config.protectAllies = true;
        for (int style = ClanGuard.DISAPPEAR; style <= ClanGuard.GONE; style++) {
            config.hideStyle = style;
            config.ghostAllies = true;
            check(
                    !guard.onAttackEntity(e),
                    nick + ": hit on an ally cancelled (style " + style + ")");
            check(!guard.onHideEntity(e), nick + ": ally disappeared (style " + style + ")");
            check(!guard.onInteractEntity(e), nick + ": right click let through an ally");
            check(guard.onGhostEntity(e), nick + ": ally did not become a ghost");
            config.ghostHideName = true;
            check(guard.ghostName(e) != null, nick + ": ally ghost name not hidden");
            config.ghostHideName = false;
            check(
                    guard.ghostName(e) == null,
                    nick + ": ally ghost name hidden with the option off");
            config.ghostAllies = false;
            check(!guard.onGhostEntity(e), nick + ": ally ghost with \"Ghost allies\" off");
            check(!guard.onAttackEntity(e), nick + ": hit on a solid ally cancelled");
        }
        guard.hiding = false;
        config.ghostAllies = true;
        check(!guard.onGhostEntity(e), nick + ": ally ghost with the hide key off");
        guard.hiding = true;
        config.hideStyle = ClanGuard.DISAPPEAR;
    }

    /** One clanmate (in the world) through the three styles, with the protection on and off. */
    private void styleChecks(String nick, Object e) {
        CombatConfig config = mod.config;
        ClanGuard guard = mod.guard;
        config.protectAllies = true;
        config.hideStyle = ClanGuard.DISAPPEAR;
        check(guard.onAttackEntity(e), nick + ": hit on my clan was not cancelled");
        check(guard.onHideEntity(e), nick + ": clanmate did not disappear from the screen");
        check(!guard.onGhostEntity(e), nick + ": became a ghost in the disappear style");
        check(!guard.onInteractEntity(e), nick + ": right click cancelled in the disappear style");
        config.protectAllies = false;
        check(!guard.onAttackEntity(e), nick + ": hit cancelled with the protection off");
        config.hideStyle = ClanGuard.GHOST;
        check(guard.onGhostEntity(e), nick + ": clanmate did not become a ghost");
        check(!guard.onHideEntity(e), nick + ": disappeared in the ghost style");
        check(guard.onAttackEntity(e), nick + ": hit did not go through the ghost");
        config.ghostHideName = false;
        check(guard.ghostName(e) == null, nick + ": ghost name hidden with the option off");
        config.ghostHideName = true;
        check(guard.ghostName(e) != null, nick + ": ghost name not hidden");
        config.hideStyle = ClanGuard.GONE;
        check(guard.onHideEntity(e), nick + ": clanmate still on screen in the gone style");
        check(guard.onAttackEntity(e), nick + ": hit did not go through a gone clanmate");
        check(guard.onInteractEntity(e), nick + ": right click not let through a gone clanmate");
        guard.hiding = false;
        check(!guard.onHideEntity(e), nick + ": hidden with the hide key off");
        check(!guard.onGhostEntity(e), nick + ": ghost with the hide key off");
        check(!guard.onAttackEntity(e), nick + ": hit cancelled with protection and hiding off");
        check(guard.ghostName(e) == null, nick + ": name hidden with the hide key off");
        guard.hiding = true;
        config.protectAllies = true;
        config.hideStyle = ClanGuard.DISAPPEAR;
    }

    private void clanmateChecks(String clanmate, Object mate) {
        CombatConfig config = mod.config;
        ClanGuard guard = mod.guard;
        // "My clan is allied" off: the clan is no longer allied, and both stop together.
        config.clanAllies = false;
        mod.save();
        check(!guard.onAttackEntity(mate), clanmate + ": protected with \"My clan is allied\" off");
        check(!guard.onHideEntity(mate), clanmate + ": disappeared with \"My clan is allied\" off");
        config.clanAllies = true;
        mod.save();
    }

    private File configFile() {
        return mod.configFile();
    }

    private void dumpClans() {
        dumped = true;
        if (!Server.isMultiplayer()) {
            mod.log().info("dump: no server");
            return;
        }
        mod.log()
                .info(
                        "dump: my clan = "
                                + Clans.mine()
                                + ", nick = "
                                + (Players.self() == null ? "?" : Players.self()));
        int i = 0;
        for (String nick : Players.online()) {
            if (i++ >= 20) {
                break;
            }
            String team = Players.team(nick);
            String shown = Players.displayName(nick);
            mod.log()
                    .info(
                            nick
                                    + " | team="
                                    + (team == null ? "-" : team)
                                    + " | prefix="
                                    + (team == null ? "-" : show(Players.prefix(nick)))
                                    + " | suffix="
                                    + (team == null ? "-" : show(Players.suffix(nick)))
                                    + " | tab="
                                    + (shown == null ? "-" : show(shown))
                                    + " | clan="
                                    + Clans.of(nick)
                                    + " | relation="
                                    + CombatApi.relation(nick));
        }
    }

    /** The text with the formatting codes visible: "§b[ABC] " becomes "&b[ABC] ". */
    private static String show(String s) {
        return s == null ? "-" : "\"" + s.replace('§', '&') + "\"";
    }
}
