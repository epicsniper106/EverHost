# Forge 1.20.1 release validation

- The Java 17 build and all 50 automated tests passed. The release JAR retains the `everhost` mod ID, the existing Forge asset name, and GitHub updater metadata for this repository.
- In a real Forge client and dedicated test server, a custom required JAR downloaded from the host. Minecraft closed after the installer signaled readiness, CurseForge reopened, and the next launch verified the installed file. Replacing a required version archived its original JAR.
- A separate optional offer appeared once the required files matched. Declining reconnected and joined the world. The saved choice survived a client restart. A required version mismatch cleared that choice. Accepting the next optional offer installed its custom JAR, and the restarted client loaded it and joined the world.
- CurseForge fallback was off by default. A host transfer failure left it off, while explicitly enabling fallback retrieved a known exact CurseForge file. An invalid token or altered file failed verification.
- A Beta 12 Forge client joined the new dedicated server with the optional mod absent. The compatibility check covered the previous protocol 3 client and Forge 47.4.10.
- The production Forge server loaded the SPIXY compatibility coremod, passed datapack loading, and reached `Done`. The existing SPIXY JAR hash matched its backup. The ModernFix `dynamic_dfu` compatibility fix remained active. The server completed a countdown, saved its dimensions, and came back online on the rebuilt JAR.
- Playit reported online and answered a Minecraft status request through its public TCP tunnel to the IPv4 game listener. Two ownership checks with no saved PID adopted the same running Playit process. The Windows named pipe remained in use. A custom mod transferred through the live public mod delivery tunnel and passed SHA-256 verification with fallback off.
- The updater reached the live GitHub API with the Windows certificate store while leaving Java's global trust configuration alone. Existing installer tests covered readiness, checksum validation, backup and replacement on Windows.
- The full 169-mod CurseForge client reached the main menu and joined the live world through the permanent address after declining an optional mod. On the test PC, the profile used Windows certificate roots for Minecraft authentication. Essential displayed a separate sign-in warning that could be dismissed before joining.

These checks cover Forge 1.20.1 and the tested modpacks. Fabric and external players' PCs were outside the release test scope.
