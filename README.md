# SkySpy LAN 0.3.0

SkySpy is a consent-based device diagnostics system delivered as two installable applications. It does not use a website, browser dashboard, or PWA.

## Applications

1. **SkySpy Control for Windows** — native C# / WPF desktop application, distributed as a self-contained Windows x64 executable.
2. **SkySpy Companion for Android** — Kotlin Android application installed and approved by the device owner.

The Windows application hosts the local API inside its own process, displays devices enrolled by their owners, and refreshes the inventory every six seconds. There is no separate Node.js process, web server, browser window, WebView, or website.

## Windows application

Download the `skyspy-control-windows-x64` GitHub Actions artifact and extract it. Run `SkySpy.Control.exe`.

The executable is published self-contained for Windows x64 and includes the .NET and ASP.NET Core runtime. No Node.js or .NET installation is required on the target PC.

Device records are stored under:

```text
%LOCALAPPDATA%\\SkySpy\\data\\devices.json
```

The local API runs inside the desktop process on port `8787`. It binds only to loopback and private IPv4 network interfaces. The service stops when SkySpy Control closes. Allow the application on a **private network** in Windows Firewall when prompted.

## Connect an Android Companion

1. Start `SkySpy.Control.exe` on the Windows PC.
2. Connect the PC and Android phone to the same trusted Wi-Fi or private LAN.
3. In SkySpy Control, copy the displayed Companion address (for example `http://192.168.1.10:8787`).
4. Open SkySpy Companion on the Android phone and enter the address once.
5. Review the diagnostics it shares and approve the one-time enrollment.
6. The Android device appears in the Windows application. Later launches reuse the saved installation identity and reconnect automatically.

The Companion shares only manufacturer/model, Android version/security patch, battery/charging state, app version, and heartbeat time. Its visible Android foreground service sends a heartbeat every 30 seconds while Android allows it to run. Force-stop, vendor battery policies, or system restrictions can interrupt background work; reopening Companion starts reconnection again.

## Access boundaries

- The Windows desktop app reads the inventory directly from its in-process data store. No operator password or browser session is needed on that PC.
- The embedded API exposes only the Companion registration, heartbeat, unpair, and health routes. It does not expose a remote device-inventory endpoint.
- Initial device registration requires the owner to explicitly approve the diagnostics notice in Companion.
- Companion heartbeats and unpair requests require the device-specific credential created during enrollment.
- The embedded API listens only on loopback and detected private IPv4 interfaces, and rejects browser-origin requests.
- Plain HTTP is restricted by Companion to localhost/private IPv4 networks. The local desktop API is intended for trusted private LAN use only.

## Build

Requires the .NET 8 SDK on Windows:

```powershell
dotnet test apps/control-windows.Tests/SkySpy.Control.Tests.csproj -c Release
dotnet publish apps/control-windows/SkySpy.Control.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

Build the Android Companion by opening `apps/companion` in Android Studio, or run:

```bash
cd apps/companion
gradle assembleDebug
```

## GitHub Actions

The workflow tests and builds the native Windows desktop application and the Android debug APK. It uploads:
- `skyspy-control-windows-x64`
- `skyspy-companion-debug-apk`

There is no PWA, static site, website, browser dashboard, or Node.js artifact.

## Current scope

- [x] Native Windows WPF Control application
- [x] Embedded ASP.NET Core API running in the desktop process
- [x] Self-contained Windows x64 executable
- [x] Automatic local API startup and shutdown with the desktop app
- [x] Local inventory with online/offline status, battery, Android version, and last heartbeat
- [x] Copyable private-LAN connection address
- [x] One-time owner-approved registration without pairing codes
- [x] Companion reconnects using its saved installation identity
- [x] Visible Companion foreground service and 30-second heartbeat
- [x] No website, web app, PWA, embedded browser, or web dashboard
- [ ] WebRTC screen sharing with per-session Android MediaProjection consent
- [ ] Consent-based file transfer using Android's system file picker
- [ ] Production TLS deployment for use beyond a private LAN

## Consent and safety

The Companion remains visible, has a launcher icon, and describes the diagnostics it shares. The owner can unpair from inside Companion. SkySpy does not include SMS/OTP access, contacts access, notification capture, keylogging, credential collection, hidden recording, phishing overlays, silent installation, stealth persistence, remote shell, or Android security-control bypass.
