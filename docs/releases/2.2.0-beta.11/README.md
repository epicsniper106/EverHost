# EverHost 1.0 Release

Technical build: `2.2.0-beta.11`

Beta 11 adds exact required-mod delivery from the hosting PC. When a joining player is missing a required mod, or has the wrong version, EverHost can now obtain the exact JAR selected by the server even when that JAR is private, custom, or unavailable on CurseForge.

## Choose one JAR

- `EverHost.2.2.Fabric.1.21.11.Full.jar`: Fabric on Minecraft 1.21.11.
- `EverHost.2.2.Fabric.1.20.1.jar`: Fabric on Minecraft 1.20.1.
- `EverHost.2.2.Forge.1.20.1.jar`: Forge on Minecraft 1.20.1.

Install only the JAR matching both the Minecraft version and loader.

## Required-mod installation

1. The server sends its exact required-mod manifest during login.
2. EverHost compares each mod ID and SHA-256 fingerprint with the joining player's profile.
3. `Automatic Install` closes Minecraft and stages every missing or mismatched JAR.
4. EverHost first requests the exact file from the hosting PC through a separate Playit route. CurseForge remains a fallback when an approved project and file are available.
5. Every download is checked for file size, SHA-256 fingerprint, and mod identity before the existing profile is changed.
6. Conflicting versions are preserved in `everhost/mod-archive`, the verified files are installed, and CurseForge is reopened.
7. On the next launch, EverHost checks the installed files and reports success or a specific error.

The direct file route uses a fresh private token on every server start and serves only JARs in that server's current required-mod manifest.

## Updating EverHost

Existing EverHost installations that use the `epicsniper106/EverHost` updater will discover Beta 11 from GitHub. The main-menu update screen provides `Restart Minecraft & Install Update`, which downloads the matching loader build, verifies it, replaces the old EverHost JAR after Minecraft closes, and reopens CurseForge.
