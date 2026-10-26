# Phase 2 handoff and verification boundary

The project now contains a partial Phase 2 survival increment on top of the existing Phase 1 source. It is not a complete implementation of the supplied Phase 2 specification. No target Paper 26.3 artifact or live server was available for verification, and no build/test command was run. The project remains Maven-based; no Gradle build file was added.

## Current service/API map

* `config.ConfigManager` validates configuration ranges and water-point syntax, merges bundled defaults for older configs, then swaps active configuration. Invalid candidate values leave active configuration unchanged.
* `condition.SurvivalConditionManager` provides `getThirst(UUID)`, `getStamina(UUID)`, `hydrate(Player,double)`, `restoreStamina(Player,double)`, `canSprint(Player)`, and death/join hooks. Conditions are held on the main thread; `SurvivalConditionStore` performs file reads/writes on a single worker.
* `infection.InfectionManager` continues owning Phase 1 infection progression. New `reduceStage`, `grantImmunity`, `isImmune`, and `isLoaded` methods support treatments. Immunity expiry is stored in player PDC; pre-load changes are merged rather than overwritten by the async read.
* `item.CustomItemService` remains the authority for `create(itemId)` and `getItemId(ItemStack)`. PDC keys also include perishable creation timestamps and storage identities. Display names/lore are never an identity check.
* `item.SurvivalItemService` routes configured right-click behaviors; its public `repair(Player,int,String,int)` repairs a damaged offhand item while consuming custom material IDs.
* `recipe.RecipeRegistry` provides `reload`, `list`, `get`, `search`, `forItem`, and `craft`. Its current implementation is a PDC custom-item recipe registry and command/GUI browser, not native crafting-table/furnace recipes.
* `storage.StorageService` provides `handleInteract`, `setAccessMode`, `getAccessMode`, and `setTeamResolver`. Storage is an interaction-based portable inventory, with async disk I/O and main-thread inventory serialization/deserialization.
* `communication.RadioService` exposes `broadcast(Player,String)` and `setTeamResolver`; it calls Phase 1 `NoiseService.emit(Player,double)`.
* `navigation.WaypointService` exposes `save`, `remove`, `list`, and `showLocation`; waypoint records are persisted in player PDC.
* `protection.ProtectionService` exposes `infectionChance` and `protects` equipment checks.
* `trader.TraderService` exposes offers, `buy`/`sell`, and `setEconomyAdapter`. The `EconomyAdapter`, `TeamResolver`, and `StorageTeamResolver` interfaces are integration hooks; no external economy/team plugin adapter is included.
* Plugin getters include `items()`, `infections()`, `conditions()`, `recipes()`, `storages()`, `radios()`, `noise()`, `waypoints()`, `protection()`, and `traders()`.

## Data formats and backup rules

* `infections.dat`: unchanged `schema=1`, then `UUID|STAGE|infectedAtMillis|criticalAtMillis`; `infections.dat.bak` is retained. Preserve it as an import/recovery source for any future migration. A malformed/unsupported primary file fails loading closed rather than being replaced.
* `survival-conditions.dat`: `schema=1`, then `UUID|thirst|stamina`; writes use a worker, temporary file, and `survival-conditions.dat.bak`. Malformed/unsupported data leaves the manager unloaded rather than overwriting the source.
* `storages.yml`: YAML with `schema: 1`, each storage UUID's owner UUID, item ID, access mode, size, and serialized inventory slots. `storages.yml.bak` is retained. Invalid/unsupported data leaves storage access disabled; do not delete or overwrite the file to recover.
* Player PDC stores item IDs, spoilage timestamps, storage UUID references, infection-immunity expiry, and waypoint records. Back up the server's player data along with plugin data.

## Behavior controls and limitations

Thirst/stamina default off; vanilla hunger remains native. Food/water, medical treatment, refill/purification points, optional spoilage, offhand repair, radio alerts/noise, basic coordinate waypoints, custom recipe crafting, portable persistent storage, and command-based trade tests have working code paths. Team-only features fail closed without a provider. Trader stock is in memory and trading is disabled without an economy adapter.

Still missing are full condition types, all blueprint catalog entries, physical chest/locker blocks and keys, storage team integration, NPC trading/real currency adapter, fully persistent trader stock, native workstation/multistage/unlock recipes, dynamic lighting, map discovery/team markers, treatment stations, complete protection hooks for contamination/acid/weather, Phase 1 block breaching/region adapter, natural spawn/horde engine, Phase 1's listed advanced AI/boss features, resource-pack assets, and automated/live performance/security tests. See `README.md` for the feature-by-feature boundaries. Do not describe this branch as Phase 2 complete or Paper 26.3 verified.

## Next verification steps

1. Confirm the actual Paper 26.3 artifact, Java runtime, and plugin metadata before changing the provisional Maven API pin.
2. Compile/test the final source, inspect the resulting JAR, and run the manual integration cases in `BUILD_TEST_REPORT.md` on a disposable Paper server.
3. Test migration/backup recovery and concurrent storage access; verify team/economy integrations only after actual adapters are registered.
4. Continue the omitted Phase 2 mechanics before beginning Phase 3 implementation.