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
import dev.xavier.stein.loader.api.Targeting;

/**
 * What Combat does to my clan and to my allies on screen and in a fight. My clan ({@link
 * Teams#isClanmate}) is shielded: hits on it are cancelled before reaching the server (and can go
 * through to whoever is behind), and the hide key takes it off the screen in one of three styles
 * ({@link CombatConfig#hideStyle}). The other allies — marked nicks and allied clans — are never
 * shielded: they are always seen and always hit, and the hide key can at most turn them into ghosts
 * ({@link CombatConfig#ghostAllies}) that still take every hit.
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

    /** The empty name a ghost gets with "Hide ghost name tags" (built once the game is up). */
    private Object noName;

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
                        || hidden(entity) && mod.config.hideStyle != DISAPPEAR);
    }

    /** A hidden clanmate that hits go through: the ghost and gone styles. */
    private boolean passesThrough(Object entity, String nick) {
        return mod.config.hideStyle != DISAPPEAR && hidden(entity) && mod.teams.isClanmate(nick);
    }

    /** The hide key is on and it is another player (never me, in third person). */
    private boolean hidden(Object entity) {
        return hiding && entity != Entities.self();
    }

    /**
     * My clan disappears from the screen while the key is on, in the disappear and gone styles —
     * body, armor, name and shadow. It is not the game's invisibility: nothing is left, not even
     * the armor. They are still in the Tab list, on the minimap and in messages, in the clan color.
     * Allies outside my clan never disappear.
     */
    boolean onHideEntity(Object entity) {
        if (!hiding || mod.config.hideStyle == GHOST || !Entities.isPlayer(entity)) {
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
            return mod.config.hideStyle == GHOST;
        }
        return mod.config.ghostAllies && mod.teams.isAlly(nick);
    }

    /**
     * The name above a ghost's head, with "Hide ghost name tags": an empty name (the Loader keeps
     * the original for null), or null when the name stays.
     */
    Object ghostName(Object entity) {
        if (!mod.config.ghostHideName || !onGhostEntity(entity)) {
            return null;
        }
        if (noName == null) {
            noName = Chat.text("");
        }
        return noName;
    }
}
