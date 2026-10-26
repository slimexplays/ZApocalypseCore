# Build and test report — current partial implementation

## Environment and build status

* Build system: Maven, project file `pom.xml`; no `build.gradle` was created.
* Configured compiler release: Java 25.
* Configured Paper API: `26.3.build.222-stable`; configured XSeries: `13.3.3`.
* Paper's version endpoint reports Minecraft 26.3 as supported, Java 25 as its minimum, and build 222 as stable. The matching Paper API Maven artifact URL responded with HTTP 200. `plugin.yml` declares `api-version: '26.3'`.
* Target availability is verified; this does not verify that the project compiles or runs against that API.
* Existing XSeries `13.3.3` dependency resolution/compatibility with Paper 26.3: **not build-verified**.
* A passing compile is **not confirmed**; the current source revision has no recorded successful verifier result.
* Unit/automated tests executed: **none**. No test sources exist.
* Live Paper/Pterodactyl integration, startup, firearm shooting/reload/durability, gun/ammo recipes and drops, loot-search timing/rarity/cooldowns, regional progression, config reload, persistence/restart, inventory interactions, recipe exploit cases, PDC spoofing, migration, team/economy adapters, WorldGuard behavior, concurrency, load, and log inspection: **not run**. No live server was available.
* Compiled JAR and complete source ZIP: **not generated or verified**. No artifact link is available.

## Source changes made

The source includes configured cuboid regional infection, 20 server-side firearms with PDC-backed magazines/condition, delayed reload, block-aware hitscan against zombies, variant damage multipliers, zombie-noise attraction, craft recipes and loot entries, plus optional time- and rarity-weighted searches of existing containers. These additions have not been built or gameplay-tested. Security, CCTV, power, events, NPC/quests, dashboard, protection-plugin adapters, and custom resource-pack assets remain unimplemented.

## Required verification before deployment

On a disposable server using the exact confirmed Paper/Java target, compile and test firearm configuration validation, PDC ID checks, block occlusion, ammo accounting, magazine reload/cancel/restart behavior, durability, burst timing, zombie armor multipliers, noise attraction, recipes, drops, and permission denial. Test loot-search cancellation on movement/quit, weighted rarity and reward selection, tile PDC cooldown persistence, container changes, full inventory handling, and WorldGuard fail-closed behavior. Also test regional boundaries, thresholds, spread/decay, notices, reload, admin access, backup/restart/corrupted-file behavior, bite-probability modifiers, and existing survival workflows while monitoring scheduler/thread/file errors.

Do not report any of these tests as passed until actually run. The remaining unsupported or partial mechanics are listed in `README.md` and `PHASE_2_HANDOFF.md`; this source increment must not be called a completed Phase 2 release.