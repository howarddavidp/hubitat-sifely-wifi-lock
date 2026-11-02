# Changelog

## 2.0.2 — 2026-11-02 (in testing / prerelease)

See the 1.0.1 entry above for the full Problem / Cause / Fix explanation.

### Fixed
- Gateway-paired locks no longer show `onlineStatus = "offline (gateway)"`. Sifely's lock list returns `isOnline=0` for gateway-bridged locks even when they're healthy; the driver now reports `"via gateway"` when `hasGateway=1` and the lock state query succeeds.
- `lockListSummary` is now refreshed on every `refresh()` (it was only updated by `listLocks`/`testLogin`, so it went stale), and gateway locks show as `via gateway` there.

### Added
- `reachable` attribute (`true`/`false`) based on whether the lock state query succeeded.

## 1.0.1 — 2026-11-02 (in testing / prerelease, username/password line)

Backports the 2.0.2 gateway online-status fix to `Sifely-WiFi-Lock-Driver.groovy`. 1.0.0 stays the stable username/password release.

#### Problem
Hubitat showed Sifely locks as **"offline (gateway)"** even when the Sifely mobile app showed the gateways and locks as **Online**, and even while Hubitat's lock state and battery refreshes were working.

#### Cause
The driver copied Sifely's `isOnline` flag as-is and labeled `hasGateway=1` + `isOnline=0` as "offline (gateway)". For gateway-paired locks, Sifely can report that flag as `0` while the lock is still reachable through the gateway.

Separately, `lockListSummary` was only updated by `listLocks` / `testLogin`, not by `refresh()`. It went stale, so locks looked online on one device and offline on another.

#### Fix
- When a gateway-paired lock's state query succeeds, `onlineStatus` now reports **"via gateway"** instead of "offline".
- `lockListSummary` is refreshed on every `refresh()`, and gateway-paired locks show as "via gateway" there too.
- New `reachable` attribute (`true` / `false`), based on whether the lock state query succeeded.

No changes to commands or capabilities, so existing rules keep working. If any rule checks for `onlineStatus == "online"`, also allow `"via gateway"`, or use the new `reachable` attribute.

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
