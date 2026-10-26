# Changelog

**Ultra Advanced Zombie Apocalypse — Made By @slimexplays | SlimeX Development | https://dsc.gg/slimexdev**

## Branding and attribution update

- Added official plugin author, community URL, branded information/about/version commands, and startup attribution.
- Added branded project, configuration, changelog, and release documentation metadata.

## Unreleased — partial regional infection, firearm, and scavenging systems

- Added configurable cuboid regional infection levels for existing worlds, with clean, quarantined, contaminated, and high-risk categories.
- Added bounded scheduled spread/decay, region-transition notifications, and configurable zombie-bite infection multipliers.
- Added `/zombie region list|status|set|clear`, guarded by the existing `zombie.admin` permission.
- Added schema-1 asynchronous flat-file persistence in `regional-infection.dat` with backup and atomic replacement, plus configuration validation.
- Preserved the existing Maven project and Phase 1 / partial Phase 2 modules.
- Added 20 configurable server-side firearms with PDC-backed magazines, condition and per-item serials, delayed reloads, SEMI/AUTO/BURST triggers, spread/recoil, block-aware hitscan against zombies, variant damage multipliers, and gunfire noise.
- Added custom-ID ammunition, firearm/ammo recipes, zombie-drop entries, and firearm configuration validation.
- Added optional timed container searches with rarity-weighted rewards and persistent per-container cooldowns. The feature is disabled by default and fails closed when WorldGuard is installed without an adapter.
- Configured Maven for Paper API `26.3.build.222-stable` and Java 25. Paper target availability was checked; project compilation and runtime remain unverified.
- This is not a complete Phase 3 release. Security devices, CCTV, power, events, NPC/quests, dashboard, protection-plugin adapters, custom visual assets, and full integration testing remain outstanding.