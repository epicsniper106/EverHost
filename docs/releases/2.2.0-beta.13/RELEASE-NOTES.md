# EverHost 1.0 Release - Beta 13

Required mods now download directly from the hosting PC by default, including custom JARs that are not published on CurseForge. This release is for **Forge 1.20.1** and keeps the existing mod ID and `EverHost.2.2.Forge.1.20.1.jar` filename.

## Joining a server

1. Join the server with EverHost installed. If required mods are missing or differ from the host's exact files, choose **Install required mods**.
2. EverHost checks that its installer is ready, closes Minecraft, downloads the required files, backs up replaced versions, and reopens CurseForge. Launch the profile again; EverHost independently checks the installed files.
3. Join the same server again. Once requirements are satisfied, a separate screen offers compatible optional mods with **Yes, install optional mods**, **No, join server**, and **Don't ask again until required mods change**.
4. The checked choice is remembered for that server and Minecraft profile. It survives restarts and resets when another required installation becomes necessary. Uncheck it to allow future offers.

Choosing No reconnects to the same server. Optional extras are never included in the required installation. Installing optional files uses the same exact-file verification and backup process.

## Host and download settings

- **Server > Mods > CurseForge fallback** is Off by default. This controls downloads into the current Minecraft profile. When enabled and saved, CurseForge is tried only if the host transfer fails and an exact approved CurseForge source is available. A different public version cannot substitute for a custom host file.
- Hosts keep their existing Off / Server / Required controls. A separate **Optional: On/Off** choice controls compatible extras offered to clients. Save and restart the hosted server to refresh its catalog. Dependencies needed by another shared mod may still be included.
- Client downloads are staged separately from the dedicated server's loaded mods. Sharing client-side extras does not install them into the dedicated server runtime.
- The host must be online with working mod delivery. Only the selected catalog's exact JARs are served; worlds, account files, and arbitrary PC files are not shared.

## Reliability and compatibility

- Fixed the download listener binding to IPv6 while its local tunnel destination uses IPv4.
- Custom host JARs use their exact SHA-256 fingerprint; stale CurseForge metadata no longer rejects an intentionally modified host file.
- Required client dependencies are included. Wrong-loader optional JARs and host-only tools are excluded from automatic offers.
- The mod installer confirms readiness before closing Minecraft and reports startup or transfer failures. Replacements are backed up and failed installations roll back.
- On Windows, the EverHost updater uses Windows' trusted certificate store when no custom Java trust store is configured. This fixes update checks and downloads failing with certificate-chain errors while retaining certificate and hostname validation.
- Preserved the compiled Java 17 EverHost updater, exact-profile Playit process adoption, valid Windows named pipe, and mandatory SPIXY compatibility-mod server syncing. Windows process discovery now fails safely if its ownership query does not complete.
- Previous protocol-3 EverHost clients can still join when their required files are present. The optional-mod screen requires this version. Both host and joining clients should update for the complete workflow.

Existing working updaters discover this GitHub release automatically at startup. Choose the restart/update action to install it. Clients still using the older broken source-file updater need the one-time manual repair described in Beta 12.

Only the verified Forge artifact is included. No Fabric binary, private profile data, local compatibility executable, world, or third-party mod is redistributed in this release. See `VALIDATION.md` for the release checks and limits.
