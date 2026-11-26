# Security policy

## Supported use

SkySpy is for an Android phone owned by the operator and Android devices whose owners knowingly install SkySpy Companion and explicitly approve the diagnostics connection. Do not use it to monitor anyone without informed consent.

## Android-only architecture

- **SkySpy Controller** is a native Android app. It hosts the private-LAN API in a visible Android foreground service.
- **SkySpy Companion** is a separate native Android app installed and approved by the managed-device owner.
- The project has no Windows panel, website, browser dashboard, PWA, embedded browser, or Node.js sidecar.
- The Controller API uses port 8787, rejects browser-origin requests, accepts only loopback/private IPv4 clients, and does not expose a remote inventory endpoint.
- Companion registration requires an explicit owner consent flag and a valid installation UUID.
- Heartbeats and unpair requests require a per-installation random device token. The Controller stores only the token's SHA-256 digest.
- Re-registering the same installation rotates its device token without creating a duplicate record.
- Request bodies are size-limited, metadata fields are allow-listed, and device text is sanitized.
- The Companion retains a launcher icon and uses a visible foreground-service notification for its connection loop.
- Current diagnostics need no camera, microphone, location, contacts, SMS, call-log, notification-listener, accessibility, or storage permissions.

## Current feature boundaries

The current build supports diagnostics, heartbeat/online status, and revoking a device enrollment. It does **not** implement remote screen streaming, remote touch injection, file browsing, camera/microphone access, arbitrary remote commands, P2P tunnelling, or covert operation. Any future screen-support feature must use Android's system MediaProjection consent flow for each session and remain visible on the Companion device.

There is no SMS/OTP access, contacts harvesting, notification capture, keylogging, credential collection, hidden recording, stealth persistence, anti-uninstall, silent installation, phishing overlay, remote shell, or Android security-control bypass.

## Network warning

Plain HTTP is intended only for localhost and trusted private IPv4 LAN connections. Do not port-forward the Controller or expose port 8787 to the public internet. A production deployment outside a trusted LAN would need a separately reviewed TLS and authentication design.

## Reporting

Do not publish device credentials, personal device information, or exploit details in public issues. For a security concern, contact the repository owner privately.
