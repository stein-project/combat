/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.combat;

import dev.xavier.stein.loader.api.Entities;
import dev.xavier.stein.loader.api.Gameplay;
import dev.xavier.stein.loader.api.Hud;
import dev.xavier.stein.loader.api.Inventory;
import dev.xavier.stein.loader.api.Log;
import dev.xavier.stein.loader.api.ModContext;
import dev.xavier.stein.loader.api.Option;
import dev.xavier.stein.loader.api.Page;
import dev.xavier.stein.loader.api.Players;
import dev.xavier.stein.loader.api.Relation;
import dev.xavier.stein.loader.api.Relations;
import dev.xavier.stein.loader.api.Server;
import dev.xavier.stein.loader.api.SteinMod;

import java.io.File;

/**
 * Combat: Better Sprinting's sprint and BattleChatter's allies, in code of its own.
 *
 * <ul>
 *   <li>Auto Walk (J): walking forward without holding the game's key ({@link AutoWalk}) — a sprint
 *       with the Sprint mod's toggle sprint on (toggle sprint moved there).
 *   <li>Allies: nicks and clans on a list (the Panel, or the Y key aimed at a player; with Shift,
 *       their clan), and the members of my clan (an option, on). What being an ally gives is <b>the
 *       name's color</b>, in the Tab list ({@code onPlayerListName}) and above the head ({@code
 *       onNametag}).
 *   <li>Allies (my clan, marked nicks and allied clans) have hits cancelled before they reach the
 *       server and leave the screen with the hide key — disappearing, as ghosts or as if they were
 *       not there, with footprints where they walk ({@link ClanGuard}, {@link PresenceMarker}).
 *   <li>Enemies: nicks and clans on a list (the Panel, or the U key aimed at a player; with Shift,
 *       their clan). Marked as enemy beats clan and alliance ({@link Teams}).
 *   <li>The list from the internet ({@link RemoteList}): instead of everyone writing their own, the
 *       four lists come from a JSON file at a URL — the clan's, for instance.
 *   <li>Fight automations: Auto Armor, Auto Refill, Smart Effect, Auto Protect and Autoclick, each
 *       in its own class, in that order every tick.
 * </ul>
 *
 * The clan tag comes from the scoreboard ({@link Clans}).
 */
public final class SteinCombat implements SteinMod {

    /** The mod's name, which is the name of its page in the Panel. */
    static final String NAME = "Combat";

    /** The mod id: the owner of what it registers in the Loader. */
    static final String MOD_ID = "steincombat";

    private final ModContext ctx = ModContext.of(this);

    CombatConfig config = new CombatConfig();

    private TargetConfig targetConfig;
    private File targetFile;
    private TargetHud targetHud;

    /** Changes when the target's cached texts may be stale: once a second. */
    int textEpoch;

    private int ticks;
    final Teams teams = new Teams(this);
    final AutoWalk autoWalk = new AutoWalk();
    final ClanGuard guard = new ClanGuard(this);
    final RemoteList remote = new RemoteList(this);
    final Marks marks = new Marks(this);
    CombatKeys keys;

    private final AutoArmor autoArmor = new AutoArmor(this);
    final AutoRefill autoRefill = new AutoRefill(this);
    final SmartEffect smartEffect = new SmartEffect(this);
    final Autoclick autoclick = new Autoclick(this);

    // The item modules, and who decides whose turn it is with the inventory and the hand.
    final Coordinator coordinator = new Coordinator(this);
    final AutoStash autoStash = new AutoStash(this);
    final ItemLock itemLock = new ItemLock(this);
    final AutoTrash autoTrash = new AutoTrash(this);
    final GroundItems groundItems = new GroundItems(this);
    final LootRadar lootRadar = new LootRadar(this);
    final Supplies supplies = new Supplies(this);
    private final Diagnostics diagnostics = new Diagnostics(this);
    private final PresenceMarker marker = new PresenceMarker(this);

    /** Ticks since Auto Armor last touched the inventory. */
    private int armorQuiet = 100;

    private File configFile;

