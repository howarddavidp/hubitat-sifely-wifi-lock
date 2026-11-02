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
