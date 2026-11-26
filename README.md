# SkySpy LAN

SkySpy LAN is a **consent-based remote device management** project with two separate applications:

1. **SkySpy Control** — an installable web dashboard (PWA-ready) for an operator.
2. **SkySpy Companion** — an Android app that a device owner installs and explicitly pairs.

A small API service handles pairing, device registration, and heartbeats. The first milestone focuses on transparent device enrollment and inventory. Remote screen sharing and file transfer are later, separately consented features; they are not claimed as implemented in this milestone.

## Project layout

```text
apps/
  control/       React + TypeScript dashboard
  companion/     Kotlin Android companion app
services/
  api/           Node.js API for pairing and device status
.github/
  workflows/     CI builds both applications
```

## Consent and safety model

- The Companion has a visible app icon and persistent, user-readable status.
- Pairing is initiated by an operator and completed by entering a short-lived code on the Companion.
- Device owners can unpair by clearing the app's pairing data or uninstalling the app.
- The first milestone collects only device model, Android version/security patch, battery level/charging state, and heartbeat time.
- It does not request SMS, contacts, call logs, accessibility, notification access, microphone, camera, location, or broad storage permissions.
- No covert recording, credential/OTP collection, keylogging, phishing overlays, stealth persistence, remote shell, or security-control bypass is included.
- Screen sharing, when implemented, must use Android's visible MediaProjection consent flow and an explicit per-session action.
- File transfer, when implemented, must use Android's system file picker and explicit owner selection.

## Network model

- LAN mode: the Companion may connect to an API hosted on a private IPv4 address on the same trusted network.
- Internet mode: use a public HTTPS endpoint. Do not expose the API over public plain HTTP.
- This milestone uses authenticated HTTPS/HTTP API requests and polling. **WebRTC P2P, STUN, and TURN are not implemented yet.** A later milestone can add consent-gated WebRTC screen sharing with TURN fallback.
- Never publish operator tokens in frontend source, Git history, screenshots, or issue reports. The operator enters the token in the Control dashboard at runtime.

## API configuration

Run the API with Node.js 26 LTS or another supported modern Node.js LTS release.

Required environment variables:

- `SKYSPY_ADMIN_TOKEN`: a random secret of at least 32 characters.
- `SKYSPY_DATA_DIR`: optional data directory; defaults to `./data`.
- `SKYSPY_PORT`: optional port; defaults to `8787`.
- `SKYSPY_ALLOWED_ORIGIN`: optional browser origin; defaults to `http://localhost:5173`.

Example:

```bash
cd services/api
SKYSPY_ADMIN_TOKEN="replace-with-a-long-random-secret" node src/server.mjs
```

The API listens on port 8787. For LAN use, bind it to a private network and firewall it to trusted clients. For internet access, put it behind a TLS reverse proxy or an HTTPS deployment platform.

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

The workflow in `.github/workflows/build.yml` builds the Control dashboard and Android Companion, checks the API JavaScript syntax, and uploads the outputs as one workflow artifact. A green workflow is the build verification; a repository commit by itself is not proof that an APK compiled.

## Status

- [x] Separate Control and Companion application boundaries
- [x] Pairing and device inventory API
- [x] Device heartbeat/status
- [x] Control dashboard for pairing and selecting a paired device
- [x] Android Companion pairing UI and minimal diagnostics
- [ ] WebRTC P2P screen sharing (requires a visible, per-session Android MediaProjection approval)
- [ ] Consent-based file transfer
- [ ] Production database, deployment, and end-to-end security review

See [SECURITY.md](SECURITY.md) before exposing any service to the internet.