    @Override
    public void afterStartGame() {
        configFile = ctx.configFile();
        config = ConfigFile.load(configFile);
        keys = new CombatKeys(this);
        if (SprintLink.rememberOldKey(configFile.getParentFile().getParentFile(), config)) {
            save();
        }
        apply();
        CombatApi.mod = this;
        // What this server calls a group, and who is what to the player: the Loader takes it to
        // every
        // mod (Relations/Players). The group is decided entirely here — this project's clan
        // plugin's
        // sc_ team, or the bracketed three-letter tag; "" = no group, and the Loader's generic way
        // is
        // not even tried.
        Players.groupResolver(
                MOD_ID,
                nick -> {
                    String tag = Clans.of(nick);
                    return tag == null ? "" : tag;
                });
        Relations.register(MOD_ID, teams::relationOf);
        publishColors();
        Hud.register(new NearestPlayer(this, false));
        Hud.register(new NearestPlayer(this, true));
        Hud.register(new ActiveFeatures(this));
        Hud.register(new SupplyForecast(this));
        Hud.register(new RefillProgress(this));
        targetFile = new File(configFile.getParentFile(), "steincombat-target.json");
        targetConfig = TargetConfigFile.load(targetFile, log());
        // Written at once: from here on this file is the one that counts.
        TargetConfigFile.save(targetConfig, targetFile, log());
        targetHud = new TargetHud(targetConfig, this);
        Hud.register(targetHud);
        log().info(
                        "started ("
                                + configFile
                                + ", "
                                + config.allies.size()
                                + " allies, "
                                + config.allyClans.size()
                                + " allied clans, "
                                + config.enemies.size()
                                + " enemies)");
    }

    Log log() {
        return ctx.log();
    }

    File configFile() {
        return configFile;
    }

    /**
     * Joined a server: this is where the clan's list is fetched, once per join — so it arrives
     * fresh every session without anyone pressing anything, and a change of side in the file
     * applies to everyone on the next join.
     */
    @Override
    public void onJoinGame() {
        remote.joined();
        apply();
        if (config.remote) {
            remote.fetch(false);
        }
    }

    /**
     * The whole inventory arrived from the server: Auto Armor and Auto Refill may be waiting for it
     * in a check.
     */
    @Override
    public void onWindowItems(int windowId) {
        autoArmor.windowItems(windowId);
        autoRefill.windowItems(windowId);
        autoStash.windowItems(windowId);
        if (windowId != 0 && coordinator.chestTrip() && Inventory.isContainerOpen()) {
            supplies.chestSeen(); // the server's chest, as the trip sees it
        }
    }

    /** Item Lock: the drop click on a locked item is cancelled. */
    @Override
    public boolean onWindowClick(int windowId, int slot, int button, int mode) {
        return Server.allows("itemLock") && itemLock.onWindowClick(slot, mode);
    }

    /** Item Lock: the drop key's presses with a locked item in the hand are eaten. */
    @Override
    public void onKeyInput() {
        if (Server.allows("itemLock")) {
            itemLock.keyInput();
        }
    }

    /** Loot Radar's arrows at the edge of the screen. */
    @Override
    public void onOverlay(float partialTicks) {
        if (Server.allows("lootRadar")) {
            lootRadar.overlay(partialTicks);
        }
    }

    /**
     * Auto Armor swapped in the last ticks or is waiting for the server to check a swap: no chest
     * opens now (Auto Refill).
     */
    boolean armorSettling() {
        return armorQuiet < 2 || autoArmor.checking();
    }

    /**
     * The server announced damage on an entity (status 2): for the Autoclick's W-tap, the attack
     * landed. It comes with every hit, also hits on consecutive ticks and on players whose health
     * the server hides — where neither the hurt animation nor the health shows it. The effect
     * stays.
     */
    @Override
    public boolean onEntityHurt(Object entity) {
        if (entity != Entities.self()) {
            autoclick.targetHurt(entity);
        }
        return false;
    }

    /** The player's health went down: Auto Armor checks whether the worn armor wore. */
    @Override
    public void onHealthChanged(
            Object entity,
            float oldHealth,
            float newHealth,
            float oldAbsorption,
            float newAbsorption) {
        if (newHealth + newAbsorption < oldHealth + oldAbsorption) {
            if (entity == Entities.self()) {
                autoArmor.hurt();
            } else {
                autoclick.targetHurt(entity);
            }
        }
    }

    /** After changing the options: saves, rebuilds the lists and tells the Loader. */
    void save() {
        groundItems.rulesChanged();
        if (targetFile != null) {
            TargetConfigFile.save(targetConfig, targetFile, log());
        }
        if (configFile != null) {
            ConfigFile.save(config, configFile);
        }
        apply();
    }

    /**
     * Rebuilds the lists in effect ({@link Teams#apply}: one source or the other), sets the engine
     * switches and tells the Loader.
     */
    void apply() {
        teams.apply(config, config.remote ? remote.feed() : null);
        publishColors();
    }

