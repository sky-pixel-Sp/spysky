# Security policy

## Supported use

SkySpy LAN is for devices owned by the operator or devices whose owner knowingly installs the Companion and chooses to connect. Do not use it to monitor a person without informed consent.

## Current safeguards

- SkySpy Control is a native Windows WPF application. There is no website, browser dashboard, PWA, embedded browser, or static web content.
- The local API runs inside the visible desktop application process and stops when that application closes.
- The embedded API binds only to loopback and detected private IPv4 interfaces.
- Browser-origin requests are rejected; no CORS access is enabled.
- The API exposes no network endpoint for reading the device inventory. The desktop UI reads the local data store directly.
- Companion registration requires explicit owner approval and a stable per-installation UUID.
- Companion heartbeats and unpair requests require the enrolled device's unique credential.
- Device credentials are random and only SHA-256 digests are stored by the Windows app.
- Re-registering the same installation rotates its device credential without creating a duplicate inventory record.
- API request bodies and metadata fields are size-limited and allow-listed.
- Companion requests only the permissions required for its visible diagnostics and network connection.
- Plain HTTP is intended only for localhost and private IPv4 LAN connections.

## Not implemented

There is no remote shell, SMS/OTP access, contacts access, notification capture, keylogging, credential collection, hidden recording, stealth persistence, silent installation, anti-uninstall, or security-control bypass. The current milestone does not implement screen streaming, remote UI control, or P2P tunnelling.

## Reporting

Do not publish device credentials, personal device information, or exploit details in public issues. For a security concern, contact the repository owner privately.
