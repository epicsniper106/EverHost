# EverHost

EverHost provides persistent local Minecraft server hosting, Playit addresses, exact required-mod matching, guided updates, and in-game management for Fabric and Forge.

## Current release

**EverHost 1.0 Release — Forge maintenance build `2.2.0-beta.12`**

- Forge 1.20.1
- Fabric 1.20.1 and Fabric 1.21.11 remain on [Beta 11](https://github.com/epicsniper106/EverHost/releases/tag/v2.2.0-beta.11).
- Exact required-mod delivery from the hosting PC, including private and custom JARs
- Verified, reversible automatic installation with CurseForge relaunch

Download the build matching both your Minecraft version and mod loader from the [latest release](https://github.com/epicsniper106/EverHost/releases/latest).

Beta 12 fixes update installation on Minecraft's bundled Java runtime, preserves SPIXY server compatibility, and recovers the correct Playit process on Windows. If an older updater already fails with a Java/Windows error, replace the EverHost JAR manually once while Minecraft and the EverHost daemon are closed. See the [Beta 12 release notes](docs/releases/2.2.0-beta.12/RELEASE-NOTES.md).

## Source layout

- `everhost-routing-common`: shared daemon, updater, installer, Playit, and host-side mod delivery code.
- `everhost-forge-universal`: Forge 1.20.1 adapter and build.
- `everhost-fabric-universal`: Fabric 1.20.1 and 1.21.11 adapter and build.
- `docs/releases`: packaged release notes, verification records, and SHA-256 sums.

## Build

Each loader directory includes its Gradle wrapper. The shared source directory must remain beside both loader directories because their builds include it by relative path.
