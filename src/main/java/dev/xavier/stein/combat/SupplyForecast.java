/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Effects;
import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.HudElement;
import dev.xavier.stein.loader.api.HudPlacement;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Render;

import java.util.ArrayList;
import java.util.List;

/**
 * Supply Forecast on the HUD grid: how long the rush lasts at the pace so far ({@link Supplies}),
 * in one of three views — unified (one line: how long until the first supply runs out, and which),
 * by category (armor, sword, axe, bow, apples, potions) or by item (each armor piece on its own).
 * The active regeneration's time shows next to the apples. The texts are rebuilt once a second.
 */
final class SupplyForecast implements HudElement {

    static final int UNIFIED = 0;
    static final int CATEGORY = 1;
    static final int ITEM = 2;

    private static final int LINE = 11;

    private final SteinCombat mod;
    private final List<String> lines = new ArrayList<>();
    private int epoch = -1;
    private int width;

    SupplyForecast(SteinCombat mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "steincombat.forecast";
    }

    @Override
    public String name() {
        return Lang.tr("steincombat.supplyForecast");
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        CombatConfig c = mod.config;
        Supplies s = mod.supplies;
        if (!preview && (!c.supplyForecast || !s.running())) {
            return false;
        }
        if (epoch != mod.textEpoch || lines.isEmpty()) {
            epoch = mod.textEpoch;
            build(c, s, preview && !s.running());
        }
        width = 0;
        for (String l : lines) {
            width = Math.max(width, Render.textWidth(l));
        }
        return !lines.isEmpty();
    }

    private void build(CombatConfig c, Supplies s, boolean sample) {
        lines.clear();
        if (sample) {
            lines.add(Lang.tr("steincombat.forecast.sample"));
            return;
        }
        String regen = regenText();
        if (c.forecastView == UNIFIED) {
            int first = -1;
            double min = Double.MAX_VALUE;
            for (int k = 0; k < Supplies.COUNT; k++) {
                double f = s.forecast(k);
                if (f >= 0 && f < min) {
                    min = f;
                    first = k;
                }
            }
            lines.add(
                    first < 0
                            ? Lang.tr("steincombat.forecast.measuring")
                            : Lang.tr(
                                    "steincombat.forecast.unified",
                                    range(s, first, min),
                                    Lang.tr(Supplies.KEYS[first]).toLowerCase()));
            if (regen != null) {
                lines.add(regen);
            }
            return;
        }
        if (c.forecastView == CATEGORY) {
            int worst = -1;
            double min = Double.MAX_VALUE;
            for (int k = Supplies.HELMET; k <= Supplies.BOOTS; k++) {
                double f = s.forecast(k);
                if (f >= 0 && f < min) {
                    min = f;
                    worst = k;
                }
            }
            if (worst >= 0) {
                lines.add(
                        "§b"
                                + Lang.tr("steincombat.forecast.armor")
                                + " §f"
                                + range(s, worst, min)
                                + " §7("
                                + Lang.tr(Supplies.KEYS[worst]).toLowerCase()
                                + ")");
            }
        } else {
            for (int k = Supplies.HELMET; k <= Supplies.BOOTS; k++) {
                line(s, k, "§b");
            }
        }
        line(s, Supplies.SWORD, "§c");
        line(s, Supplies.AXE, "§c");
        line(s, Supplies.BOW, "§e");
        line(s, Supplies.ARROWS, "§e");
        line(s, Supplies.APPLES, "§6");
        line(s, Supplies.POTIONS, "§d");
        if (regen != null) {
            lines.add(regen);
        }
        if (lines.isEmpty()) {
            lines.add(Lang.tr("steincombat.forecast.measuring"));
        }
    }

    /** One supply's line, only when it was used or there is some left of a counted kind. */
    private void line(Supplies s, int k, String color) {
        double f = s.forecast(k);
        long left = s.left(k);
        boolean counted = k >= Supplies.ARROWS;
        if (f < 0 && !(counted && left > 0)) {
            return;
        }
        StringBuilder b = new StringBuilder(color).append(Lang.tr(Supplies.KEYS[k])).append(" §f");
        if (counted) {
            b.append(left);
            if (f >= 0) {
                b.append(" §7(").append(range(s, k, f)).append(')');
            }
        } else {
            b.append(range(s, k, f));
        }
        lines.add(b.toString());
    }

    /** "~14 min" or, early in the rush, "10–18 min". */
    private static String range(Supplies s, int k, double minutes) {
        double e = s.spread(k);
        if (e < 0.15) {
            return "~" + time(minutes);
        }
        double low = minutes / (1 + e);
        double high = minutes / (1 - e);
        if (low >= 1 && high < 99) {
            return Math.round(low) + "–" + Math.round(high) + " min"; // "7–12 min"
        }
        return time(low) + "–" + time(high);
    }

    private static String time(double minutes) {
        if (minutes >= 99) {
            return "99+ min";
        }
        if (minutes < 1) {
            return Math.max(1, Math.round(minutes * 60)) + " s";
        }
        return Math.round(minutes) + " min";
    }

    /** "Regeneration 0:21" while it is active. */
    private static String regenText() {
        Object me = Entities.self();
        if (me == null) {
            return null;
        }
        for (Effects.Effect e : Effects.of(me)) {
            if (e.id == 10) {
                return "§d" + Effects.name(e) + " §f" + Effects.durationText(e);
            }
        }
        return null;
    }

    @Override
    public int width() {
        return width + 2;
    }

    @Override
    public int height() {
        return lines.size() * LINE + 1;
    }

    @Override
    public void draw(boolean alignRight, float partialTicks) {
        float y = 1;
        for (String l : lines) {
            float x = alignRight ? width - Render.textWidth(l) + 1 : 1;
            Render.text(l, x, y, 0xFFFFFFFF, true);
            y += LINE;
        }
    }

    @Override
    public HudPlacement defaultPlacement() {
        return new HudPlacement(0, 1, 0, 40, 1.0F, true, true);
    }

    @Override
    public float defaultBackground() {
        return 0.5F;
    }

    @Override
    public void options(List<Option> out) {
        CombatConfig c = mod.config;
        out.add(
                Option.cycle(
                        Lang.tr("steincombat.forecast.view"),
                        new String[] {
                            Lang.tr("steincombat.forecast.view.unified"),
                            Lang.tr("steincombat.forecast.view.category"),
                            Lang.tr("steincombat.forecast.view.item")
                        },
                        () -> c.forecastView,
                        i -> {
                            c.forecastView = i;
                            epoch = -1;
                        }));
    }

    @Override
    public void restoreOptions() {
        mod.config.forecastView = new CombatConfig().forecastView;
    }

    @Override
    public void optionsChanged() {
        mod.save();
    }
}
