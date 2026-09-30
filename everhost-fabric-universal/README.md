# EverHost 2

EverHost 2 adds a `Server` button to Minecraft's main menu and keeps the entire hosting workflow inside the game. The dashboard scans local CurseForge profiles, selects a world, reviews server and required client mods, syncs supported plugins, configures the server, and exposes the live console.

The hosted server runs as a hidden Java process so it can remain online after the Minecraft client closes. No separate dashboard application or executable is used.

## In-game pages

- Overview: start, stop, restart, backup, address, local join, players, and uptime.
- Profiles: select and rescan supported CurseForge profiles.
- Mods: cycle each detected jar through Off, Server, or Required.
- Plugins: sync the profile's plugin folder and verify that a bridge is selected.
- Hosting, World, Performance, Access, and Safety: server settings with hover descriptions.
- Integrations: Essential notifications, e4mc, and diagnostics.
- Console: live server output and command input.

## Compatibility note

Fabric and Forge do not natively load Bukkit or Paper plugins. Plugin jars are only active when the selected profile includes a compatible bridge for that Minecraft version. EverHost labels this clearly instead of claiming unsupported plugins are loaded.
