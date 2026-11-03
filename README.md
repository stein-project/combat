# Combat

A [Stein Loader](https://github.com/Stein-Project) mod for Minecraft 1.8.9, for PvP: allies and clans, the nearest
enemy and ally on the HUD, and fight automations. Everything is set up on its page in the Panel (Insert), where each
feature has its own key.

Some servers do not allow these features: check the rules of where you play. A server can turn off the whole mod or
one of them (`features` in `steinmod.json`).

- **Team**: allies and enemies by nick or clan tag (the Panel, or a key aimed at a player), the members of your
  clan as allies, name colors by relation in the Tab list and above heads, and the lists from a JSON file at a URL
  (one per clan, with per-server sections). Hits on your clan are cancelled (and can go through to whoever is
  behind), and a key hides it from the screen: it disappears, becomes ghosts that hits go through, or is gone as if
  it were not there, with footprints or a square on the ground where they are. Other allies are never protected
  nor hidden: with the same key they can become ghosts, and they still take every hit.
- **Combat and movement**: No Hit Delay, Autoclick, toggle sprint and Auto Walk.
- **Target**: head, name, distance, health, armor and held item of whoever you are fighting, on the HUD grid.
- **Automations**: Auto Armor, Auto Refill (from the server's virtual chest), Smart Effect (golden apple, Speed and
  Strength) and Auto Protect (the sword blocking in menus and at low health).
- **HUD**: the nearest enemy, the nearest ally and the list of active features.

Some of these are things servers may detect and punish (the Panel says which); the player decides what to turn on.

## Download

Combat is an official extra: it is made by the Stein Project, but it does not come with the Stein Client because it
is for specific servers. In the [Stein Client](https://stein-project.github.io/client/), add its index under Additions
and the client installs and updates it for you:

```
https://github.com/Stein-Project/combat/releases/latest/download/steinmods.json
```

By hand: download `Combat.steinmod` from the
[latest release](https://github.com/Stein-Project/combat/releases/latest), put it in `.minecraft/mods` and play
with the **Stein Loader** profile.

## Building

You need **JDK 25 or newer**. The
[Stein SDK](https://github.com/Stein-Project/sdk) Gradle plugin prepares Minecraft 1.8.9 for compiling the
first time, from your own installation or from Mojang.

```sh
./gradlew build            # build/libs/Combat.steinmod (also checks the formatting)
./gradlew spotlessApply    # format the code (google-java-format, AOSP style) and add the licence header
./gradlew installMod       # build and copy into .minecraft/mods
```

Before a Stein SDK release (developing the SDK and the mod together), build against a local SDK: in a clone of
[sdk](https://github.com/Stein-Project/sdk) run `./gradlew build publishPlugin`, then here
`./gradlew build -Pstein.sdkDir=<path to that clone>` (the plugin comes from its `build/maven-repo`, the SDK from
its `build/sdk/stein-sdk.jar`).

## Contributing

Bug reports, ideas and pull requests are welcome. Read the
[contributing guide](https://github.com/Stein-Project/.github/blob/main/CONTRIBUTING.md) first; your first pull
request asks you to sign the [CLA](https://github.com/Stein-Project/.github/blob/main/CLA.md). For a change to this
mod, also check in the game, on a server with clans: the colors in the Tab list and above heads, marking an
ally and an enemy with the keys, the hit on a clanmate cancelled, the hide key, and the HUD elements.

Releases are made by the maintainer: a `v<version>` tag, with `changelog/<version>.en_US.md` (and optionally
`.pt_BR.md`) written first. Each stable release carries the mod's index, `steinmods.json` (see
`.github/workflows/release.yml`).

## Licence

Combat is licensed under the [Mozilla Public License 2.0](LICENSE). Third-party notices are
in [NOTICE](NOTICE). The Stein name and logo are not covered by the licence: see the
[trademark policy](https://github.com/Stein-Project/.github/blob/main/TRADEMARK.md).

Stein is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.

Created by [Breno Xavier](https://github.com/brenoxavier).
