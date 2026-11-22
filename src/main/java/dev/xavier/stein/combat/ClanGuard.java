/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Chat;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.Targeting;

/**
 * What Combat does to my clan and to my allies on screen and in a fight. My clan ({@link
 * Teams#isClanmate}) is shielded: hits on it are cancelled before reaching the server (and can go
 * through to whoever is behind), and the hide key takes it off the screen in one of three styles
 * ({@link CombatConfig#hideStyle}). The other allies — marked nicks and allied clans — are never
 * shielded: they are always seen and always hit, and the hide key can at most turn them into ghosts
 * ({@link CombatConfig#ghostAllies}) that still take every hit.
 *
 * <p>The hide key is the Fight View: what it does to each group is chosen apart — my clan stays
 * visible ({@link CombatConfig#clanInView}) or hides in its style, allies become ghosts or not, and
 * everyone else wearing all their armor (whoever Armor Gap does not mark) becomes a ghost with
 * {@link CombatConfig#ghostArmored}. My clan, gone, my allies as ghosts and the fully armored as
 * ghosts is the "focus" view: only the players missing armor stay solid. Nobody but my clan is ever
 * shielded: every ghost still takes every hit.
 */
final class ClanGuard {

    /** My clan disappears from the screen; the aim still finds them (and Clan Protect decides). */
    static final int DISAPPEAR = 0;

    /** My clan stays as a faint silhouette, and hits go through them as if they were not there. */
    static final int GHOST = 1;

    /**
     * As if my clan did not exist: nothing on screen, and hits and right clicks go through them to
     * whatever is behind.
     */
    static final int GONE = 2;

    /** {@code -Dsteincombat.log=true}: every cancelled hit in the log. */
    private static final boolean LOG_HITS = Boolean.getBoolean("steincombat.log");

    private final SteinCombat mod;

    /**
     * The hide key is on. Not saved: it starts off every session — opening the game without the
     * clan on screen, without remembering why, would be worse than pressing the key again.
     */
    boolean hiding;

    /** Sending the hit that goes through a clanmate (see {@link #behind}). */
    private boolean hittingThrough;

    /** The self-test checks the decisions without sending a hit to whoever is behind. */
    boolean dryRun;

    /** The empty name of a hidden ghost name: the Bridge then draws no label at all. */
    private Object noName;

    /** The clan tags shown in place of ghosts' names, by nick: {tag, color code, component}. */
    private final java.util.Map<String, Object[]> tagNames = new java.util.HashMap<>();

    ClanGuard(SteinCombat mod) {
        this.mod = mod;
    }

    /**
     * The hit cancelled before it reaches the server: on a clanmate with Clan Protect on, and
     * always on a clanmate hidden as a ghost or gone — those two are not there for hits, so the hit
     * goes to whoever is behind them even without the protection. <b>Never on an ally outside my
     * clan</b>, ghost or not: the hit always lands on them.
     */
    boolean onAttackEntity(Object target) {
        if (!Entities.isPlayer(target)) {
            return false;
        }
        String nick = Entities.name(target);
        boolean passThrough = passesThrough(target, nick);
        if (!passThrough && !(mod.config.protectAllies && mod.teams.isClanmate(nick))) {
            return false;
        }
        // No action bar notice: whoever turned on "do not hit my clan" knows what they asked for,
        // and text flashing in the middle of a fight gets in the way more than it informs (and it
        // covers what the server writes there).
        boolean through = passThrough || mod.config.clanHitThrough;
        Object behind = through && !hittingThrough && !dryRun ? behind(target) : null;
        if (behind != null) {
            // The hit goes through: to whoever is behind, the same way as the click (the arm
            // already swung). Targeting.attack does not come back here; the flag only guarantees
            // it never loops.
            hittingThrough = true;
            try {
                Targeting.attack(behind);
            } finally {
                hittingThrough = false;
            }
        }
        if (LOG_HITS) {
            mod.log()
                    .info(
                            "hit on my clan cancelled ("
                                    + nick
                                    + ")"
                                    + (behind == null
                                            ? ""
                                            : ", went through to " + Entities.name(behind)));
        }
        return true;
    }

    /**
     * A right click on a gone clanmate carries on as if nobody were there: the held item is used.
     * The other styles leave it alone (a right click does not hurt anyone).
     */
    boolean onInteractEntity(Object target) {
        return mod.config.hideStyle == GONE
                && Entities.isPlayer(target)
                && passesThrough(target, Entities.name(target));
    }

