# Changelog

## 2.0.1 — 2026-11-01

Open API key driver (recommended).

### Added
- `Sifely-WiFi-Lock-Driver-v2.groovy` — driver type **Sifely WiFi Lock v2** (version 2.0.1)
- Auth via Sifely Open API (`https://cus-openapi.sifely.com`) using `Authorization: sk-...` (no Bearer)
- API key preference sourced from connect.sifely.com

### Changed
- Replaces reliance on legacy `app-smart-server.sifely.com` email/password login for lock commands (that path has been rejecting calls with "authentication failed" since ~2026-09-29)
- Same capabilities, attributes, and command names as v1 so Hubitat rules keep working after switching driver type
- `lastError` set to `"none"` on success (empty values were not recorded)

### Notes
- Legacy preference fields (`apiBaseUrl`, account, password, token refresh hours, force login) are retained on v2 but ignored
- v1 source remains in the repo for historical installs

## 1.0.0 — 2026-07-16

First production release, promoted from the tested internal build **0.3-test Version D**.

### Confirmed working

- Manual lock and unlock
- Refresh and lock-state synchronization
- Scheduled proactive token refresh
- Fresh authentication before commands when enabled
- Authentication retry after token failure
- List Locks
- Multiple locks on one Sifely account
- Hubitat Basic Rules
- Geofence arrival and departure automations

### Versioning note

The production source preserves the tested Version D behavior. Public-facing driver name and version identifiers were changed to `Sifely WiFi Lock` and `1.0.0`.
