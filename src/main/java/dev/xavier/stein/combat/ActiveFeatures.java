/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.HudElement;
import dev.xavier.stein.loader.api.HudPlacement;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Render;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of Combat's features that are on now, on the HUD grid: one line per feature, with its
 * name (the same as in the Panel), an accent bar on the edge side and a dark background. The colors
 * are the category's ({@link Features#COLORS}: combat, movement, team, automation), a rainbow that
 * runs down the list, or a single color.
 *
 * <p>Turning on and off animates: the line slides in from the edge and out the same way, and the
 * others settle. Each feature can stay out of the list ("Show in list", in the Panel). With nothing
 * on, the element disappears.
 */
final class ActiveFeatures implements HudElement {

    private static final int LINE = 11;
    private static final int PAD = 3;
    private static final int BAR = 2;

    /** Seconds of one entry or exit. */
    private static final float ANIM_SECONDS = 0.18F;

    /** The widest line on top. */
    private static final Comparator<Row> BY_WIDTH = (a, b) -> Integer.compare(b.width, a.width);

    private final SteinCombat mod;

    /** What is on screen, coming in, going out or still: id -> progress (0 out, 1 fully in). */
    private final Map<String, Float> shown = new LinkedHashMap<>();

    /** The features that should be in the list now (reused every frame). */
    private final List<Features.Feature> want = new ArrayList<>();

    /** The lines of this frame, and the pool they come from (no allocation per frame). */
    private final List<Row> rows = new ArrayList<>();

    private final Row[] pool = new Row[Features.ALL.size()];

    private long last = System.nanoTime();
    private int w;
    private int h;

    private static final class Row {
        String text;
        int category;
        int width;
        float t;
    }

    ActiveFeatures(SteinCombat mod) {
        this.mod = mod;
        for (int i = 0; i < pool.length; i++) {
            pool[i] = new Row();
        }
    }

    @Override
    public String id() {
        return "steincombat.features";
    }

    @Override
    public String name() {
        return Lang.tr("steincombat.hud.features");
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        long now = System.nanoTime();
        float step = Math.min(1F, (now - last) / 1e9F / ANIM_SECONDS);
        last = now;
        CombatConfig c = mod.config;
        // Who should be there: on and not hidden from the list (in the editor's preview, two
        // samples if nothing is on).
        want.clear();
        for (Features.Feature f : Features.ALL) {
            if (f.on.test(mod) && !c.featuresHidden.contains(f.id)) {
                want.add(f);
            }
        }
        if (want.isEmpty() && preview) {
            want.add(Features.ALL.get(0));
            want.add(Features.byId("autoWalk"));
        }
        animate(step);
        rows.clear();
        int n = 0;
        for (Features.Feature f : Features.ALL) {
            Float t = shown.get(f.id);
            if (t == null) {
                continue;
            }
            Row r = pool[n++];
            r.text = Lang.tr(f.nameKey);
            r.category = f.category;
            r.width = Render.textWidth(r.text);
            r.t = t;
            rows.add(r);
        }
        if (rows.isEmpty()) {
            return false;
        }
        if (c.featuresByWidth) {
            rows.sort(BY_WIDTH);
        }
        w = 0;
        float total = 0;
        for (Row r : rows) {
            w = Math.max(w, r.width + PAD * 2 + BAR);
            total += LINE * ease(r.t);
        }
        h = Math.max(1, Math.round(total));
        return true;
    }

    /** Moves each line's progress one step towards in (wanted) or out. */
    private void animate(float step) {
        for (Features.Feature f : Features.ALL) {
            boolean in = want.contains(f);
            Float t = shown.get(f.id);
            if (t == null && !in) {
                continue;
            }
            float v = t == null ? 0F : t;
            v = in ? Math.min(1F, v + step) : Math.max(0F, v - step);
            if (v <= 0F && !in) {
                shown.remove(f.id);
            } else {
                shown.put(f.id, v);
            }
        }
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public void draw(boolean alignRight, float partialTicks) {
        CombatConfig c = mod.config;
        int fixed = 0xFF000000 | Option.rgb(String.valueOf(c.featuresColor));
        float y = 0;
        long time = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float e = ease(r.t);
            int color =
                    c.featuresStyle == 0
                            ? Features.COLORS[r.category]
                            : c.featuresStyle == 1 ? rainbow(time, i) : fixed;
            int rowW = r.width + PAD * 2 + BAR;
            // Slides in from the edge the list is pinned to, and is transparent until it arrives.
            float slide = (1 - e) * (rowW + 4);
            float x = alignRight ? w - rowW + slide : -slide;
            if (c.featuresBackground) {
                Render.rect(x, y, rowW, LINE * e, Render.alpha(0xB0101014, e));
            }
            float barX = alignRight ? x + rowW - BAR : x;
            Render.rect(barX, y, BAR, LINE * e, Render.alpha(color, e));
            int a = (int) (255 * e);
            if (a > 8) {
                float tx = alignRight ? x + PAD : x + BAR + PAD;
                Render.text(r.text, tx, y + 2, (a << 24) | (color & 0xFFFFFF), true);
            }
            y += LINE * e;
        }
    }

    /** A rainbow that runs down the list: each line a little ahead of the one above. */
    private static int rainbow(long time, int index) {
        float hue = ((time % 4000) / 4000F + index * 0.07F) % 1F;
        return 0xFF000000 | (Color.HSBtoRGB(hue, 0.55F, 1F) & 0xFFFFFF);
    }

    private static float ease(float t) {
        return 1 - (1 - t) * (1 - t);
    }

    @Override
    public HudPlacement defaultPlacement() {
        // Top corner, on the right, under the minimap.
        return new HudPlacement(2, 0, -2, 72, 1.0F, true, true);
    }

    @Override
    public void options(List<Option> out) {
        CombatConfig c = mod.config;
        out.add(
                Option.cycle(
                        Lang.tr("steincombat.hud.featuresStyle"),
                        new String[] {
                            Lang.tr("steincombat.hud.featuresStyle.category"),
                            Lang.tr("steincombat.hud.featuresStyle.rainbow"),
                            Lang.tr("steincombat.hud.featuresStyle.fixed")
                        },
                        () -> c.featuresStyle,
                        i -> c.featuresStyle = i));
        out.add(
                Option.color(
                                Lang.tr("steincombat.hud.featuresColor"),
                                () -> String.valueOf(c.featuresColor),
                                v -> c.featuresColor = v.charAt(0))
                        .visibleWhen(() -> c.featuresStyle == 2));
        out.add(
                Option.toggle(
                        Lang.tr("steincombat.hud.featuresBackground"),
                        () -> c.featuresBackground,
                        v -> c.featuresBackground = v));
        out.add(
                Option.cycle(
                        Lang.tr("steincombat.hud.featuresOrder"),
                        new String[] {
                            Lang.tr("steincombat.hud.featuresOrder.width"),
                            Lang.tr("steincombat.hud.featuresOrder.fixed")
                        },
                        () -> c.featuresByWidth ? 0 : 1,
                        i -> c.featuresByWidth = i == 0));
    }

    @Override
    public void restoreOptions() {
        CombatConfig d = new CombatConfig();
        CombatConfig c = mod.config;
        c.featuresColor = d.featuresColor;
        c.featuresByWidth = d.featuresByWidth;
        c.featuresStyle = d.featuresStyle;
        c.featuresBackground = d.featuresBackground;
    }

    @Override
    public void optionsChanged() {
        mod.save();
    }
}
