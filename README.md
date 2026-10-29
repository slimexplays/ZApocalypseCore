# ZApocalypseCore

**Made By @slimexplays | SlimeX Development | https://dsc.gg/slimexdev**

This Maven source project operates in an already-existing Paper world. It does not generate worlds, terrain, structures, maps, or lobbies. The Phase 2 work here is a **partial implementation**, not a claim that every requirement in the supplied Phase 2 prompt is complete.

## Target and build status

The Maven target is Paper API `26.3.build.222-stable`, Java release 25, and XSeries `13.3.3`; `plugin.yml` declares API `26.3`. Paper's version endpoint identifies Minecraft 26.3 as supported, Java 25 as its minimum, and build 222 as stable. The corresponding Paper API Maven artifact URL responded successfully. This verifies target availability, not that this project has compiled or run on that server. The existing XSeries dependency was retained, but its 26.3 compatibility has not been build-verified. The separate `Ultra_Advanced_Zombie_Apocalypse_Feature_Blueprint_26.3.txt` was not present in the workspace; implementation review is limited to the supplied prompt and available source/handoffs.

A successful compile, automated test, or live Paper integration test has not been confirmed. An initial verifier attempt failed; a source correction was applied, but no successful follow-up result is available. Maven packaging is configured to produce `ZApocalypseCore-<version>.jar` (with XSeries bundled) and `ZApocalypseCore-<version>-resource-pack.zip`; neither artifact has been build-verified yet. To build in Kodari, click **Compile** on desktop, or **⋮ → Compile Project** on phone; click **Compile** in the dialog, then download the JAR and resource-pack ZIP from the successful build artifacts. Do not deploy until the resulting build passes on the target runtime.

## Pterodactyl installation

1. Use Paper 26.3 (stable build 222) with Java 25 or newer; the plugin has not yet been tested on a live server.
2. Compile this project in Kodari using the steps above and download the resulting JAR.
3. Upload the JAR to the server's `plugins` directory and restart the server.
4. Back up the plugin data folder before upgrading, then review `plugins/ZApocalypseCore/config.yml`. On first start, files from the former plugin data folders are copied only when no same-named destination file exists; old folders are left untouched.

No external plugin is required. No WorldGuard adapter is bundled. The new supply storage is an interaction-based inventory item, not a block container; it does not access or modify protected world blocks.

## Commands and permissions

* `/zombie info`, `/zombie about`, `/zombie version` — branded plugin information, attribution, and detected version details.
* `/zombie help`, `/zombie status`, `/zombie event status` — help/status.
* `/zombie event list|start <id>|stop` — inspect and administer configured dynamic events (`zombie.admin` required to start/stop). Auto-start is off by default.
* `/zombie reload` — validates a candidate configuration, copies in missing bundled defaults, and reloads recipe/trader/region definitions only when valid.
* `/zombie spawn <variant> [amount]` — admin spawn; `revenant` remains reserved for infection-caused death.
* `/zombie infection <player> status|clear|set <healthy|infected|critical>` and `/zombie cure <player>` — admin infection controls.
* `/zombie item <online-player> <item-id> [amount]` — admin issue configured custom items for testing/provisioning.
* `/zombie region list|status <id>|set <id> <0-100>|clear <id>` — inspect and administer configured regional infection (`zombie.admin`).
* `/zombie device place <camera|generator|battery|cable|connector|breaker|charger|monitor|terminal|laptop> <id>`, `/zombie device list|remove <id>`, `/zombie power status`, and `/zombie cctv list|view|log|exit` — device and CCTV controls. Device placement/removal requires `zombie.admin`; CCTV access requires `zombie.cctv`; breaker operation requires `zombie.power.manage`.
* `/zphone` — persistent phone GUI; `msg`, `history`, `contact`, `group`, `block`, `mute`, `sos`, `settings`, `battery`, and `recharge` subcommands are available. Each player needs their owner-bound phone; `components` recharge it by default.
* `/zsecurity lock <id> <PIN>` while looking at a door; owner commands `allow`, `revoke`, `keycard`, `setpin`, `pin`, `status`, and `remove` manage powered doors. Doors fail closed without power. `zombie.security.manage` allows door registration; admin permissions manage any door.
* `/zombie quest reputation` displays persistent faction reputation; configured quest claims award the configured faction points once.
* `/zrecipe list`, `/zrecipe search <term>`, `/zrecipe <item-or-recipe>`, `/zrecipe gui [search]`, `/zrecipe craft <recipe-id>` — browse and craft registered custom-item recipes.
* `/zstorage info` and `/zstorage mode <private|team|public>` — configure the held storage item's access mode.
* `/ztrade list|buy <item> [amount]|sell <item> [amount]` — temporary command-based trader test interface.

`zombie.admin` (default operators) gates administration. `zombie.use` (default everyone) gates public recipe/storage/trade commands. `/zombie` also has alias `/za`.

## Implemented survival behavior

