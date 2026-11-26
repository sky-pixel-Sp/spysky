# Security policy

## Supported use

SkySpy LAN is for devices owned by the operator or devices whose owner knowingly installs the Companion and chooses to connect. Do not use it to monitor a person without informed consent.

## Current safeguards

- First admin setup is restricted to a loopback request and can only happen once.
- Admin passwords are stored as salted scrypt hashes.
- Operator sessions use random bearer tokens with a 12-hour lifetime and are held in memory.
- Companion registration requires an explicit `consent: true` confirmation and a stable per-installation UUID.
- Device credentials are unique random tokens; only SHA-256 digests are stored by the API.
- Re-registering the same installation rotates its device token without creating a duplicate inventory record.
- Registration and login attempts are rate-limited.
- API metadata is allow-listed and length-limited.
- Companion requests only the Android INTERNET permission in this milestone.
- Public endpoints must use HTTPS. Plain HTTP is allowed by the Companion only for localhost and private IPv4 LAN addresses.

## Not implemented

There is no remote shell, SMS/OTP access, contacts access, notification capture, keylogging, credential collection, hidden recording, stealth persistence, silent installation, anti-uninstall, or security-control bypass. The current milestone does not implement screen streaming, remote UI control, or P2P tunnelling.

## Reporting

Do not publish passwords, session tokens, device tokens, personal device information, or exploit details in public issues. For a security concern, contact the repository owner privately.
