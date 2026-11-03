/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.WorldRender;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The presence marker: with the allies hidden (disappear and gone styles), a sign on the ground of
 * where each one is, in the clan or ally color (or black, {@link CombatConfig#markerColored}), with
 * nothing floating over the fight. Two styles ({@link CombatConfig#markerStyle}):
 *
 * <ul>
 *   <li>{@link #FOOTPRINTS}: square prints where each one walks, fading out in under two seconds —
 *       where they are and where they are going. An ally standing still keeps their last two
 *       prints, faint, while they are there: someone who does not move is exactly who gets walked
 *       into.
 *   <li>{@link #SQUARE}: a flat square under the feet, the size of the player's box, following them
 *       frame by frame.
 * </ul>
 *
 * <p>Prints are laid on the tick (one per stride, left and right in turn, only on the ground) and
 * drawn every frame from a fixed ring, without allocating.
 */
final class PresenceMarker {

    static final int FOOTPRINTS = 0;
    static final int SQUARE = 1;

    /** Half the square's side: the player's box is 0.6 blocks wide. */
    private static final double SQUARE_HALF = 0.3;

    /** The square's border width. */
    private static final double BORDER = 0.035;

    /** The square's border and fill opacity. */
    private static final int BORDER_ALPHA = 0xC0 << 24;

    private static final int FILL_ALPHA = 0x30 << 24;

    /** How many prints exist at once, for everyone; the oldest is overwritten. */
    private static final int CAPACITY = 256;

    /** Ground covered between two prints, in blocks. */
    private static final double STRIDE = 0.8;

    /** How far each print sits from the middle of the walk, to the side: where each leg is. */
    private static final double SIDE = 0.125;

    /** Half a print's side: the leg is 4 skin pixels, a quarter of a block, and square. */
    private static final double HALF = 0.125;

    /** Seconds until a print is gone. */
    private static final double LIFE = 1.6;

    /** A fresh print's opacity. */
    private static final double ALPHA = 0.55;

    /** The floor of the last two prints of an ally who is still there. */
    private static final double STILL_ALPHA = 0.22;

    /** Past this distance from the camera, no prints are drawn. */
    private static final double MAX_DISTANCE = 48;

    /** One ally's walk: where the last print went and which foot is next. */
    private static final class Track {
        double x;
        double z;
        double lastY;
        boolean left;
        boolean started;

        /** The ring index of the last two prints (-1 = none). */
        int last = -1;

        int beforeLast = -1;

        /** The tick it was last seen hidden. */
        long seen;

        /** Still in {@link #tracks}: the ally is hidden and in the world. */
        boolean present;

        /** The ally, and their color (clan or ally). */
        Object entity;

        int color;
    }

    private final SteinCombat mod;

    private final Map<Object, Track> tracks = new IdentityHashMap<>();

    /** The hidden allies of the last tick, for the square (no iterator per frame). */
    private Track[] shown = new Track[16];

    private int shownCount;

    // The ring of prints.
    private final double[] px = new double[CAPACITY];
    private final double[] py = new double[CAPACITY];
    private final double[] pz = new double[CAPACITY];
    private final float[] yaw = new float[CAPACITY];
    private final long[] born = new long[CAPACITY];
    private final int[] rgb = new int[CAPACITY];
    private final Track[] owner = new Track[CAPACITY];
    private int next;

    private long tick;

    // The two colors, cached by their code: Option.rgb takes a String.
    private char clanCode;
    private int clanRgb;
    private char allyCode;
    private int allyRgb;

    PresenceMarker(SteinCombat mod) {
        this.mod = mod;
    }

    private boolean on() {
        CombatConfig c = mod.config;
        return mod.guard.hiding && c.presenceMarker && c.hideStyle != ClanGuard.GHOST;
    }

    /** Every tick: a new print for each hidden ally who covered a stride on the ground. */
    void tick() {
        tick++;
        Object self = Entities.self();
        if (!on() || self == null) {
            if (!tracks.isEmpty()) {
                clear();
            }
            return;
        }
        colors(mod.config);
        boolean prints = mod.config.markerStyle == FOOTPRINTS;
        shownCount = 0;
        List<Object> players = Entities.players();
        for (int i = 0, n = players.size(); i < n; i++) {
            Object p = players.get(i);
            if (p == self || !Entities.isAlive(p)) {
                continue;
            }
            String nick = Entities.name(p);
            if (nick == null || !mod.teams.isClanmate(nick)) {
                continue;
            }
            Track t = tracks.get(p);
            if (t == null) {
                t = new Track();
                t.present = true;
                tracks.put(p, t);
            }
            t.seen = tick;
            t.entity = p;
            t.color = mod.teams.relation(nick) == CombatApi.CLAN ? clanRgb : allyRgb;
            if (shownCount == shown.length) {
                shown = Arrays.copyOf(shown, shownCount * 2);
            }
            shown[shownCount++] = t;
            if (prints) {
                step(t, p, t.color);
            }
        }
        // Whoever left the world (or stopped being an ally) takes their track along.
        for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); ) {
            Track t = it.next();
            if (t.seen != tick) {
                t.present = false;
                it.remove();
            }
        }
    }

    private void step(Track t, Object player, int color) {
        double x = Entities.x(player, 1);
        double y = Entities.y(player, 1);
        double z = Entities.z(player, 1);
        // The same height as last tick: on the ground (a jump or a fall leaves no print).
        boolean ground = Math.abs(y - t.lastY) < 1e-3;
        t.lastY = y;
        if (!t.started) {
            t.started = true;
            t.x = x;
            t.z = z;
            return;
        }
        double dx = x - t.x;
        double dz = z - t.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < STRIDE) {
            return;
        }
        t.x = x;
        t.z = z;
        if (!ground || d > STRIDE * 6) {
            return; // in the air, or a teleport: no print across it
        }
        // Each foot to its own side of the walk.
        double side = t.left ? SIDE : -SIDE;
        t.left = !t.left;
        int i = next;
        next = (next + 1) % CAPACITY;
        px[i] = x + dz / d * side;
        py[i] = y;
        pz[i] = z - dx / d * side;
        yaw[i] = (float) Math.toDegrees(Math.atan2(dx, dz));
        born[i] = System.nanoTime();
        rgb[i] = color;
        owner[i] = t;
        t.beforeLast = t.last;
        t.last = i;
    }

    /** Every frame, in onRenderWorld. */
    void frame(float partialTicks) {
        if (tracks.isEmpty() || !on()) {
            return;
        }
        if (mod.config.markerStyle == SQUARE) {
            squares(partialTicks);
            return;
        }
        long now = System.nanoTime();
        for (int i = 0; i < CAPACITY; i++) {
            Track t = owner[i];
            if (t == null) {
                continue;
            }
            double alpha = ALPHA * (1 - (now - born[i]) / 1e9 / LIFE);
            if (t.present && (t.last == i || t.beforeLast == i)) {
                alpha = Math.max(alpha, STILL_ALPHA);
            }
            if (alpha <= 0.01) {
                owner[i] = null;
                continue;
            }
            if (WorldRender.distance(px[i], py[i], pz[i]) <= MAX_DISTANCE) {
                int color = mod.config.markerColored ? rgb[i] : 0;
                draw(i, (int) (alpha * 255) << 24 | color);
            }
        }
    }

    /**
     * A print: the square sole of a Minecraft leg, flat just above the ground and turned along the
     * walk.
     */
    private void draw(int i, int argb) {
        WorldRender.push(px[i], py[i] + 0.015, pz[i]);
        try {
            WorldRender.rotate(yaw[i], 0, 1, 0);
            WorldRender.quad(-HALF, 0, -HALF, -HALF, 0, HALF, HALF, 0, HALF, HALF, 0, -HALF, argb);
        } finally {
            WorldRender.pop();
        }
    }

    /** Each hidden ally's square, under the feet where the frame draws them. */
    private void squares(float partialTicks) {
        boolean colored = mod.config.markerColored;
        for (int k = 0; k < shownCount; k++) {
            Track t = shown[k];
            double x = Entities.x(t.entity, partialTicks);
            double y = Entities.y(t.entity, partialTicks) + 0.015;
            double z = Entities.z(t.entity, partialTicks);
            if (WorldRender.distance(x, y, z) > MAX_DISTANCE) {
                continue;
            }
            int rgb = colored ? t.color : 0;
            double h = SQUARE_HALF;
            double in = h - BORDER;
            WorldRender.quad(
                    x - in,
                    y,
                    z - in,
                    x - in,
                    y,
                    z + in,
                    x + in,
                    y,
                    z + in,
                    x + in,
                    y,
                    z - in,
                    FILL_ALPHA | rgb);
            int edge = BORDER_ALPHA | rgb;
            // The border: four strips that meet at the corners without overlapping.
            WorldRender.quad(
                    x - h, y, z - h, x - h, y, z + h, x - in, y, z + h, x - in, y, z - h, edge);
            WorldRender.quad(
                    x + in, y, z - h, x + in, y, z + h, x + h, y, z + h, x + h, y, z - h, edge);
            WorldRender.quad(
                    x - in, y, z - h, x - in, y, z - in, x + in, y, z - in, x + in, y, z - h, edge);
            WorldRender.quad(
                    x - in, y, z + in, x - in, y, z + h, x + in, y, z + h, x + in, y, z + in, edge);
        }
    }

    private void clear() {
        for (Track t : tracks.values()) {
            t.present = false;
        }
        tracks.clear();
        shownCount = 0;
        for (int i = 0; i < CAPACITY; i++) {
            owner[i] = null;
        }
    }

    private void colors(CombatConfig c) {
        if (c.clanColor != clanCode || clanRgb == 0) {
            clanCode = c.clanColor;
            clanRgb = Option.rgb(String.valueOf(clanCode));
        }
        if (c.allyColor != allyCode || allyRgb == 0) {
            allyCode = c.allyColor;
            allyRgb = Option.rgb(String.valueOf(allyCode));
        }
    }
}