    /**
     * The colors chosen here apply to every mod; names without color, white in all. Says something
     * changed.
     */
    private void publishColors() {
        boolean on = config.colorNames;
        Relations.setColor(
                Relation.GROUP, on ? Option.rgb(String.valueOf(config.clanColor)) : 0xFFFFFF);
        Relations.setColor(
                Relation.ALLY, on ? Option.rgb(String.valueOf(config.allyColor)) : 0xFFFFFF);
        Relations.setColor(
                Relation.ENEMY, on ? Option.rgb(String.valueOf(config.enemyColor)) : 0xFFFFFF);
        Relations.setColor(
                Relation.NEUTRAL, on ? Option.rgb(String.valueOf(config.neutralColor)) : 0xFFFFFF);
        Relations.changed();
    }

    /**
     * The Autoclick: each click's time is checked every frame (see {@link Autoclick}); and the
     * hidden allies' footprints.
     */
    @Override
    public void onRenderWorld(float partialTicks) {
        if (Server.allows("autoclick")) {
            autoclick.frame();
        }
        marker.frame(partialTicks);
        if (Server.allows("lootRadar")) {
            lootRadar.frame(partialTicks);
        }
    }

    /**
     * Another Combat feature has the hand or the inventory: eating, drinking, switching slots,
     * blocking (Smart Effect and Auto Protect), with the refill chest (Auto Refill) or equipping
     * armor (Auto Armor, until one tick later). The Autoclick, last in line, waits.
     */
    boolean handBusy() {
        return coordinator.ahead(Coordinator.CLICK);
    }

    @Override
    public void onTickEnd() {
        if (++ticks % 20 == 0) {
            textEpoch++;
        }
        if (keys == null) {
            return;
        }
        // Changed server: the per-server sections may be others, and with them the lists.
        if (remote.serverChanged()) {
            apply();
        }
        diagnostics.beforeKeys();
        keys.handle();
        // Armor first: in the tick it touches the inventory, Smart Effect starts nothing.
        boolean armorActed = Server.allows("autoArmor") && autoArmor.tick();
        armorQuiet = armorActed ? 0 : Math.min(100, armorQuiet + 1);
        if (Server.allows("autoRefill")) {
            autoRefill.tick();
        }
        if (Server.allows("smartEffect")) {
            smartEffect.tick(armorActed);
        }
        itemLock.tick();
        if (Server.allows("autoStash")) {
            autoStash.tick();
        }
        if (Server.allows("autoTrash")) {
            autoTrash.tick();
        }
        groundItems.tick();
        if (Server.allows("lootRadar")) {
            lootRadar.tick();
        }
        if (Server.allows("supplyForecast")) {
            supplies.tick();
        }
        autoWalk.tick(Server.allows("autoWalk"));
        marker.tick();
        if (Server.allows("autoclick")) {
            autoclick.tick();
        }
        // Each tick: a server can take it away, and give it back, at any moment.
        Gameplay.setNoHitDelay(config.noHitDelay && Server.allows("noHitDelay"));
        diagnostics.afterTick();
    }

    @Override
    public boolean onAttackEntity(Object target) {
        if (guard.onAttackEntity(target)) {
            return true;
        }
        autoclick.attacked(target); // the W-tap follows when it lands
        if (targetHud != null) {
            targetHud.hit(target);
        }
        supplies.attacked();
        return false;
    }

    @Override
    public boolean onInteractEntity(Object target) {
        return guard.onInteractEntity(target);
    }

    @Override
    public boolean onHideEntity(Object entity) {
        return guard.onHideEntity(entity);
    }

    @Override
    public boolean onGhostEntity(Object entity) {
        return guard.onGhostEntity(entity);
    }

    @Override
    public String onPlayerListName(Object playerInfo, String name) {
        String nick = config.colorNames ? Players.nickOf(playerInfo) : null;
        return nick == null ? name : teams.paintListName(nick, name);
    }

    @Override
    public Object onNametag(Object entity, Object name) {
        Object ghost = guard.ghostName(entity);
        if (ghost != null) {
            return ghost;
        }
        if (!config.colorNames || !Entities.isPlayer(entity)) {
            return name;
        }
        return teams.paintNametag(Entities.name(entity), name);
    }

    @Override
    public void onPage(Page page) {
        CombatPage.build(this, page);
    }
}
