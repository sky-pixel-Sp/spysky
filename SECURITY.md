# Security policy

## Supported use

SkySpy LAN is for devices owned by the operator or devices whose owner has knowingly installed and paired the Companion. Do not use it to monitor a person without their informed consent.

## Current safeguards

- Operator-only endpoints require a bearer token with at least 32 characters.
- Pairing codes are short-lived and single-use.
- Each paired device receives a unique random bearer token; only its SHA-256 digest is stored by the API.
- Pairing attempts are rate-limited per source IP.
- Device metadata accepted by the API is allow-listed and length-limited.
- The Companion requests no sensitive Android permissions in the initial milestone.
- Internet deployments must use HTTPS. Keep the API private behind a TLS reverse proxy or trusted deployment platform.

## Not implemented

There is no remote shell, SMS/OTP access, contacts access, notification capture, keylogging, credential collection, hidden recording, stealth persistence, silent installation, anti-uninstall, or security-control bypass. The current milestone does not implement screen streaming, remote UI control, or P2P tunnelling.

## Reporting

Do not publish credentials, pairing codes, device tokens, personal device information, or exploit details in public issues. For a security concern, contact the repository owner privately.
