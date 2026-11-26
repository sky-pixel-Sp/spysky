# SkySpy LAN

SkySpy LAN is a **consent-based remote device management** project with two separate applications:

1. **SkySpy Control** — an installable web dashboard (PWA) for the operator.
2. **SkySpy Companion** — an Android application that the device owner installs and chooses to connect.

A small API service handles first-run admin setup, authentication, device registration, and heartbeats. Device registration does not require a pairing code: the owner reviews the diagnostics notice and taps **Connect this device** in Companion.

## Project layout

```text
apps/
  control/       React + TypeScript dashboard (PWA)
  companion/     Kotlin Android Companion
services/
  api/           Node.js API and local data store
.github/
  workflows/     CI builds both applications
```

## No environment-variable setup

The API uses built-in defaults. You do not need to configure `SKYSPY_ADMIN_TOKEN`, `SKYSPY_DATA_DIR`, `SKYSPY_PORT`, or `SKYSPY_ALLOWED_ORIGIN`.

- API port: `8787`
- Local data directory: `./data`
- Device records: `./data/devices.json`
- Admin password hash: `./data/admin-auth.json`

The data directory is created automatically when the API starts and saves its first record.

## Run the API

Install Node.js 22 or newer, then:

```bash
cd services/api
npm start
```

No environment variables or API token need to be entered.

The API package includes the compiled Control PWA in `services/api/public` and serves it from the same port. Start the service, then open `http://localhost:8787` on the host computer. No separate web server is required.

The API listens on port 8787 on the host's network interfaces. Keep it on a trusted LAN during local use. For internet access, place it behind an HTTPS reverse proxy or deploy it with HTTPS; do not expose the plain HTTP API directly to the public internet.

## First-time Control setup

1. Start the API + Control package on the computer that will host SkySpy.
2. Open `http://localhost:8787` on that same computer.
3. Create an admin password of at least 12 characters. Initial admin creation is restricted to a loopback request from the server computer.
4. Sign in with that password.
5. On another computer or phone on the same LAN, open `http://<server-lan-ip>:8787` and sign in with the admin password.

The Control UI uses the current server origin automatically, so LAN clients do not need to edit an API URL. The admin password is salted and hashed by the API. Control receives a short-lived session token after login; the operator does not need to manage or paste an API token. Sessions last up to 12 hours and are cleared when the API restarts.

The admin password is salted and hashed by the API. Control receives a short-lived session token after login; the operator does not need to manage or paste an API token. Sessions last up to 12 hours and are cleared when the API restarts.

## Connect a Companion device — no pairing code

1. Open SkySpy Companion on the phone.
2. Enter the SkySpy server address once. For LAN, use the private IPv4 address of the computer running SkySpy, for example `http://192.168.1.10:8787`.
3. Read the diagnostics list and tick the explicit consent checkbox.
4. Tap **Connect this device**.
5. The registered device appears automatically in Control. The dashboard refreshes its list every six seconds.

The Companion does not request an operator password, pairing code, or broad Android permissions. It shares only manufacturer/model, Android version/security patch, battery/charging state, app version, and heartbeat time. The heartbeat runs only while the Companion screen is open in this milestone.

If Companion is installed on a different network, use an HTTPS API address. Plain HTTP is intentionally blocked by the app for public addresses.

## Build locally

### Control dashboard

```bash
cd apps/control
npm install
npm run build
npm run dev
```

### Android Companion

Open `apps/companion` in Android Studio and build the `app` debug variant, or run:

```bash
cd apps/companion
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions

The workflow builds and tests:
- API authentication, consent-gated registration, inventory, token rotation, heartbeat, and unpair
- SkySpy Control TypeScript production build
- SkySpy Companion debug APK
- API service archive

A successful run uploads separate artifacts named `skyspy-control-web`, `skyspy-companion-debug-apk`, and `skyspy-api-service`. The API service artifact includes the compiled Control PWA, so it can host the dashboard itself.

## Current scope

- [x] Separate Control dashboard and Android Companion
- [x] Default API configuration without environment variables
- [x] First-run admin password setup and login
- [x] Owner-approved device registration without pairing codes
- [x] Device inventory, details, online/offline status, heartbeat, and owner unpair
- [x] LAN address support; HTTPS required for public internet endpoints
- [ ] WebRTC P2P screen sharing with per-session Android MediaProjection consent
- [ ] Consent-based file transfer using Android's system file picker
- [ ] Production deployment hardening, TLS configuration, and security review

## Consent and safety model

The Companion stays visible, has a launcher icon, and shows what data it shares. The owner can disconnect at any time. This project does not include SMS/OTP access, contacts access, call logs, notification capture, keylogging, credential collection, hidden recording, phishing overlays, silent installation, stealth persistence, remote shell, or Android security-control bypass.

See [SECURITY.md](SECURITY.md).
