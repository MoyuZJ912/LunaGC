# LunaGC-6.7.0 WIP

## Changes in this fork

This fork is based on [girluh/LunaGC](https://github.com/girluh/LunaGC) (`6.7.0` branch) with the following additions for a private-server deployment:

### 2026-08-17 仓库基底修正 / Repository base correction
本仓库已完全切换到 `girluh/LunaGC` 基底，仅保留私服功能改动，不再包含 fjyczcr 系提交历史。
This repository is now based purely on `girluh/LunaGC`; the fjyczcr-based history has been removed.

### Daily commissions & (1,1,2) crash fixes (2026-08-15)
- Daily commissions are fully playable end-to-end: dispatch -> scene-group load -> kill counting -> completion -> rewards -> client panel refresh.
- Fixed kill counting: `GameEntity.damage()` now forwards `killerId`, so normal attacks, skills, summons and elemental-reaction kills all advance commission progress.
- Client panel updates: progress changes send `DailyTaskProgressNotify`, and completing a task resends `WorldOwnerDailyTaskNotify` so the finished count refreshes immediately.
- Isolated Lua environments: every group script now evaluates in its own `Globals`, preventing cross-group global leakage (`attempt to index ? (a nil value)`) that could also destabilize the client.
- Added the missing `ScriptLib.CreateMonsterByConfigIdByPos` Lua API and its scene-spawn helper.
- Commission groups are now streamed by distance (load within 500m, unload beyond 1200m) instead of loading all four at once; this avoids stacking distant commission entities onto a scene transition and reduces (1,1,2) crash risk.
- Crash diagnostics: `tools/monitor_client_crash.ps1` watches the client output log for fatal .NET exceptions and automatically captures recent server logs, GC, jstack and port state into `crash-diagnostics/`; `tools/capture_crash_diag.ps1` can be run manually.

### Chest/drop fixes + dynamic entity loading (2026-08-16)
- `/spawn` with `group`/`config` now loads the real `SceneGadget` and calls `buildContent()`, so spawned chests are interactive and can trigger drops.
- `EntityItem` restores `TrifleGadget`, making drops render correctly and stay pickable; `getFightProperties()` no longer returns null, fixing related NPEs.
- `GadgetChest` falls back to the legacy drop system when `metaGadget` is null instead of crashing.
- `checkGroups` no longer scans the whole scene: it only scans the player's current `SceneBlock`, filters groups within 500m, and greedily keeps the nearest groups up to `sceneEntityLimit=409`.
- `Grid` / `SpawnDataEntry` hard caps raised to 500m.
- Fixed recursive double-loading of the same group in `SceneScriptManager.getGroupById` / `getCachedGroupInstanceById` (`findGroupById`), which was creating duplicate monsters/chests and is the most likely remaining root cause of (1,1,2).

### Account & authentication
- Password login locks the entered password (BCrypt-hashed) on first login, covering both auto-created accounts and legacy accounts with an empty password. `Account.verifyPassword` now accepts both BCrypt-hashed and legacy plaintext passwords.
- Combo-login and stoken verification are lenient: if the stored session key differs (e.g. the client cached a token from another session/server), the client's token is adopted so login succeeds instead of failing with a session-key error.
- Added the `ma-cn-passport` API routes (`/account/ma-cn-passport/...`) used by the CN SDK client, and marked accounts as adult / email-verified in the passport response.

### Mail
- `GetAllMailResultNotify` now sends `retcode 0`, total page count and page index, which the 6.7 client needs to display the mailbox.

### Activities & announcements
- Added activity, announcement and game-announcement configs (`data/ActivityConfig.json`, `data/Announcement.json`, `data/GameAnnouncement.json`, `data/GameAnnouncementList.json`).
- Quest event executor pool reduced to a single thread to avoid quest-state races.

### Gacha & shop
- Updated banner pool configs (`data/Banners.json`).
- Mapped the 6.7 obfuscated shop field name (`costItems` alias `KPCDDDCMLNB`) in `ShopGoodsData` and made cost-item handling null-safe in `ShopInfo`; trimmed `data/Shop.json`.

### Co-op world & movement
- Joining / leaving / kicking in multiplayer now uses the `ENTER_OTHER` enter type instead of `ENTER_SELF`. The 6.7 client does not reload the scene on `ENTER_SELF`, which previously left the host stuck on the loading screen and caused "internal server error" when leaving multiplayer.
- Same-scene `/tp` (COMMAND teleport) now also broadcasts a reposition to co-op peers, so they see the teleport.
- Co-op movement sync: the 6.7 client ignores movement carried inside `CombatInvocationsNotify`, so avatar movement is rebroadcast to peers as a single `SceneEntityAppearNotify` (`VISION_REPLACE`) every 100ms while the avatar is moving; idle motion states are skipped. This gives position sync without animation (a temporary workaround until real movement broadcasting is re-enabled upstream).

### Drop / chest configs
- Added drop, chest, dungeon-drop, energy-drop and blossom configs (`data/ChestDrop.json`, `data/ChestReward.json`, `data/Drop.json`, `data/DungeonDrop.json`, `data/EnergyDrop.json`, `data/BlossomConfig.json`).

### Infrastructure
- `DatabaseHelper.saveGameAsync` now retries MongoDB duplicate-key (11000) and `ConcurrentModificationException` failures instead of silently losing saves.
- Dispatch `RegionHandler` resolves the game-server address and dispatch domain per request (configured address → request host → bind address), so clients on localhost / LAN / public IP / domain can all join.
- The console input loop returns cleanly when stdin closes (EOF) instead of spinning and flooding the log.

## Note from the maintainer
Might update to latest occasionally, depends on how I'm feeling and my situation. Of course, I post the protocol buffer definitions on [GitLab](https://gitlab.com/kitkat-multiverse/genshin-protocol) and translations. Contact me at my [Discord](https://discord.gg/5Rfyjrt5aB)

## Updated version of Grasscutters, with some new features implemented.
Old Discord for LunaGC https://discord.gg/7D5gkyJR5Y (don't ask for support there as it's been taken over by other people (...), instead create an issue in this repository)

Features and functionality of the ps is not guaranteed, try it yourself to see what works and what doesnt.
This is possibly the only public PS with updated mob and gadget spawns! (Up to Version 5.4)

Contribute if you want/can...

# Read the [handbook](handbook.md)!

# Setup Guide
- Read it below, its just enough to get the server up and running along with the client.

## Main Requirements

- Get [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
- Get [MongoDB Community Server](https://www.mongodb.com/try/download/community)
- Get [NodeJS](https://nodejs.org/dist/v20.15.0/node-v20.15.0-x64.msi) (For handbook generation)
- Get game version REL6.6.0
- Make sure to install java and set the environment variables.
- Build the server (refer to "Compile the actual server" in this guide.)
- Download the [Resources](https://github.com/girluh/LunaGC-Resources), make a new folder called `resources` in the downloaded LunaGC folder and then extract the resources in that new folder.
- Set useEncryption, Questing and useInRouting to false (it should be false by default, if not then change it)
- [Patch the game](#patching-the-game)
- Start the server and the game, make sure to also create an account in the LunaGC console!
- Have fun (or don't)

### Patching the game
- Install [**Rust**](https://rust-lang.org/learn/get-started/) and **Cargo** (comes with rustup)
- Go to the `patch/` folder (make sure you have cloned this repository with the `--recurse-submodules` flag)
- Run `cargo build --release` to build the DLL at `target/release`
- Inject the DLL into the game. You can do this by renaming the patch to `Astrolabe.dll` and putting it in the game folder at `GenshinImpact_Data/Plugins`. Make sure you back up the old `Astrolabe.dll` in the plugins folder.

### Getting started

- Clone the repository (install [Git](https://git-scm.com) first )

  ```
  git clone --recurse-submodules https://github.com/kitkat033/LunaGC.git
  ```

- Now you can continue with the steps below.


### Compile the actual Server

**Requirements**:

[Java Development Kit 17 | JDK](https://oracle.com/java/technologies/javase/jdk17-archive-downloads.html) or higher

- **Sidenote**: Handbook generation may fail on some systems. To disable handbook generation, append `-PskipHandbook=1` to the `gradlew jar` command.

- **For Windows**:

  ```shell
  .\gradlew.bat
  .\gradlew.bat jar
  ```

- **For Linux**:

  ```bash
  chmod +x gradlew
  ./gradlew
  ./gradlew jar
  ```

### You can find the output JAR in the project root folder.

### Manually compile the handbook

```shell
./gradlew generateHandbook
```

## Troubleshooting

- Make sure to set useEncryption and useInRouting both to false otherwise you might encounter errors.
- To use windy make sure that you put your luac files in C:\Windy (make the folder if it doesnt exist)
- If you get an error related to MongoDB connection timeout, check if the mongodb service is running. On windows: Press windows key and r then type `services.msc`, look for mongodb server and if it's not started then start it by right clicking on it and start. On linux, you can do `systemctl status mongod` to see if it's running, if it isn't then type `systemctl start mongod`. However, if you get error 14 on linux change the owner of the mongodb folder and the .sock file (`sudo chown -R mongodb:mongodb /var/lib/mongodb` and `sudo chown mongodb:mongodb /tmp/mongodb-27017.sock` then try to start the service again.)

## Credit

proto Repository [hk4e-protos](https://gitlab.com/kitkat-multiverse/genshin-protocol)

patch Repository [hk4e-patch-universal](https://github.com/kitkat033/hk4e-patch-universal)
