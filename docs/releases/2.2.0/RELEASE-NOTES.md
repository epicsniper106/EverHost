# EverHost 2.2.0

Forge 1.20.1 installation repair for large first-time mod downloads.

- A visible installer window shows which mod is downloading and the total progress after Minecraft closes. CurseForge reopens after the files have been verified and installed.
- A repeated install request detects the existing worker and reports its progress. It preserves that worker's pending plan and result.
- Back and Escape from the required-mod screen return to Multiplayer, including when the server's missing-mod list is too large for Minecraft's normal disconnect screen.
- Required installs include the selected SPIXY compatibility fix. A clean client receives the resource-path repair before loading the pack's content mods.
- Forge library containers with embedded mod metadata are recognized during dependency selection and installation verification. Required Kotlin for Forge libraries are included, and their installed outer files are checked correctly when the loaded mod lives inside the container.
- A received mod catalog remains available when Forge disconnects during the installed-file hash check. This preserves the download offer on large packs.
- Success results are written before reopening CurseForge. A client opened during an active installation skips stale results.

The release retains the compiled Windows updater, exact-profile Playit recovery, Windows named pipe, and SPIXY dedicated-server selection. Hosts should update and restart their server to refresh the required-mod catalog. See `VALIDATION.md` for the checked behavior.
