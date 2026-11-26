# SkySpy Android 0.4.0

SkySpy is a transparent, consent-based Android-to-Android device diagnostics and support project. Both the operator panel and the managed-device companion are native Android applications. There is no Windows panel, website, browser dashboard, PWA, or Node.js sidecar.

## Android applications

1. **SkySpy Controller** — installed on the owner's Android phone. It hosts the local LAN API, displays enrolled devices, shows diagnostic status, and can revoke an enrollment.
2. **SkySpy Companion** — installed on the Android phone being managed. Its owner reviews the diagnostics notice and approves the first connection.

## Quick setup

1. Install **SkySpy Controller** on the owner/operator Android phone.
2. Connect both Android phones to the same trusted private Wi-Fi network.
3. Open Controller and tap **Start local panel**. Keep its visible foreground-service notification active while devices are connected.
4. Copy the private-LAN address shown in Controller, such as `http://192.168.1.10:8787`.
5. Open Companion on the other phone, enter the address, read the diagnostics list, and explicitly approve the connection.
6. The device appears in Controller. Companion uses its saved device-specific session token for later heartbeats. The owner can remove the enrollment in Companion; the Controller operator can revoke it from the panel.

Both devices must remain on the same trusted private LAN. The Controller listens on port `8787` and accepts only private IPv4/loopback clients. It rejects requests containing a browser `Origin` header. The local HTTP endpoint is not designed for public Wi-Fi, port forwarding, or internet exposure.

## Data shared by Companion

- Manufacturer and model
- Android version and security patch level
- Battery percentage and charging state
- Companion app version
- Heartbeat time / online status

The current build does not request camera, microphone, location, contacts, SMS, call-log, notification-listener, accessibility, or storage permissions. Android's notification permission is requested so users can see the foreground-service status; the diagnostics payload itself does not need a dangerous runtime permission.

## What Controller can do in this build

- Show enrolled Android devices and their online/offline status.
- View approved device model, Android version, battery and last heartbeat.
- Refresh the device list from the local store.
- Revoke an enrollment so its stored session token stops working.

**This is not full remote touch control.** Remote screen viewing, touch injection, file browsing, camera/microphone access, and arbitrary remote commands are not implemented. A future screen-support feature must be started on the Companion phone and use Android's system MediaProjection consent UI for each session.

## Build

Requires Java 17 and Gradle 8.14.4.

Build Controller:

```bash
cd apps/controller
gradle --no-daemon assembleDebug
```

Build Companion:

```bash
cd apps/companion
gradle --no-daemon assembleDebug
```

The debug APKs are generated at:

- `apps/controller/app/build/outputs/apk/debug/app-debug.apk`
- `apps/companion/app/build/outputs/apk/debug/app-debug.apk`

## GitHub Actions

The workflow builds and uploads two separate artifacts:

- `skyspy-controller-android-debug-apk`
- `skyspy-companion-android-debug-apk`

CI build success confirms compilation and APK packaging. It does not replace installation and functional testing on physical Android phones.

## Security and consent

- The Companion app keeps a launcher icon and shows an ongoing foreground-service notification while connected.
- Initial enrollment requires the Companion user to affirm the diagnostics consent notice.
- Later heartbeats and unpair calls require the device-specific token issued at enrollment.
- Controller's API is intended for private LAN only and does not expose an internet-facing service.
- The device owner can unpair from Companion; the Controller operator can revoke the enrollment.
- SkySpy does not include stealth persistence, hidden capture, SMS/OTP collection, contacts harvesting, notification capture, keylogging, credential collection, phishing overlays, silent installation, remote shell, or Android security-control bypass.
