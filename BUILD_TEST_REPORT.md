# Build and test report — current partial implementation

## Environment and build status

* Build system: Maven, project file `pom.xml`; no `build.gradle` was created.
* Configured compiler release: Java 25.
* Configured Paper API: `26.3.build.222-stable`; configured XSeries: `13.3.3`.
* Paper's version endpoint reports Minecraft 26.3 as supported, Java 25 as its minimum, and build 222 as stable. The matching Paper API Maven artifact URL responded with HTTP 200. `plugin.yml` declares `api-version: '26.3'`.
* Target availability is verified; this does not verify that the project compiles or runs against that API.
* Existing XSeries `13.3.3` dependency resolution/compatibility with Paper 26.3: **not build-verified**.
* Compile attempt 1 failed; a source correction was applied, but no successful follow-up compile result is available yet.
* Automated tests executed: **none**. No test sources or test-run output are available.
* Live Paper/server integration, startup/shutdown, phone duplication and messaging, power-network/breaker outages, battery charging, monitor permissions/motion logs, secure-door PIN/keycards, event restart/cooldowns, quest reputation, zombie target-memory behavior, firearm rendering, and WorldGuard/protection behavior: **not run**. No compatible live server was available.
* Compiled JAR, complete source ZIP, and resource-pack ZIP: **not generated or verified**. No artifact link is available.

## Source changes made

The source retains its existing infection, firearm, quest, trader, NPC, team, device, CCTV, breach, and survival foundations. This revision adds a persistent phone/messaging service, finite power distribution and charging devices, monitor GUIs and camera motion logs, owner-bound powered secure doors, toxic fog, a configurable event engine, quest-based faction reputation, and zombie target-memory reacquisition. Five shared firearm texture PNGs were regenerated; two remain invalid. The latest source remains **without a successful compile or gameplay test**.

## Required verification before deployment

On a disposable server matching the actual Paper 26.3 / Java 25 target, first run the project's Maven verification. Then test the phone's unique PDC ownership, message/block/mute/offline flows and GUI inventory controls; power-network topology, load/fuel behavior, battery persistence, charger range, and CCTV shutdown on outages; door owner/PIN/keycard access, PIN throttling and event lockdown; motion logging and camera access permissions; each dynamic event's start/end/cooldown/restart cleanup; quest reputation claimed exactly once; zombie memory expiry/reacquisition without world/block changes; and firearm textures/model rendering and pack loading. Also test the existing firearm ammo/reload, infection, regional progression, survival, storage and protection workflows, plus WorldGuard denial, full inventories, corrupted YAML, and multiplayer concurrency. None of these checks has been run yet.

Do not report any of these tests as passed until actually run. The remaining unsupported or partial mechanics are listed in `README.md` and `PHASE_2_HANDOFF.md`; this source increment must not be called a completed Phase 2 release.