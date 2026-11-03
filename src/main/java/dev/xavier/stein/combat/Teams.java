/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Relation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Who is what to the player: the four lists in effect now (allies, allied clans, enemies, enemy
 * clans), the clans, and the cached relation of each nick.
 *
 * <ul>
 *   <li>Marked as an enemy beats clan and alliance: out of the hit protection, in the enemy color,
 *       counted by "nearest enemy" and the HUD Tab list's enemy filter. Ally and enemy never at the
 *       same time: marking one removes it from the other list, and the mark on a nick beats the tag
 *       of its clan.
 *   <li>Allies: nicks and clans on a list, and the members of my clan (an option, on).
 * </ul>
 */
final class Teams {

    private final SteinCombat mod;

    /** Allies in lower case, for the per-frame lookups. */
    private Set<String> allies = new HashSet<>();

    /** Tags of the allied clans. */
    private Set<String> clans = new HashSet<>();

    /** Marked enemies in lower case. */
    private Set<String> enemies = new HashSet<>();

    /** Tags of the enemy clans. */
    private Set<String> enemyClans = new HashSet<>();

    /**
     * A nick's relation, its last painted nametag and the painted text of its last Tab name. The
     * relation comes only from the four lists (replaced whole in {@link #apply}), a few options, my
     * nick and the clans — and the clans are already cached in one-second rounds ({@link
     * Clans#epoch}). While none of that changes the answer is the same, and each player's nametag
     * asked every frame: the lists twice, three new lower-case strings and four lookups.
     */
    private static final class Rel {
        int relation;
        // The nametag: the team prefix and suffix it was built from (the game's name above the
        // head is prefix + nick + suffix) and the painted component, null when painting changes
        // nothing.
        boolean tagKnown;
        String tagPrefix;
        String tagSuffix;
        Object tagComponent;
        String listText;
        String listPainted;
    }

    private final Map<String, Rel> cache = new HashMap<>();

    // What was in effect when the cache was built: when any of it changes, the cache starts over.
    private long cacheEpoch;
    private String cacheSelf;
    private CombatConfig cacheConfig;
    private Set<String> cacheAllies;
    private Set<String> cacheClans;
    private Set<String> cacheEnemies;
    private Set<String> cacheEnemyClans;
    private boolean cacheClanAllies;
    private boolean cacheClanFromPrefix;
    private boolean cacheEnemyFromPrefix;
    // The colors go into the cached painted text.
    private boolean cacheColorNames;
    private long cacheColors;

    Teams(SteinCombat mod) {
        this.mod = mod;
    }

    /**
     * Rebuilds the four lists in effect. <b>One source or the other</b>: in URL mode the lists that
     * this server's URL delivered ({@code feed}), otherwise the ones written by hand (they stay in
     * the file and apply again when URL mode is turned off).
     */
    void apply(CombatConfig c, Feed feed) {
        allies = lower(feed == null ? c.allies : feed.allies);
        clans = new HashSet<>(feed == null ? c.allyClans : feed.allyClans);
        enemies = lower(feed == null ? c.enemies : feed.enemies);
        enemyClans = new HashSet<>(feed == null ? c.enemyClans : feed.enemyClans);
    }

