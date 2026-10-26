# Resource-pack status

**Status: CREATED; NOT LIVE-TESTED.** The source pack is in `src/main/resources/resource-pack`, with seven original pixel-art weapon textures and corresponding item models for all 20 configured firearms. The item definitions use the `zapocalypsecore:firearms/<item-id>` keys assigned by the custom-item factory.

Firearms retain their configured vanilla base materials and XSeries-matched vanilla sounds, but clients using the pack see the custom weapon art. The pack targets the modern item-model resource format (pack format 46); it has not been loaded in a live Minecraft 26.3 client. The Phase 3 security devices, CCTV, power devices, and survivor NPCs are not implemented, so they have no visual mappings.

Maven packaging creates `ZApocalypseCore-<version>-resource-pack.zip` alongside the plugin JAR. Upload the ZIP through your server host's resource-pack delivery setup and provide its public URL to clients; this project does not host files or generate a download URL/hash. Vanilla icons remain as fallbacks when a client declines or cannot load the pack.