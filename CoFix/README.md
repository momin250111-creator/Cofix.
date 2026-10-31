# CoFix 1.0.0

CoFix is a Paper/Purpur plugin for Minecraft 1.21.11 that saves a snapshot of generated chunks in all Bukkit-loaded worlds and restores the saved block state later.

## Commands

- `/cofix save` — captures all generated chunks found in the `region` folders of worlds currently loaded by Bukkit. For a consistent snapshot, run this during maintenance with no players online.
- `/cofix` — restores every saved chunk in the snapshot.
- `/cofix <radius>` — restores only blocks inside a spherical radius around the command user's current position, in their current world.
- `/cofix status` — displays saved world and chunk counts.

Permission: `cofix.admin` (default: op).

## Build

Requires Java 21 and Gradle 8.10 or newer. Run:

```bash
gradle build
```

The plugin JAR will be at `build/libs/CoFix-1.0.0.jar`.

## Install

Copy the JAR into `plugins/` and restart Paper/Purpur. The plugin creates `plugins/CoFix/` for its snapshot data.

## Important limitations

This is a block-state snapshot helper, not a complete Minecraft world backup. It saves block data, container inventories, and the four legacy sign text lines. It does **not** snapshot entities/mobs, player inventories, scheduled ticks, redstone state, block-entity data beyond containers and signs (for example, spawner settings, lectern page, banners, skull profiles, beehive contents), biomes, world metadata, or plugin data. It only snapshots chunks represented in the world's `region/*.mca` files at save time; chunks generated after the snapshot are not removed by restore. A radius restore only affects saved blocks inside the spherical radius.

A full-world save scans and serializes every generated chunk, which can take a long time and consume substantial disk space. Do not run it during active gameplay. Keep an independent server backup before testing restoration. Restores are processed on the main server thread, one chunk per tick by default, and can still cause noticeable lag.

This plugin does not use CoreProtect's database and cannot restore changes that were not present in the snapshot.