    /**
     * The hit that goes through the clanmate: in the line of aim, within the game's reach and
     * before the first block, the nearest one that is not me, not a shielded clanmate and alive —
     * or null. An ally outside my clan in that line takes the hit like anyone. The game's own
     * aiming math (each entity's box with the collision border), only without stopping at the
     * first: the clanmate in front is left out.
     */
    private Object behind(Object clanmate) {
        Targeting.Ray ray =
                Targeting.raycast(
                        Targeting.query()
                                .living()
                                .cutAtBlock()
                                .exclude(clanmate)
                                .exclude(Entities.self()));
        for (Targeting.Hit hit : ray.hits) {
            Object e = hit.entity;
            if (hit.distance >= ray.blockDistance) {
                break;
            }
            if (Entities.isPlayer(e) && shielded(e, Entities.name(e))) {
                continue; // another clanmate in line: the hit goes through them too
            }
            return e;
        }
        return null;
    }

    /** Hits never land on them: a clanmate protected, or hidden as a ghost or gone. */
    private boolean shielded(Object entity, String nick) {
        return mod.teams.isClanmate(nick)
                && (mod.config.protectAllies
                        || clanHidden(entity) && mod.config.hideStyle != DISAPPEAR);
    }

    /** A hidden clanmate that hits go through: the ghost and gone styles. */
    private boolean passesThrough(Object entity, String nick) {
        return mod.config.hideStyle != DISAPPEAR
                && clanHidden(entity)
                && mod.teams.isClanmate(nick);
    }

    /**
     * The Fight View is on, it hides my clan (not left visible) and it is another player (never me,
     * in third person).
     */
    private boolean clanHidden(Object entity) {
        return hiding && !mod.config.clanInView && entity != Entities.self();
    }

    /**
     * My clan disappears from the screen while the key is on, in the disappear and gone styles —
     * body, armor, name and shadow. It is not the game's invisibility: nothing is left, not even
     * the armor. They are still in the Tab list, on the minimap and in messages, in the clan color.
     * Allies outside my clan never disappear.
     */
    boolean onHideEntity(Object entity) {
        if (!hiding
                || mod.config.clanInView
                || mod.config.hideStyle == GHOST
                || !Entities.isPlayer(entity)) {
            return false;
        }
        return entity != Entities.self() && mod.teams.isClanmate(Entities.name(entity));
    }

    /**
     * With the hide key on, a ghost: the faint silhouette the game already draws for invisible
     * teammates, without armor and without the held item, so whoever is fighting behind can be
     * seen. My clan becomes one in the ghost style (and hits go through it, {@link
     * #onAttackEntity}); an ally outside my clan becomes one with "Ghost allies", in any style, and
     * still takes every hit.
     */
    boolean onGhostEntity(Object entity) {
        if (!hiding || !Entities.isPlayer(entity) || entity == Entities.self()) {
            return false;
        }
        String nick = Entities.name(entity);
        if (mod.teams.isClanmate(nick)) {
            return !mod.config.clanInView && mod.config.hideStyle == GHOST;
        }
        if (mod.teams.isAlly(nick)) {
            return mod.config.ghostAllies;
        }
        return armoredGhost(entity);
    }

    /**
     * Anyone else (enemy or neutral) wearing all their armor — whoever Armor Gap does not mark:
     * missing no piece, protected enough, or out of its range — as a ghost, with "Fully armored"
     * on. Where the server does not allow Armor Gap, nobody: it would tell the same thing.
     */
    private boolean armoredGhost(Object entity) {
        return mod.config.ghostArmored
                && Server.allows("armorGap")
                && !mod.armorGap.isMarked(entity);
    }

    /**
     * The name above a ghost's head, each group with its own choice ({@link
     * CombatConfig#clanGhostName} for my clan, {@link CombatConfig#allyGhostName} for the allies):
     * the name as it is (null: the Loader keeps it), only the clan's tag in the relation's color,
     * or nothing — an empty name, which the Bridge draws no label for. Someone with no clan shows
     * nothing with "only the clan tag".
     */
    Object ghostName(Object entity) {
        if (!onGhostEntity(entity)) {
            return null;
        }
        String nick = Entities.name(entity);
        boolean clan = mod.teams.isClanmate(nick);
        if (!clan && !mod.teams.isAlly(nick)) {
            return null; // a fully armored enemy or neutral keeps their name
        }
        int mode = clan ? mod.config.clanGhostName : mod.config.allyGhostName;
        if (mode == CombatConfig.NAME_SHOWN) {
            return null;
        }
        if (mode == CombatConfig.NAME_CLAN_TAG) {
            Object tag = clanTag(nick);
            if (tag != null) {
                return tag;
            }
        }
        if (noName == null) {
            noName = Chat.text("");
        }
        return noName;
    }

    /** "[ABC]" in the color of the player's relation to me, kept until either changes. */
    private Object clanTag(String nick) {
        String tag = Clans.of(nick);
        if (tag == null) {
            return null;
        }
        char code = CombatApi.code(nick);
        Object[] cached = tagNames.get(nick);
        if (cached == null || !tag.equals(cached[0]) || (char) cached[1] != code) {
            cached = new Object[] {tag, code, Chat.text("\u00a7" + code + "[" + tag + "]")};
            tagNames.put(nick, cached);
        }
        return cached[2];
    }
}
