# In testing: v2.0.2-testing (API key) and v1.0.1-testing (username/password)

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

---

# Sifely WiFi Lock Driver v2.0.1

Recommended release: Open API key authentication.

## What changed

- New file: `Sifely-WiFi-Lock-Driver-v2.groovy` (Hubitat driver type **Sifely WiFi Lock v2**)
- Uses Sifely Open API (`https://cus-openapi.sifely.com`) with an API key from connect.sifely.com
- Header style: `Authorization: sk-...` (no Bearer prefix)
- Same capabilities / commands as v1 so existing rules keep working after you switch the device type and enter the API key
- `lastError` records `"none"` on success

## Why upgrade

The legacy v1.0.0 login against `app-smart-server.sifely.com` has been failing with "authentication failed" since about 2026-09-29. Install or switch to v2 and use an Open API key instead of email/password.

## Install

See README — copy `Sifely-WiFi-Lock-Driver-v2.groovy`, set driver type **Sifely WiFi Lock v2**, enter API key + Lock ID, then Initialize / testLogin / Refresh.

v1.0.0 remains available as `Sifely-WiFi-Lock-Driver.groovy` for reference only.

This is an unofficial community driver and is not affiliated with Sifely or Hubitat.