    private static Set<String> lower(List<String> list) {
        Set<String> s = new HashSet<>();
        for (String a : list) {
            s.add(a.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    /** Is the clan on the allied clans list in effect? */
    boolean isAlliedClan(String tag) {
        return clans.contains(tag);
    }

    /**
     * An enemy by the lists: the marked nick, or someone in a clan marked as enemy. Beats clan and
     * alliances — even on a server where I have no clan.
     *
     * <p>The nick beats the tag: someone on the allies list stays an ally even with their clan
     * marked as enemy, because the mark on a nick is about that person and the tag is about a
     * group.
     */
    boolean isEnemy(String nick) {
        if (nick == null) {
            return false;
        }
        String low = nick.toLowerCase(Locale.ROOT);
        if (enemies.contains(low)) {
            return true;
        }
        if (enemyClans.isEmpty() || allies.contains(low)) {
            return false;
        }
        String tag = Clans.of(nick);
        return tag != null && enemyClans.contains(tag);
    }

    /**
     * An ally: on the nick list, in an allied clan or, with the option, in my clan — and not marked
     * as enemy.
     */
    boolean isAlly(String nick) {
        if (nick == null || isEnemy(nick)) {
            return false;
        }
        if (allies.contains(nick.toLowerCase(Locale.ROOT))) {
            return true;
        }
        CombatConfig c = mod.config;
        if (clans.isEmpty() && !c.clanAllies) {
            return false;
        }
        String tag = Clans.of(nick);
        if (tag == null) {
            return false;
        }
        if (clans.contains(tag)) {
            return true;
        }
        return c.clanAllies
                && Players.self() != null
                && !nick.equalsIgnoreCase(Players.self())
                && tag.equals(Clans.mine());
    }

    /**
     * In my clan, and only with "My clan is allied" on — which is what makes the clan count as
     * allied. It is what hiding and hit protection look at: marked allies and allied clans are left
     * out of both (they can only become ghosts, and they always take hits).
     *
     * <p>Marked as an enemy beats everything, here too: an enemy from my own clan is neither
     * protected nor hidden.
     */
    boolean isClanmate(String nick) {
        if (!mod.config.clanAllies || nick == null || isMe(nick) || isEnemy(nick)) {
            return false;
        }
        String mine = Clans.mine();
        return mine != null && mine.equals(Clans.of(nick));
    }

    /** The player is never on any list. */
    static boolean isMe(String nick) {
        return Players.self() != null && nick.equalsIgnoreCase(Players.self());
    }

    /**
     * The relation Combat gives the player, for the Loader ({@link
     * dev.xavier.stein.loader.api.Relations}). The usual order: marked as enemy beats everything,
     * then my clan, the allies and, with proof, another clan. null = no opinion: the Loader moves
     * on to other mods' registrations and, with nothing, neutral.
     */
    Relation relationOf(String nick, String group) {
        if (nick != null) {
            // The same answer as CombatApi.relation, cached; neutral here means "no opinion".
            return switch (relation(nick)) {
                case CombatApi.ENEMY -> Relation.ENEMY;
                case CombatApi.CLAN -> Relation.GROUP;
                case CombatApi.ALLY -> Relation.ALLY;
                default -> null;
            };
        }
        if (isEnemy(nick)) {
            return Relation.ENEMY;
        }
        Clans.Clan clan = Clans.info(nick);
        String mine = Clans.mine();
        if (mine != null && mine.equals(clan.tag)) {
            return Relation.GROUP;
        }
        if (isAlly(nick)) {
            return Relation.ALLY;
        }
        boolean proof = clan.proven || mod.config.enemyFromPrefix;
        return clan.tag != null && mine != null && proof ? Relation.ENEMY : null;
    }

    /** {@link CombatApi#relation}, cached (nick not null). */
    int relation(String nick) {
        return rel(nick).relation;
    }

    private Rel rel(String nick) {
        long epoch = Clans.epoch();
        String self = Players.self();
        CombatConfig c = mod.config;
        if (epoch != cacheEpoch
                || c != cacheConfig
                || allies != cacheAllies
                || clans != cacheClans
                || enemies != cacheEnemies
                || enemyClans != cacheEnemyClans
                || c.clanAllies != cacheClanAllies
                || c.clanFromPrefix != cacheClanFromPrefix
                || c.enemyFromPrefix != cacheEnemyFromPrefix
                || c.colorNames != cacheColorNames
                || colors(c) != cacheColors
                || !Objects.equals(self, cacheSelf)) {
            cache.clear();
            cacheEpoch = epoch;
            cacheSelf = self;
            cacheConfig = c;
            cacheAllies = allies;
            cacheClans = clans;
            cacheEnemies = enemies;
            cacheEnemyClans = enemyClans;
            cacheClanAllies = c.clanAllies;
            cacheClanFromPrefix = c.clanFromPrefix;
            cacheEnemyFromPrefix = c.enemyFromPrefix;
            cacheColorNames = c.colorNames;
            cacheColors = colors(c);
        }
        Rel r = cache.get(nick);
        if (r == null) {
            r = new Rel();
            r.relation = computeRelation(nick);
            cache.put(nick, r);
        }
        return r;
    }

    /** The four name colors in one number (each is a char). */
    private static long colors(CombatConfig c) {
        return (long) c.clanColor << 48
                | (long) c.allyColor << 32
                | (long) c.neutralColor << 16
                | c.enemyColor;
    }

    /** The relation computed again; see {@link CombatApi#relation}. */
    private int computeRelation(String nick) {
        // Marked as enemy beats everything: it is the player's choice about that nick, stronger
        // than clan and alliance.
        if (isEnemy(nick)) {
            return CombatApi.ENEMY;
        }
        Clans.Clan clan = Clans.info(nick);
        String mine = Clans.mine();
        if (mine != null && mine.equals(clan.tag)) {
            return CombatApi.CLAN;
        }
        if (isAlly(nick)) {
            return CombatApi.ALLY;
        }
        boolean proof = clan.proven || mod.config.enemyFromPrefix;
        return clan.tag != null && mine != null && proof ? CombatApi.ENEMY : CombatApi.NEUTRAL;
    }

    /**
     * The Tab name painted by relation. The same text as the last frame (same nick, same relation)
     * gives the same painted text, without building it again.
     */
    String paintListName(String nick, String name) {
        Rel r = rel(nick);
        if (r.listText == null || !(r.listText == name || r.listText.equals(name))) {
            r.listPainted = paint(name, nick);
            r.listText = name;
        }
        return r.listPainted;
    }

    /**
     * The nametag (a game text component) painted by relation, built once per nick and reused while
     * nothing it depends on changes.
     *
     * <p>The game builds the name above a player's head every frame as the team prefix, the nick
     * and the team suffix, so those three are the key: comparing the prefix and suffix the
     * scoreboard holds (the same strings until the server changes the team) costs no allocation,
     * where reading the component's text and painting it again built two strings and a new
     * component per player per frame. The relation and colors are cached alongside and start over
     * when they may have changed ({@link #rel}).
     *
     * @param nick the player's nick
     * @param name the name the game built (IChatComponent)
     * @return the painted component, or {@code name} when painting changes nothing
     */
    Object paintNametag(String nick, Object name) {
        if (nick == null) {
            return name;
        }
        Rel r = rel(nick);
        String prefix = Players.prefix(nick);
        String suffix = Players.suffix(nick);
        if (!r.tagKnown || r.tagPrefix != prefix || r.tagSuffix != suffix) {
            String text = Chat.formatted(name); // "" if it is not a game text
            String painted = paint(text, nick);
            r.tagComponent = painted.equals(text) ? null : Chat.text(painted);
            r.tagPrefix = prefix;
            r.tagSuffix = suffix;
            r.tagKnown = true;
        }
        return r.tagComponent == null ? name : r.tagComponent;
    }

    /**
     * The nick in the color of its relation to me, inside the game's text (the clan tag and
     * whatever else the server put around it stay as they are). My clan, ally, neutral and enemy
     * each have their own color, chosen in the Panel.
     */
    private static String paint(String text, String nick) {
        int i = text.indexOf(nick);
        if (i < 0) {
            return text;
        }
        return text.substring(0, i)
                + "§"
                + CombatApi.code(nick)
                + nick
                + "§r"
                + text.substring(i + nick.length());
    }
}
