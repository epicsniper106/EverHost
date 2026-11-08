# Beta 12 verification

Artifact: `EverHost.2.2.Forge.1.20.1.jar`

SHA-256: `f39a748863fdcb3a24e31037e21f602c18bdd1057c817e1249dc6f5a42a33cdb`

Verified locally on Windows with Minecraft 1.20.1 / Forge 47.4.10, Java 17, and an existing 169-mod client profile, November 7-8, 2026.

| Check | Evidence |
| --- | --- |
| Build and automated checks | Gradle test/build passed: 39 tests, zero failures/errors. |
| Runtime-only installer | The compiled helper passed with Minecraft's bundled JRE and only `java.base`: successful replacement, corrupt staged file rejection, changed-original rejection, and Windows locked-target retry. The helper class in this release exactly matches that tested helper. |
| Actual local installation | Installed the exact release SHA at 01:39 CST. The installer logged a verified original backup and success; CurseForge reopened. |
| Client startup | Main menu rendered with all 169 client mods. |
| Dedicated-server startup | Daemon launched; dedicated server completed datapack loading and reached `Done` at 01:40:57 CST, listening on the configured local port. |
| SPIXY compatibility | The unchanged SPIXY compatibility JAR was copied into the runtime and logged both file-backed path validation patches plus the ModernFix dynamic_dfu/ETF compatibility patch. No `Pendragon_Sword.json.mcmeta` startup crash. |
| Playit | ONLINE with one owned agent. Public Minecraft status successfully forwarded to the local server. The test PC used its existing native-Windows-trust compatibility executable because its HTTPS-scanning certificate is not recognized by the default agent's embedded roots. No certificate verification or antivirus setting was disabled. |
| Process recovery | A fresh manager with no cached/saved PID adopted that exact live Playit process twice. The test reproduces Windows Java's missing arguments and a profile path with spaces. Automated checks reject another profile's agent. |
| Multiplayer | Exact release client joined through the public permanent hostname at 01:42 CST, rendered the world, and disconnected cleanly. Simple Voice Chat authenticated and validated its connection. Original gameplay mod JARs remained installed. |
| Existing hosting behavior | Profile selection, server status/console commands, persistent settings, controlled shutdown countdown, world saving, restart, required-mod selection and delivery configuration remain available. |

The known-good EverHost and SPIXY JARs were preserved before replacement. Source restoration includes the process-adoption and SPIXY-selection patches. The existing Windows named-pipe implementation is retained. SPIXY still does not rewrite registry/sentinel IDs; unrelated nonfatal mod data/animation warnings can remain in a modpack's log.

This is a targeted maintenance validation, not an exhaustive test of every modpack or every EverHost feature. The public release is Forge-only; no untested Fabric artifact is included. No worlds, player data, private tunnel configuration, credentials, local certificate files, or custom Playit binary are packaged.
