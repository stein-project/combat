/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Keys;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Screens;

/**
 * Auto Walk: its key starts walking forward without holding the game's forward key — the mod keeps
 * it pressed — and pressing it again stops. With the Sprint mod's toggle sprint on, the walk is a
 * sprint (Sprint holds the sprint key). Pressing forward or back by hand also stops it: the player
 * takes the keys back. It pauses in menus (nobody walks off a cliff with the inventory open) and
 * turns off outside a world.
 */
final class AutoWalk {

    /**
     * Walking by itself. Not saved: like the hide key, it starts off every session, so the game
     * never opens with the player walking.
     */
    boolean on;

    /** The mod is holding the game's forward key. */
    private boolean holding;

    /** Forward or back was physically down last tick: only a new press stops the walk. */
    private boolean wasDown;

    /** The key turns it on and off. */
    void toggle() {
        on = !on;
    }

    /** Every tick; not allowed, it stays off on this server. */
    void tick(boolean allowed) {
        boolean down = pressedByHand();
        if (!allowed || Players.self() == null || down && !wasDown) {
            on = false;
        }
        wasDown = down;
        boolean want = on && Players.self() != null && !Screens.isOpen();
        if (want) {
            Keys.game(Keys.GameKey.FORWARD).hold(true);
            holding = true;
        } else if (holding) {
            holding = false;
            Keys.game(Keys.GameKey.FORWARD).hold(false);
        }
    }

    /**
     * Forward or back is down, with no menu open. Forward is read on the keyboard (or mouse)
     * itself, since the game's key is the hold of this class; back is the game's key. A key already
     * held when the walk starts does not stop it: only pressing again does.
     */
    private static boolean pressedByHand() {
        if (Screens.isOpen()) {
            return false;
        }
        return physical(Keys.game(Keys.GameKey.FORWARD).key())
                || Keys.game(Keys.GameKey.BACK).isDown();
    }

    private static boolean physical(int key) {
        return key != Keys.KEY_NONE && Keys.isDown(key);
    }
}
