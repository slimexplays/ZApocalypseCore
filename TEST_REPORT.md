# Test report — current workspace state

## Environment

- Build system: Maven (`pom.xml`); no Gradle build file was added.
- Configured compiler release: Java 25.
- Configured Paper API: `26.3.build.222-stable`; plugin metadata declares API `26.3`.
- Paper's version endpoint lists 26.3 as supported, stable build 222, and Java 25 as the minimum; the matching Paper API Maven artifact URL responded HTTP 200. This verifies target availability only; a successful project build and runtime compatibility are **NOT CONFIRMED**.
- Existing XSeries `13.3.3` dependency compatibility with Paper 26.3: **NOT VERIFIED by a build**.
- Master blueprint `Ultra_Advanced_Zombie_Apocalypse_Feature_Blueprint_26.3.txt`: **MISSING** from the workspace.
- No live Paper or Pterodactyl server was available.

## Results

| Check | Result | Evidence / limitation |
|---|---|---|
| Source/build/config/persistence inventory | PASS | Existing Maven source, Phase 1 and partial Phase 2 services, configs, stores, and handoff files were inspected. No SQL schema or `src/test` suite exists. |
| Clean Maven build | NOT CONFIRMED | No successful final compile result is recorded. No JAR is claimed. |
| Automated/unit tests | NOT RUN | The project has no test sources; no tests were executed. |
| Paper 26.3 API/runtime target availability | PASS (metadata only) | Paper 26.3 is supported; stable build 222, minimum Java 25, and the matching API artifact URL were verified. Plugin compilation and runtime remain untested. |
| Firearm implementation/config presence | PASS (source review only) | 20 firearm definitions, ammo items, PDC-backed state, reload/fire handlers, recipes, and drop entries are present in source/config; no compile or gameplay verification has run. |
| Firearm firing/reload/durability/anti-duplication behavior | NOT RUN | No Paper server test for ray tracing, block occlusion, ammo removal, PDC state, reload timing/cancel, or multiplayer behavior. |
| Loot-search implementation/config presence | PASS (source review only) | Optional timed search, rarity weighting, rewards, and per-container PDC cooldown are present; default is disabled and WorldGuard presence disables the feature. |
| Loot-search timing, rewards, cooldown persistence, protection behavior | NOT RUN | No live test of movement/quit cancellation, concurrent searches, chunk unload, tile PDC persistence, or claims/protection plugins. |
| Optional dependency-absent startup | NOT RUN | No live server test. |
| Invalid configuration/reload recovery | NOT RUN | Validation code was added, but runtime cases were not executed. |
| Regional infection progression and boundaries | NOT RUN | No live/world test of cuboid lookup, threshold transitions, spread/decay, or notifications. |
| Regional persistence/restart/corruption recovery | NOT RUN | Store code uses async file I/O, schema validation, temporary writes, and a backup; behavior was not executed. |
| Commands/permissions integration | NOT RUN | `/zombie region` uses the existing admin permission path; no command integration test was run. |
| Security, CCTV, power, event, NPC/quest, dashboard workflows | NOT RUN / NOT IMPLEMENTED | These Phase 3 systems are not present in this increment. |
| Phase 1/partial Phase 2 regression suite | NOT RUN | No regression tests or live server were available. |
| World/map generation added by this change | PASS (source review) | The changed feature defines configured cuboids and does not create worlds, chunks, terrain, structures, or maps. No live-server test was run. |
| JAR, source ZIP, resource pack | NOT GENERATED | No release artifacts exist to link or checksum; no custom textures, models, sounds, or resource-pack mappings were created. |

## Required next verification

Run Kodari Compile and inspect its actual output. Then test on a disposable server using the verified Paper/Java target: first startup, valid/invalid config reload, regional commands and permission denial, cuboid edges and overlaps, category thresholds, progression/decay, notifications, firearm recipes and loot, PDC identity validation, reload/ammo/durability, blocked line of sight, zombie variant damage, loot-search cancellation/rarity/cooldown persistence and protection behavior, persistence across restart, malformed-file handling and backup recovery, bite probability modifiers, and regression of existing Phase 1/2 features. Live tests remain **NOT RUN** until performed.