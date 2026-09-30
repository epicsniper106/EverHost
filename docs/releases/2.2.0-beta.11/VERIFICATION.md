# EverHost 1.0 Release Verification

Technical build: `2.2.0-beta.11`

Verified on September 30, 2026.

- Fabric shared test suite: 31 tests passed with zero failures, errors, or skips. Both the Minecraft 1.20.1 and 1.21.11 remapped release JARs built successfully.
- Forge 1.20.1: 34 tests passed with zero failures, errors, or skips, and the reobfuscated release JAR built successfully.
- A private test JAR was delivered end to end through a real Playit route using the new Minecraft-handshake transfer protocol. The remote client received the exact expected SHA-256 fingerprint.
- Each packaged JAR was inspected for the Beta 11 version, loader metadata, updater repository, expected asset name, and direct delivery classes.
- The Forge build was installed in the Epic shi CurseForge profile. Its hash matches the packaged release, and the previous Beta 10 JAR is preserved under `everhost/previous-mod-jars/2.2.0-beta.11-install`.
- The installed daemon, Forge server, Playit game route, and private mod route are running. EverHost reports the world online with zero players connected at the time of verification.

No profile mod was removed. No world, player, configuration, plugin, or Playit account data was reset.
