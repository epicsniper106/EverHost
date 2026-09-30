# EverHost 1.0 Release Notes

Technical build and updater tag: `2.2.0-beta.11`

## Exact mods directly from the host

- Added direct delivery of required mod JARs from the server owner's selected profile.
- Private and custom mods no longer need a CurseForge project or file ID to be eligible for automatic installation.
- Missing mods and incorrect versions both resolve to the exact SHA-256 fingerprint advertised by the server.
- Direct host delivery is preferred; the existing approved CurseForge download path remains available as a fallback.
- A separate Playit Minecraft route carries mod files, including on free Playit accounts.
- The route uses a fresh 256-bit bearer token on every server start and exposes only files present in the current required-mod manifest.
- Downloads are fully staged and verified before existing mod files are moved.
- Replaced JARs are archived, and a failed installation rolls changes back when possible.
- The post-install launch check reports success or a specific plan, download, checksum, identity, rollback, or relaunch error.
- Forge's required-mod login protocol is upgraded to version 3 to carry the direct source safely.
- The feature is included in Forge 1.20.1, Fabric 1.20.1, and Fabric 1.21.11.

Beta 11 also includes all Beta 10 updater and dashboard repairs.
