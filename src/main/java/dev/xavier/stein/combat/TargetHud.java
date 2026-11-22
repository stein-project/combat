/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Lang;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Relation;
import dev.xavier.stein.loader.api.Relations;
import dev.xavier.stein.loader.api.Render;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.Stacks;

import net.minecraft.entity.EntityLivingBase;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Target: what you need to know about whoever you are fighting ({@link TargetTracker} picks who).
 * Shows the head, the name in the relation's color, the distance, the health (the bar drops with
 * the lost part fading slowly), the armor and the held item, each with its durability.
 *
 * <p>The items are the ones the server sends for each player (the equipment packet carries the
 * whole item, with enchantments and damage), drawn by {@link Render#item}, the inventory's own
 * drawing: resource packs and CIT apply here too.
 *
 * <p>Where the server hides the health or the wear ({@link TargetIntel}), the element says so
 * instead of showing its numbers; a target that dies shows dead for a moment ({@link
 * TargetTracker#died}).
 */
final class TargetHud extends GridElement {

    private static final int HEAD = 24;
    private static final int BAR_W = 100;

    /** Room kept to the right of the bar and the items for the update age. */
    private static final int AGE_W = 24;

    private final TargetConfig c;
    private final TargetTracker tracker;
    private final TargetIntel intel;

    /** What is on screen now. */
    private Object target;

    private String nick = "";

    /** How much of the element shows (0 to 1): rises when coming in, falls when leaving. */
    private float shown;

    /** The health the bar shows (chasing the real one) and the lost part that fades slowly. */
    private float shownHealth = -1;

    private float trailHealth = -1;
    private long lastFrame;

    // Built in layout, drawn in draw.
    private Object drawn;
    private String drawnNick = "";
    private final Object[] items = new Object[5];
    private int itemCount;
    private float health;
    private float maxHealth;
    private float absorption;
    private float distance;
    private String loggedDamage = "";
    private Object[] sample;

    // The texts, kept with what they were built from: they change a few times a second at most,
    // and used to be formatted (String.format) and measured twice every frame.
    private String title = "";
    private int titleWidth;
    private String titleNick;
    private int titleFlags = -1;
    private long titleTenths = Long.MIN_VALUE;
    private int titleEpoch = -1;
    private String hp = "";
    private String hpRed = "";
    private int hpWidth;
    private long hpTenths = Long.MIN_VALUE;
    private long hpAbsorption = Long.MIN_VALUE;
    private int hpEpoch = -1;
    private final Object[] durStack = new Object[5];
    private final int[] durDamage = new int[5];
    private final String[] durText = new String[5];
    private final int[] durWidth = new int[5];
    private String durSetting;
    private int durEpoch = -1;

    TargetHud(TargetConfig c, SteinCombat mod, TargetIntel intel) {
        super("steinhud.target", c, mod);
        this.c = c;
        this.tracker = new TargetTracker(c, mod.log());
        this.intel = intel;
    }

    /** A player died: if it is the one on screen, it shows dead for a moment. */
    void died(Object entity) {
        tracker.died(entity);
    }

    /** You hit someone (the attack click, before it goes to the server). */
    void hit(Object entity) {
        tracker.hit(entity);
    }

    @Override
    public String name() {
        return tr("hud.target");
    }

    private static String tr(String key, Object... args) {
        return Lang.tr("steincombat." + key, args);
    }

    /** The nick's color (RGB): the one chosen for their relation; you go with your group. */
    private static int relationColor(String nick) {
        Relation r = Relations.of(nick);
        if (r == Relation.SELF) {
            r = Players.group(nick) != null ? Relation.GROUP : Relation.NEUTRAL;
        }
        return r == Relation.NONE ? 0xFFFFFF : Relations.color(r);
    }

    private static String[] all(String... keys) {
        String[] out = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = tr(keys[i]);
        }
        return out;
    }

    /** A number formatted with a dot (Locale.ROOT) to the language's separator. */
    private static String num(String formatted) {
        String sep = tr("hud.target.decimal");
        return ".".equals(sep) ? formatted : formatted.replace(".", sep);
    }

    @Override
    public boolean layout(boolean preview, float partialTicks) {
        boolean allowed = Server.allows("targetInfo");
        if (!preview && !allowed) {
            target = null; // this server does not allow it: nothing shows, nothing is kept
            return false;
        }
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1e9F);
        lastFrame = now;
        // In the grid editor the game keeps running behind it: where the server forbids the
        // target, only the sample shows, never a real player's data.
        Object next = allowed ? tracker.choose(now) : null;
        if (next != target) {
            target = next;
            if (next != null) {
                nick = Entities.name(next);
                shownHealth = -1;
                trailHealth = -1;
            }
        }
        boolean live = target != null;
        if (live) {
            intel.watch(target, now);
        }
        float want = live || preview ? 1 : 0;
        shown = c.animation ? approach(shown, want, dt * 6) : want;
        if (live) {
            drawn = target;
            drawnNick = nick;
        } else if (preview && shown >= 1) {
            drawn = null;
            drawnNick = Players.self() != null ? Players.self() : "Steve";
        }
        if (shown <= 0.01F && !preview) {
            drawn = null;
            return false;
        }
        collect(preview && !live);
        float goal = health + absorption;
        if (shownHealth < 0 || !c.animation) {
            shownHealth = goal;
            trailHealth = goal;
        } else {
            shownHealth =
                    approach(
                            shownHealth, goal, Math.max(1, Math.abs(goal - shownHealth)) * dt * 10);
            // The lost part waits a moment and falls slowly, so you can see how big the hit was.
            trailHealth =
                    trailHealth < shownHealth
                            ? shownHealth
                            : approach(trailHealth, shownHealth, dt * 6);
        }
        int age = c.updateAge ? AGE_W : 0;
        int right =
                Math.max(c.healthBar ? BAR_W + age : 0, itemCount > 0 ? itemCount * 18 + age : 0);
        right = Math.max(right, updateTitle());
        w = (c.head ? HEAD + 4 : 0) + right;
        h = Math.max(c.head ? HEAD : 0, lines());
        return true;
    }

    /** The target's data (or a sample, in the grid editor). */
    private void collect(boolean sample) {
        itemCount = 0;
        if (sample || !(drawn instanceof EntityLivingBase l)) {
            health = 14.5F;
            maxHealth = 20;
            absorption = 4;
            distance = 3.2F;
            Object[] s = sample();
            if (c.armor) {
                for (int i = 0; i < 4; i++) {
                    items[itemCount++] = s[i];
                }
            }
            if (c.held) {
                items[itemCount++] = s[4];
            }
            return;
        }
        health = Entities.health(l);
        maxHealth = Math.max(1, Entities.maxHealth(l));
        absorption = Entities.absorption(l);
        if (tracker.isDead(l)) {
            health = 0;
            absorption = 0;
        }
        Object me = Entities.self();
        distance = me != null ? Entities.distance(me, l) : 0;
        if (c.armor) {
            for (int slot = 4; slot >= 1; slot--) { // helmet down to boots
                Object s = l.getEquipmentInSlot(slot);
                if (s != null) {
                    items[itemCount++] = s;
                }
            }
        }
        if (c.held) {
            Object s = l.getEquipmentInSlot(0);
            if (s != null) {
                items[itemCount++] = s;
            }
        }
        if (TargetTracker.LOG) {
            logDamage();
        }
    }

    private void logDamage() {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < itemCount; i++) {
            d.append(Stacks.id(items[i]))
                    .append(':')
                    .append(Stacks.damage(items[i]))
                    .append('@')
                    .append(System.identityHashCode(items[i]))
                    .append(' ');
        }
        d.append("health ")
                .append(health)
                .append('+')
                .append(absorption)
                .append(" alive ")
                .append(Entities.isAlive(drawn));
        String now = d.toString();
        if (!now.equals(loggedDamage)) {
            log().info("target " + drawnNick + " wear: " + now);
            loggedDamage = now;
        }
    }

    /** The editor's sample items, made once. */
    private Object[] sample() {
        if (sample == null) {
            sample =
                    new Object[] {
                        Stacks.create("diamond_helmet", 1, 120),
                        Stacks.create("diamond_chestplate", 1, 300),
                        Stacks.create("iron_leggings", 1, 40),
                        Stacks.create("diamond_boots", 1, 410),
                        Stacks.create("diamond_sword", 1, 700),
                    };
        }
        return sample;
    }

    /**
     * Builds the title again if the nick, the shown tenth of a metre or the settings changed;
     * returns its width.
     */
    private int updateTitle() {
        int flags = (c.name ? 1 : 0) | (c.distance ? 2 : 0);
        long tenths = c.distance ? Math.round(distance * 10.0) : 0;
        int epoch = textEpoch();
        if (drawnNick != titleNick
                || flags != titleFlags
                || tenths != titleTenths
                || epoch != titleEpoch) {
            title = title();
            titleWidth = Render.textWidth(title);
            titleNick = drawnNick;
            titleFlags = flags;
            titleTenths = tenths;
            titleEpoch = epoch;
        }
        return titleWidth;
    }

    private String title() {
        StringBuilder t = new StringBuilder();
        if (c.name) {
            t.append(drawnNick);
        }
        if (c.distance) {
            if (t.length() > 0) {
                t.append("  ");
            }
            t.append("§7").append(num(String.format(Locale.ROOT, "%.1f m", distance)));
        }
        return t.toString();
    }

    private int lines() {
        int y = 0;
        if (c.name || c.distance) {
            y += 10;
        }
        if (c.healthBar || c.healthText) {
            y += c.healthBar ? 9 : 10;
        }
        if (itemCount > 0) {
            y += 16 + (!"none".equals(c.durability) ? 7 : 0);
        }
        return y;
    }

    @Override
    void draw(boolean alignRight) {
        if (shown <= 0.01F) {
            return;
        }
        Render.push();
        if (shown < 1) {
            // Slides in and grows from the crosshair's side.
            float e = 1 - (1 - shown) * (1 - shown);
            Render.translate((1 - e) * (alignRight ? 12 : -12), h * (1 - e) * 0.5F);
            Render.scale(0.85F + 0.15F * e);
        }
        float a = shown;
        int x = 0;
        if (c.head) {
            Render.playerHead(drawnNick, alignRight ? w - HEAD : 0, 0, HEAD, true);
            if (!alignRight) {
                x = HEAD + 4;
            }
        }
        int y = 0;
        if (!title.isEmpty()) {
            int color = c.name ? relationColor(drawnNick) : 0xFFFFFF;
            Render.text(title, x, y + 1, Render.alpha(0xFF000000 | color, a), true);
            y += 10;
        }
        boolean real = drawn != null;
        long now = System.nanoTime();
        if (c.healthBar || c.healthText) {
            String state = !real ? null : tracker.isDead(drawn) ? "hud.target.dead" : null;
            if (state == null && real && intel.healthHidden()) {
                state = "hud.target.healthHidden";
            }
            if (state != null) {
                stateText(tr(state), x, y, a, state.endsWith("dead"));
            } else {
                if (c.healthBar) {
                    bar(x, y, a);
                }
                if (c.healthText) {
                    healthText(x, y, a);
                }
                if (real && c.updateAge) {
                    String age = intel.healthAge(now);
                    if (!age.isEmpty()) {
                        TargetIntel.drawAge(age, x + (c.healthBar ? BAR_W + 3 : hpWidth + 3), y, a);
                    }
                }
            }
            y += c.healthBar ? 9 : 10;
        }
        boolean wear = !(real && intel.wearHidden());
        if (real && c.updateAge && itemCount > 0) {
            String age = intel.wearAge(now);
            if (!age.isEmpty()) {
                TargetIntel.drawAge(age, x + itemCount * 18 + 1, y + 5, a);
            }
        }
        for (int i = 0; i < itemCount; i++) {
            int ix = x + i * 18;
            Object s = items[i];
            Render.item(s, ix, y, c.durabilityBar && wear);
            if (!wear) {
                continue;
            }
            String d = durability(i, s);
            if (!d.isEmpty()) {
                Render.push();
                Render.translate(ix + 8 - durWidth[i] * 0.25F, y + 17);
                Render.scale(0.5F);
                Render.text(d, 0, 0, Render.alpha(0xFFFFFFFF, a), true);
                Render.pop();
            }
        }
        Render.pop();
    }

    /**
     * In place of the health: a short line (dead, or the server hides the health), small inside the
     * bar's place.
     */
    private void stateText(String text, int x, int y, float a, boolean dead) {
        if (c.healthBar) {
            Render.rect(x, y, BAR_W, 6, Render.alpha(0xFF1A1A1A, a * 0.8F));
        }
        Render.push();
        Render.translate(x + 1, y + 1);
        Render.scale(0.5F);
        Render.text(text, 0, 0, Render.alpha(dead ? 0xFFFF5555 : 0xFFA0A0A0, a), true);
        Render.pop();
    }

    private void healthText(int x, int y, float a) {
        // Built again only when the shown tenth of health or whole absorption changes.
        long tenths = Math.round(health * 10.0);
        long whole = absorption > 0 ? Math.round((double) absorption) : -1;
        int epoch = textEpoch();
        if (tenths != hpTenths || whole != hpAbsorption || epoch != hpEpoch) {
            hp =
                    num(String.format(Locale.ROOT, "%.1f", health))
                            + (absorption > 0
                                    ? " §e+" + String.format(Locale.ROOT, "%.0f", absorption)
                                    : "");
            hpRed = "§c" + hp;
            hpWidth = Render.textWidth(hp);
            hpTenths = tenths;
            hpAbsorption = whole;
            hpEpoch = epoch;
        }
        if (c.healthBar) {
            // Small, inside the bar, against the right.
            Render.push();
            Render.translate(x + BAR_W - hpWidth * 0.5F - 1, y + 1);
            Render.scale(0.5F);
            Render.text(hp, 0, 0, Render.alpha(0xFFFFFFFF, a), true);
            Render.pop();
        } else {
            Render.text(hpRed, x, y + 1, Render.alpha(0xFFFFFFFF, a), true);
        }
    }

    /**
     * The health bar: the background, the lost part (light), health (green to red) and absorption
     * (gold).
     */
    private void bar(int x, int y, float a) {
        float total = maxHealth + Math.max(absorption, 0);
        Render.rect(x, y, BAR_W, 6, Render.alpha(0xFF1A1A1A, a * 0.8F));
        float trail = Math.min(1, trailHealth / total);
        float life = Math.min(1, Math.min(shownHealth, maxHealth) / total);
        float abs = Math.min(1, shownHealth / total) - life;
        Render.rect(x, y, BAR_W * trail, 6, Render.alpha(0xFFE8E0D0, a * 0.7F));
        float f = Math.min(1, health / maxHealth);
        int color =
                f > 0.5F
                        ? Render.mix(0xFFE0C030, 0xFF40C040, (f - 0.5F) * 2)
                        : Render.mix(0xFFD03030, 0xFFE0C030, f * 2);
        Render.rect(x, y, BAR_W * life, 6, Render.alpha(color, a));
        if (abs > 0) {
            Render.rect(x + BAR_W * life, y, BAR_W * abs, 6, Render.alpha(0xFFE8B020, a));
        }
    }

    /** Item {@code i}'s durability text, built again only when its stack or damage changes. */
    private String durability(int i, Object s) {
        int epoch = textEpoch();
        if (c.durability != durSetting || epoch != durEpoch) {
            Arrays.fill(durStack, null);
            durSetting = c.durability;
            durEpoch = epoch;
        }
        int damage = Stacks.damage(s);
        if (durStack[i] != s || durDamage[i] != damage) {
            String d = durability(s);
            durText[i] = d;
            durWidth[i] = d.isEmpty() ? 0 : Render.textWidth(d);
            durStack[i] = s;
            durDamage[i] = damage;
        }
        return durText[i];
    }

    private String durability(Object s) {
        if ("none".equals(c.durability) || !Stacks.isDamageable(s)) {
            return "";
        }
        int max = Stacks.maxDamage(s);
        int left = max - Stacks.damage(s);
        int percent = max > 0 ? left * 100 / max : 0;
        String color = percent > 60 ? "§a" : percent > 30 ? "§e" : percent > 15 ? "§6" : "§c";
        return color + ("percent".equals(c.durability) ? percent + "%" : String.valueOf(left));
    }

    private static float approach(float from, float to, float step) {
        return from < to ? Math.min(to, from + step) : Math.max(to, from - step);
    }

    @Override
    void addOptions(List<Option> out) {
        out.add(Option.toggle(tr("hud.target.aim"), () -> c.aim, v -> c.aim = v));
        out.add(
                Option.slider(
                        tr("hud.target.range"),
                        3,
                        32,
                        1,
                        () -> c.range,
                        v -> c.range = (float) v,
                        v -> String.format(Locale.ROOT, "%.0f m", v)));
        out.add(
                Option.slider(
                        tr("hud.target.seconds"),
                        1,
                        15,
                        0.5,
                        () -> c.seconds,
                        v -> c.seconds = (float) v,
                        v -> num(String.format(Locale.ROOT, "%.1f s", v))));
        out.add(
                Option.slider(
                        tr("hud.target.switch"),
                        0,
                        5,
                        0.25,
                        () -> c.switchSeconds,
                        v -> c.switchSeconds = (float) v,
                        v -> num(String.format(Locale.ROOT, "%.2f s", v))));
        out.add(Option.toggle(tr("hud.target.head"), () -> c.head, v -> c.head = v));
        out.add(Option.toggle(tr("hud.target.name"), () -> c.name, v -> c.name = v));
        out.add(Option.toggle(tr("hud.target.distance"), () -> c.distance, v -> c.distance = v));
        out.add(Option.toggle(tr("hud.target.healthBar"), () -> c.healthBar, v -> c.healthBar = v));
        out.add(
                Option.toggle(
                        tr("hud.target.healthText"), () -> c.healthText, v -> c.healthText = v));
        out.add(Option.toggle(tr("hud.target.armor"), () -> c.armor, v -> c.armor = v));
        out.add(Option.toggle(tr("hud.target.armor.held"), () -> c.held, v -> c.held = v));
        out.add(
                Option.toggle(
                        tr("hud.target.armor.bar"),
                        () -> c.durabilityBar,
                        v -> c.durabilityBar = v));
        String[] display = {"percent", "value", "none"};
        out.add(
                Option.cycle(
                        tr("hud.target.armor.display"),
                        all("hud.target.percent", "hud.target.value", "hud.target.off"),
                        () -> Math.max(0, Arrays.asList(display).indexOf(c.durability)),
                        i -> c.durability = display[i]));
        out.add(Option.toggle(tr("hud.target.animation"), () -> c.animation, v -> c.animation = v));
        out.add(Option.toggle(tr("hud.target.updateAge"), () -> c.updateAge, v -> c.updateAge = v));
    }
}
