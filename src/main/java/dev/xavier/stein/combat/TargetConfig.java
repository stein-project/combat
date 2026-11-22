/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

/**
 * Target: head, name, health, armor and held item of whoever you last hit, or who is in your
 * crosshair. {@code config/steincombat-target.json}; the field names are the JSON keys the block
 * had in the HUD's file, which is where a player's choices come from the first time ({@link
 * TargetConfigFile}): do not rename them.
 */
public final class TargetConfig {
    /** Without a recent hit, shows who is in the crosshair. */
    public boolean aim = true;

    /** Blocks: the crosshair's reach; farther than this the target leaves. */
    public float range = 8.0F;

    /** Seconds on screen after the last hit. */
    public float seconds = 5.0F;

    /**
     * Seconds the current target holds the place: a hit on another player before that does not
     * switch.
     */
    public float switchSeconds = 1.0F;

    public boolean head = true;
    public boolean name = true;
    public boolean distance = false;

    /** Health bar (with the lost part fading slowly). */
    public boolean healthBar = true;

    public boolean healthText = false;
    public boolean armor = true;
    public boolean held = true;

    /** Durability bar over the icon, like in the inventory. */
    public boolean durabilityBar = true;

    /** value, percent or none: the durability under each piece. */
    public String durability = "value";

    /** Entering, leaving and health changes animated. */
    public boolean animation = true;

    /**
     * A small clock with how long ago the server last changed the health and the items (from 2
     * seconds on).
     */
    public boolean updateAge = true;

    // Place and enabled belong to the Loader's grid: these are the factory ones. Beside the
    // crosshair, a little below: the fight happens there, and the eye need not leave the target.

    public boolean enabled = true;
    public int anchorX = 1;
    public int anchorY = 1;
    public int x = 70;
    public int y = 30;
    public float scale = 1.0F;
    public boolean showInChat = false;
}
