# EverHost 1.0 Release - Beta 12

This maintenance release fixes Windows update installation for **Forge 1.20.1** while retaining the existing EverHost identity, filename, hosting features, and compatibility patches.

## Fixed

- Update installation now uses a precompiled Java 17 helper. Minecraft's bundled runtime no longer needs the missing `jdk.compiler` module.
- Minecraft closes only after the helper confirms it is ready. Failed preparation reports an error while leaving the game open.
- The installer verifies the download and the original JAR, preserves a verified backup, waits for Windows file locks, and checks the installed file. It refuses to overwrite a JAR changed while it was waiting.
- Playit recovery adopts the existing agent belonging to the same profile even when the saved PID is absent. Windows process lookup preserves quoted profile paths containing spaces and never adopts another profile's agent.
- The valid Windows named-pipe path is preserved.
- `spixy_wow_resource_fix` stays included in the dedicated-server runtime. Existing file-backed resource sanitizing and ModernFix/ETF compatibility are preserved; the SPIXY mod itself is unchanged.

## Installing this repair

If your older updater already fails with a Windows/Java error, **replace the EverHost JAR manually once** with `EverHost.2.2.Forge.1.20.1.jar` from this release while Minecraft and the EverHost daemon are closed. Keep a backup. That broken older installer cannot install its own repair reliably. Subsequent updates use the corrected installer.

The existing startup update screen and GitHub release checks remain available. Update installation saves/stops the hosted server with its existing countdown, closes Minecraft, installs the verified JAR, and reopens CurseForge. Start the profile again after installation.

This release contains only the tested Forge 1.20.1 artifact. Existing Fabric releases remain available and are not relabeled as Beta 12.

## Local Playit compatibility option

For an owner who already has a reviewed Playit compatibility executable, `everhost/playit-agent-binary.properties` can select a file in `everhost/tools` using `binaryFile=<filename>.exe` and its exact `sha256=<64 hex characters>`. Missing or changed files are rejected. Without this local file, the existing checksum-verified official Playit download is used. No custom executable or local certificate/configuration is distributed in this release.

## Validation

- 39 automated checks passed, including a compiler-less runtime installer, failed helper startup, quoted Windows paths, cross-profile process rejection, and SPIXY server selection.
- The actual Minecraft Java runtime installed the repair successfully; corrupt downloads, a changed original, and a locked JAR were also tested.
- Client main-menu, dedicated-server startup/datapack loading, permanent Playit forwarding, and live recovery checks completed. See `VALIDATION.md` for the release-specific evidence and limits.