* Thirst and stamina are independently switchable and disabled by default. They update at a bounded configured interval, persist in `survival-conditions.dat` on a dedicated file worker, and display through the action bar. Sprinting drains stamina; depletion prevents sprinting. Vanilla hunger is not replaced. Thirst damage/effects are configurable and default to no penalty.
* Custom food/water uses plugin PDC IDs, not names/lore. Canned food and ration packs restore vanilla food/saturation. Water hydrates when thirst is enabled; a successful drink returns a custom refillable bottle. Contaminated water can apply its configured effect. Optional spoilage uses a PDC creation timestamp.
* Configure water sources as `world,x,y,z,clean` or `world,x,y,z,contaminated` in `survival.water-points.locations`. Right-click a configured block with a refillable bottle to fill it. Sneak-right-click contaminated water while carrying a custom `water_filter` to purify one bottle.
* Bandages/medkits heal, infection-cure items clear infection, antidotes reduce an infection stage, and vaccine items grant restart-persistent immunity in player PDC. Effects and per-item cooldowns are configurable. A cure before infection data finishes loading is not consumed; retry after load.
* Equipped custom gas-mask/hazmat pieces reduce infection chance; gas masks/hazmat helmets block the Phase 1 toxic-zombie poison effect. Generic protection checks are exposed for future hazard integrations.
* Optional Acid Rain runs only during vanilla storms in enabled worlds and is disabled by default. It checks the highest block in a player's column for overhead shelter, uses the existing `acid` armor-protection setting, applies configured damage at bounded intervals, and can optionally attempt infection. It does not change weather or blocks; its configured duration limits effects for each continuous storm. No World Director scheduling is implemented.
* Optional toxic fog (`toxic-fog.enabled`) affects players in configured regional-infection categories, applies configured effects/damage/infection chance, and is blocked by equipped items with `protection.toxic.enabled: true`. Temperature, wetness and general visibility systems are not implemented.
* Repair kits act on a damaged item held in the offhand and consume configured custom repair materials. Radio items broadcast configured alerts globally or within a radius and emit a Phase 1 noise investigation. Team-only radio requires a registered `TeamResolver`; without one, non-sender recipients are denied.
* GPS/map items show coordinates or list waypoints. A waypoint marker right-click stores a persistent player waypoint at the clicked block (or current position when used in air). Player PDC currently stores up to 20 markers.
* Registered custom recipes use PDC-identified custom ingredients. `/zrecipe craft` validates all ingredient counts before consuming any, then issues the PDC-tagged result. The GUI is a browser, not a vanilla crafting-table integration.
* Twenty configured firearms cover pistols, revolvers, SMGs, rifles, shotguns, an LMG, and a heavy rifle. Server-side hitscan checks blocks before entities and only damages zombies; custom zombie variants can have separate damage multipliers. Magazine, condition, and serial values are stored in item PDC. Sneak-right-click reloads after a configured delay; ammo is consumed from PDC-identified inventory items only after reload completes. SEMI and BURST fire on right-click; AUTO also fires on server-received arm swings. Continuous held-trigger input is not available through this Bukkit event path.
* Gun and ammo crafting recipes use the existing `/zrecipe craft` flow, and configured gun/ammo items participate in the existing zombie drop table. Firearm use requires `zombie.use`; `/zombie item` remains admin-gated. Guns use custom item-model keys and the included resource pack's seven pixel-art weapon families; firearm sounds still use the configured vanilla fallbacks.
* Optional loot search targets configured existing chest/barrel blocks. Searching takes a configurable time, cancels if the player leaves the area, and selects a weighted rarity/reward; a cooldown timestamp is stored in the tile's PDC. It is disabled by default and does not replace blocks or generate containers. If WorldGuard is installed, loot search fails closed because this project has no WorldGuard adapter; already-cancelled interactions are respected.
* Storage items open persistent portable inventories stored in `storages.yml` (`schema: 1`). First use claims a private storage; owners can set private/team/public access. Team mode is fail-closed until a `StorageTeamResolver` is registered. Duplicate storage-item identities point to the same inventory rather than duplicating its contents.
* The trader has configured offers, in-memory stock/restock behavior, a command test interface, and an `EconomyAdapter` API. Trading is disabled by default and no Vault or other economy implementation is bundled. Stock is not persisted across restart.

### Configuration additions

