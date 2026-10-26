# Phase 3 handoff — current extension points

This document describes the available extension APIs and a limited regional-infection Phase 3 slice; it does not assert that Phase 3 is complete or that the project is verified against Paper 26.3. Before deployment, confirm the actual Paper/Java target and compile/test this source on it.

## Plugin entry point services

`ZombieApocalypsePlugin` exposes:

* `config()` — active validated `ConfigManager`.
* `items()` — PDC-based `CustomItemService`; use `create(itemId)`, `getItemId(ItemStack)`, and `getStorageId(ItemStack)`.
* `infections()` — Phase 1 infection manager plus stage reduction and PDC immunity hooks.
* `conditions()` — thirst/stamina state and mutation API.
* `recipes()` — `RecipeRegistry` reload/list/get/search/forItem/craft methods.
* `storages()` — portable inventory/access service.
* `radios()` and `noise()` — alert broadcasts and zombie noise investigation hooks.
* `waypoints()` — player-PDC location marker API.
* `protection()` — equipment hazard checks.
* `survivalItems()` — configured item behavior and public offhand `repair` operation.
* `traders()` — offer/stock service with optional economy injection.
* `zombies()` — existing Phase 1 zombie service.
* `regionalInfection()` — configured cuboids, region levels, bite-infection multipliers, and admin level control.

All Bukkit/Paper player, inventory, world, and PDC mutations must run on the server thread. Durable file writes are serialized on dedicated workers.

## Extension interfaces

* `TeamResolver` can be registered with `radios().setTeamResolver(...)` for team-only messages.
* `StorageTeamResolver` can be registered with `storages().setTeamResolver(...)`; team access fails closed if absent.
* `EconomyAdapter` can be registered with `traders().setEconomyAdapter(...)`. No Vault adapter ships with this project.
* `RecipeRegistry` currently stores custom-item-ID ingredients/results and performs virtual `/zrecipe craft` operations. Extend its definition model and migration before adding workstation, unlock, or multistage recipes.
* `NoiseService.emit(Player,double)` and `emit(Location,double)` are the Phase 1 integration points for future devices/alarms.
* `ProtectionService.protects(Player,String)` and `infectionChance(Player,double)` are hooks; add real event behavior before advertising new hazard protection.
* `RegionalInfectionService.regionAt(Location)` returns the first matching configured cuboid. Bounded spread/decay affects zombie-bite infection probability only, not supply pressure, zombie spawning, or events.

## Persistence and PDC identities

* Infection state remains `plugins/ZombieApocalypse/infections.dat`, schema 1 (`UUID|STAGE|infectedAtMillis|criticalAtMillis`). Keep the `.bak` and never delete it during a failed migration. Invalid data leaves infection loading disabled rather than replacing the source.
* Conditions use `survival-conditions.dat`, schema 1 (`UUID|thirst|stamina`), with a `.bak`. Invalid data leaves the condition manager unloaded rather than replacing the source.
* Portable storage uses `storages.yml`, schema 1, keyed by storage UUID; it stores owner, item ID, access mode, inventory size, and serialized item slots. Invalid files disable storage without overwriting the source.
* Player/item PDC identifiers currently include `item_id`, perishable `created_at`, `storage_id`, `infection_immunity_until`, `survival_waypoints`, and per-item treatment cooldown keys.
* No SQLite database/schema migration was introduced; each new feature currently has a separate flat-file or player-PDC storage format.
* Regional infection levels use `regional-infection.dat` (schema 1), with validated IDs, atomic replacement, and a backup.

## Config and command boundaries

Configuration lives in `src/main/resources/config.yml`; valid reloads merge missing bundled defaults. Item definitions live under `items`, custom recipes under `recipes`, trader offers under `trader.offers`, radio policy under `radio`, and survival tuning under `survival`. Admin provisioning is `/zombie item`; player-facing commands are `/zrecipe`, `/zstorage`, and `/ztrade`.

Portable storage is deliberately not a placed block. It is independent of WorldGuard regions because it opens an item-backed inventory and does not read/write world containers. Security, CCTV, power, events, NPC/quests, dashboard, protection-plugin adapters, dynamic lights, resource-pack models, team implementation, and external economy implementation remain absent. Storage stock is not persisted. Keep these boundaries explicit when extending the project.