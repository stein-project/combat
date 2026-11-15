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
import dev.xavier.stein.loader.api.Render;

import java.util.Locale;

/**
 * Refill Progress on the HUD grid: while Auto Refill is on a trip, "Refilling… 2.1 s" with the time
 * it still needs (a step per piece still missing), or "opening the chest" while the chest has not
 * opened. It shows only during the trip; the HUD editor turns it on or off like any element.
 */
final class RefillProgress implements HudElement {

    private final SteinCombat mod;
    private String text = "";

    RefillProgress(SteinCombat mod) {
        this.mod = mod;
    }

    @Override
    public String id() {
        return "steincombat.refillProgress";
    }

    @Override
    public String name() {
        return Lang.tr("steincombat.hud.refillProgress");
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        AutoRefill r = mod.autoRefill;
        if (!r.busy()) {
            if (!preview) {
                return false;
            }
            text = "§d" + Lang.tr("steincombat.refill.progress", seconds(42));
            return true;
        }
        int left = r.remainingTicks();
        text =
                "§d"
                        + (left < 0
                                ? Lang.tr("steincombat.refill.opening")
                                : Lang.tr("steincombat.refill.progress", seconds(left)));
        return true;
    }

    private static String seconds(int ticks) {
        return String.format(Locale.ROOT, "%.1f s", ticks / 20.0).replace('.', ',');
    }

    @Override
    public int width() {
        return Render.textWidth(text) + 2;
    }

    @Override
    public int height() {
        return 12;
    }

    @Override
    public void draw(boolean alignRight, float partialTicks) {
        Render.text(text, 1, 2, 0xFFFFFFFF, true);
    }

    @Override
    public HudPlacement defaultPlacement() {
        return new HudPlacement(1, 1, 0, 30, 1.0F, true, true);
    }

    @Override
    public float defaultBackground() {
        return 0.5F;
    }
}
