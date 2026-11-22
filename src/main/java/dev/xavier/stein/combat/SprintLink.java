/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.Mods;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Combat's side of the Sprint mod, where toggle sprint moved: the W-tap after a hit asks Sprint for
 * the sprint reset ({@code dev.xavier.stein.sprint.api.Sprint.reset}).
 *
 * <p>Sprint is optional ("optional" in steinmod.json: when present it starts, and ticks, before
 * Combat). Its class is found by name in the shared class loader and the call goes through a method
 * handle, looked up once — so Combat builds without Sprint's jar, and nothing of Sprint is touched
 * when it is not there. It runs once per hit that lands, not per frame.
 */
final class SprintLink {

    private static final String SPRINT_ID = "steinsprint";
    private static final String API_CLASS = "dev.xavier.stein.sprint.api.Sprint";

    /** Combat's old toggle sprint key, as the game saved it in options.txt. */
    private static final String OLD_KEY_PREFIX = "key_key.steincombat.toggleSprint:";

    private static final Log LOG = Log.of(SteinCombat.MOD_ID);

    private static MethodHandle reset;
    private static boolean lookedUp;

    private SprintLink() {}

    /** The Sprint mod is running. */
    static boolean available() {
        return Mods.isLoaded(SPRINT_ID);
    }

    /**
     * The sprint reset: stop sprinting and let go of the sprint key until Sprint's next tick. Does
     * nothing without Sprint, or when its toggle sprint is not holding the key.
     */
    static void reset() {
        MethodHandle h = handle();
        if (h == null) {
            return;
        }
        try {
            boolean ignored = (boolean) h.invokeExact();
        } catch (Throwable t) {
            reset = null; // a Sprint that changed its API: no W-tap rather than a crash per hit
            LOG.warn("Sprint's sprint reset failed, W-tap off: " + t);
        }
    }

    private static MethodHandle handle() {
        if (!lookedUp && available()) {
            lookedUp = true;
            try {
                Class<?> api = Class.forName(API_CLASS, true, SprintLink.class.getClassLoader());
                reset =
                        MethodHandles.publicLookup()
                                .findStatic(api, "reset", MethodType.methodType(boolean.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("Sprint is loaded but its API was not found, W-tap off: " + e);
            }
        }
        return reset;
    }

    /**
     * This Combat no longer registers the old toggle sprint key, so the game drops its line from
     * options.txt on the next save of the options. Before that, the code goes into Combat's file
     * ({@link CombatConfig#legacySprintKey}), where Sprint's first start finds it if Sprint comes
     * later.
     *
     * @return true when the config changed and should be saved
     */
    static boolean rememberOldKey(File gameDir, CombatConfig config) {
        File options = new File(gameDir, "options.txt");
        if (!options.isFile()) {
            return false;
        }
        try {
            // ISO-8859-1 never fails on read; the key lines are ASCII.
            for (String line : Files.readAllLines(options.toPath(), StandardCharsets.ISO_8859_1)) {
                if (line.startsWith(OLD_KEY_PREFIX)) {
                    int code = Integer.parseInt(line.substring(OLD_KEY_PREFIX.length()).trim());
                    if (config.legacySprintKey == null || config.legacySprintKey != code) {
                        config.legacySprintKey = code;
                        return true;
                    }
                    return false;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // unreadable: nothing to remember
        }
        return false;
    }
}
