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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supply Tracker on the HUD grid (the class keeps its first name, Supply Forecast): how long each
 * supply lasts at the pace so far ({@link Supplies}), one line each — the armor (the whole set at
 * once, or piece by piece), the sword, the axe, the bow and the arrows; then, for each effect the
 * potions and the enchanted golden apples give, an estimate of how long it lasts drinking (or
 * eating) them one after the other: what is left of the effect running now, plus their durations
 * weighed by how much of a dose this player gets ({@link Supplies#doseUse}: renewing before the
 * effect ends throws away what was left). A supply shows once it is being used (or, for what is
 * counted, while there is some) and leaves the list when it runs out. The texts are rebuilt every
 * tick, as the supplies are used: the figures follow the fight in real time.
 */
final class SupplyForecast implements HudElement {

    private static final int LINE = 11;

    /** An enchanted golden apple's Regeneration (1.8: level V for 30 seconds). */
    private static final int REGENERATION = 10;

    private static final int APPLE_LEVEL = 4;
    private static final int APPLE_TICKS = 600;

    /** Effect time on hand, in ticks, by effect and level ({@link Supplies#effectKey}). */
    private final Map<Integer, long[]> cover = new LinkedHashMap<>();

    private final SteinCombat mod;
    private final List<String> lines = new ArrayList<>();

    /** The tick the texts were built for ({@link Supplies#clock}). */
    private long builtAt = -1;

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
        boolean sample = preview && !s.running();
        if (builtAt != s.clock() || sample && lines.isEmpty()) {
            builtAt = s.clock();
            build(c, s, sample);
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
        if (c.forecastArmorSet) {
            armorSet(s);
        } else {
            for (int k = Supplies.HELMET; k <= Supplies.BOOTS; k++) {
                worn(s, k, "§b");
            }
        }
        worn(s, Supplies.SWORD, "§c");
        worn(s, Supplies.AXE, "§c");
        worn(s, Supplies.BOW, "§e");
        counted(s, Supplies.ARROWS, "§e");
        effects(s);
        // Nothing worked out yet: no line at all, and the element stays hidden until there is.
    }

    /**
     * The whole set at once: every piece's durability left over the set's pace — gone when no piece
     * is left.
     */
    private void armorSet(Supplies s) {
        long left = 0;
        double pace = 0;
        for (int k = Supplies.HELMET; k <= Supplies.BOOTS; k++) {
            long l = s.left(k);
            double f = s.forecast(k);
            left += l;
            if (l > 0 && f > 0) {
                pace += l / f; // each piece's wear per minute adds up to the set's
            }
        }
        if (left > 0 && pace > 0) {
            line(Lang.tr("steincombat.forecast.armor"), "§b", time(left / pace));
        }
    }

    /** Something that wears (a piece, a weapon): shown while there is some and it is being used. */
    private void worn(Supplies s, int k, String color) {
        double f = s.forecast(k);
        if (s.left(k) > 0 && f >= 0) {
            line(Lang.tr(Supplies.KEYS[k]), color, time(f));
        }
    }

    private void counted(Supplies s, int k, String color) {
        counted(Lang.tr(Supplies.KEYS[k]), color, s.left(k), s.forecast(k));
    }

    /** Something counted: how many, and how long they last once some were used. */
    private void counted(String name, String color, long left, double f) {
        if (left <= 0) {
            return;
        }
        line(name, color, f >= 0 ? left + " §7· §f" + time(f) : String.valueOf(left));
    }

    /**
     * How long each effect lasts with the potions and apples on hand, one after the other: what is
     * left of the effect running now, plus their durations weighed by the part of a dose this
     * player uses. Instant effects (Healing, Harming) have no time and do not show.
     */
    private void effects(Supplies s) {
        for (long[] t : cover.values()) {
            t[0] = 0;
        }
        for (Supplies.Potion p : s.potions()) {
            long left = s.left(p);
            if (left <= 0 || p.stack == null) {
                continue;
            }
            for (Effects.Effect e : Effects.ofPotion(p.stack)) {
                if (e.duration > 1) {
                    add(s, Supplies.effectKey(e.id, e.amplifier), left * e.duration);
                }
            }
        }
        long apples = s.left(Supplies.APPLES);
        if (apples > 0) {
            add(s, Supplies.effectKey(REGENERATION, APPLE_LEVEL), apples * APPLE_TICKS);
        }
        Object me = Entities.self();
        if (me != null) {
            for (Effects.Effect e : Effects.of(me)) {
                long[] t = cover.get(Supplies.effectKey(e.id, e.amplifier));
                if (t != null && t[0] > 0 && !e.permanent) {
                    t[0] += e.duration; // the one running now comes first
                }
            }
        }
        for (Map.Entry<Integer, long[]> en : cover.entrySet()) {
            long ticks = en.getValue()[0];
            if (ticks > 0) {
                int k = en.getKey();
                String name = Effects.name(new Effects.Effect(k >> 8, k & 0xFF, 0, false));
                line(name, "§d", time(ticks / 1200.0));
            }
        }
    }

    /** Doses on hand of the effect, weighed by the part of a dose this player uses. */
    private void add(Supplies s, int key, long ticks) {
        long[] t = cover.get(key);
        if (t == null) {
            t = new long[1];
            cover.put(key, t);
        }
        t[0] += Math.round(ticks * s.doseUse(key));
    }

    private void line(String name, String color, String value) {
        lines.add(color + name + " §f" + value);
    }

    /** "~14 min", "~40 s", "99+ min". */
    private static String time(double minutes) {
        if (minutes >= 99) {
            return "99+ min";
        }
        if (minutes < 1) {
            return "~" + Math.max(1, Math.round(minutes * 60)) + " s";
        }
        return "~" + Math.round(minutes) + " min";
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
        out.add(armorOption(mod.config, "", () -> builtAt = -1));
    }

    /** "Armor: whole set / piece by piece", here and in Combat's page. */
    static Option armorOption(CombatConfig c, String indent, Runnable changed) {
        return Option.cycle(
                indent + Lang.tr("steincombat.forecast.armorMode"),
                new String[] {
                    Lang.tr("steincombat.forecast.armorMode.set"),
                    Lang.tr("steincombat.forecast.armorMode.piece")
                },
                () -> c.forecastArmorSet ? 0 : 1,
                i -> {
                    c.forecastArmorSet = i == 0;
                    changed.run();
                });
    }

    @Override
    public void restoreOptions() {
        mod.config.forecastArmorSet = new CombatConfig().forecastArmorSet;
    }

    @Override
    public void optionsChanged() {
        mod.save();
    }
}