* `survival.enabled`, `survival.update-interval-ticks`, `survival.save-interval-ticks`, `survival.display.enabled`.
* `survival.thirst.*`: `enabled`, `max`, `starting`, `drain-per-minute`, `sprint-drain-per-minute`, `low-threshold`, `effect-threshold`, `effect`, `effect-amplifier`, `empty-damage`.
* `survival.stamina.*`: `enabled`, `max`, `starting`, `sprint-drain-per-minute`, `regeneration-per-minute`, `reset-on-death`.
* `survival.food-spoilage.enabled/seconds`, `survival.water-points.enabled/locations`, `survival.water-purification.enabled`.
* Per-item settings are under `items.<id>`: material/name/rarity and supported behavior fields such as `heal`, `food-level`, `saturation`, `hydration`, `cure`, `infection-reduction`, `immunity-seconds`, `cooldown-seconds`, `effect`, `contamination-effect`, `repair-durability`, `repair-cost-item`, `radio-alert`, `navigation-action`, `infection-resistance`, nested `protection`, `storage-size`, and `storage-access`.
* `recipes.<recipe-id>` defines `result`, `amount`, display-only `station`, and custom-ID `ingredients`.
* `firearms.enabled`, `firearms.max-range-blocks`, `firearms.noise-radius`, and `firearms.zombie-damage-multipliers.*` control firearm behavior. Each `firearms.guns.<id>` entry defines ammo item ID, magazine, damage, mode, delay, burst count, reload delay, range, spread, recoil, pellets, condition durability, and XSeries-matched sounds. Firearms/ammunition are custom PDC items under `items.<id>` and use the normal recipe and zombie loot configuration.
* `loot-search.enabled`, `duration-seconds`, `cooldown-seconds`, `max-distance-blocks`, `containers`, `rarity-weights`, and `rewards.<item-id>` configure optional container scavenging.
* `radio.*` configures range, noise radius, radio requirement, team-only mode, and alert text. `trader.enabled` and `trader.offers.<item-id>` configure prices, stock, and restock intervals.
* `acid-rain.enabled`, `damage-interval-seconds`, `event-duration-seconds`, `exposure-damage`, and `infection-chance-per-exposure` configure the optional storm hazard. Set `items.<id>.protection.acid.enabled: true` on a custom armor item to make wearing it protect against exposure; the existing `acid_resistant_boots` item uses this setting. The feature does not generate rain or damage blocks.
* `phone.*` configures battery capacity/drain/message costs/recharge and history limits. `devices.power.*` configures generator loads/fuel, battery capacity/charging, cable power radius and phone charging; `devices.cctv.motion-radius-blocks` controls motion logging. All device state is persisted in `devices.yml`.
* `security.*` configures powered secure doors. `/zsecurity lock` stores salted PIN hashes; keycards are owner-bound PDC items. `events.definitions.<id>.*` sets event duration/cooldown; `events.auto-start` is optional and defaults off. Event active state and cooldowns persist in `events.yml`.
* `factions.definitions` declares faction names; quest definitions can set `faction` and `reputation-reward`. Reputation is persisted alongside quest progress in `quests.yml`.
* `regional-infection.*` configures cuboid regions, deterministic spread/decay, category thresholds, bite-chance multipliers, and transition notices. Each region requires `world`, `min-x/max-x`, `min-y/max-y`, `min-z/max-z`, and `starting-level`; coordinates only describe locations in existing worlds.

## Persistence and recovery

Regional infection levels use `regional-infection.dat` (schema 1) with a `.bak` backup. Configure region IDs and cuboid bounds; only IDs still present in the config are loaded. Malformed regional-infection data is left untouched and keeps that manager unloaded.

Phone conversations/groups and battery use `phones.yml`; powered devices/camera motion logs use `devices.yml`; secure-door owners, access lists and keycard serials use `security.yml`; active event state/cooldowns use `events.yml`. These files are saved by their owning services; back up the entire plugin data folder before upgrades. Quest reputation is additive in `quests.yml`.

Keep backups of the entire `plugins/ZApocalypseCore` directory before upgrades. Phase 1 infection data remains in `infections.dat` with its `.bak`; new condition data uses `survival-conditions.dat` and its `.bak`; portable storage uses `storages.yml` and its `.bak`. These are independent schema-1 stores. A malformed/unsupported infection or condition store leaves its source untouched and keeps that manager unloaded; a malformed storage file disables storage rather than replacing the file. Player-PDC immunity, item IDs, item timestamps, storage IDs, and waypoints persist with Minecraft player/item data. The first-start folder migration copies missing files and deliberately leaves legacy folders intact.

## Not complete / known limitations

Regional infection is limited to configured cuboids, bounded spread/decay, persistence, admin management, player notifications, and a zombie-bite infection-chance multiplier; it does not alter supply availability, zombie spawn pressure, or events. Server-side firearms, opt-in scavenging, and a custom firearm resource pack are present, but security devices, CCTV, power, World Director/events, survivor NPCs/factions/quests, dashboard, and protection-plugin adapters remain unimplemented.

This is still a partial implementation. Temperature, wetness, general visibility, faction diplomacy, survivor recruitment, campaign/story progression/endings, trader tiers/reputation gates, alarms, electric fences, a secure PIN-terminal GUI, complete safehouse/base ownership, advanced group-level horde coordination, and block breaking remain unimplemented. Safe horde spawning avoids unloaded chunks and zombies do not break blocks. Events run through a persisted engine, but auto-start defaults off and event gameplay/restart cleanup are untested. Acid Rain remains a bounded vanilla-storm hazard. Five firearm texture PNGs were regenerated, but `sniper.png` and `heavy.png` remain invalid; client rendering, resource-pack ZIP generation, Maven packaging, and live tests are unverified. No JAR or source ZIP has been generated in this session.

The phase handoffs describe earlier partial increments; this README and `TEST_REPORT.md` contain the current target and implementation status. Phase 3 extension points are summarized in `PHASE_3_HANDOFF.md`.