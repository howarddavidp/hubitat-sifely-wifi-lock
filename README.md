# Unofficial Sifely S WiFi Lock Driver for Hubitat

## Beta / Proof of Concept

Let me start by setting expectations: I am about 1,000 miles from being any type of developer. I have almost zero current-day coding experience, and I am new to Hubitat within the last 6 months. With regard to smart home automation, I am not the most experienced person here — honestly, I am still a pathetic NOOB in a lot of areas. This is my first attempt at writing any type of integration, driver, or custom Hubitat code. Heck, I cannot even get all of my own automations to work yet.

That said, I have had wildly good success with Sifely locks. They have been flawless for me, and I wanted very badly to integrate these superb locks into Hubitat. Since I could not find a native Hubitat integration for them, I decided to see if it could be done.

This is an unofficial cloud-based Hubitat driver for Sifely S WiFi locks. It allows Hubitat to communicate with Sifely built-in WiFi locks through the Sifely cloud API.

## Credit / Background

I do not want to take 100% credit for the code.

I had the idea to see if these locks could be made to work with Hubitat, and I did the testing on real locks. I confirmed the API behavior, validated the endpoints, mapped the lock states, and tested everything live on my Hubitat hub.

I used ChatGPT/Jarvis to help generate and iterate the Groovy driver code while I tested each step on my system.

So this was a human + AI-assisted integration effort. I provided the locks, Hubitat environment, testing, debugging feedback, endpoint validation, and real-world confirmation. ChatGPT/Jarvis helped with the driver code.

## Tested Hardware

Tested with Sifely S models that include built-in WiFi.

Tested/owned models:

- ASIN: `B0DYN249DW`
  - Model Number: `Sifely S Model WiFi`

- ASIN: `B0G2S815CN`
  - Model Number: `Sifely S WiFi`

These are the Sifely models with built-in WiFi. I do **not** need the Sifely gateway for these locks.

## BLE-Only Sifely Locks

I am 99.99% sure this same approach would likely work with Bluetooth/BLE-only Sifely models **if and only if** the user has purchased and installed the Sifely WiFi gateway.

However, I do not own the BLE-only models or the Sifely gateway, so I cannot test or confirm that yet.

Testing from users with BLE-only Sifely locks plus the Sifely gateway would be helpful.

## Confirmed Working

Confirmed working from Hubitat:

- Login to Sifely cloud
- Store Sifely cloud token
- List locks
- Read battery level
- Read online/offline status
- Read lock state
- Map lock state:
  - `0 = locked`
  - `1 = unlocked`
- Lock command from Hubitat
- Unlock command from Hubitat
- Refresh command from Hubitat

## Cloud Timing Behavior

After changing lock state from the Sifely Android app, the Sifely cloud may need about 10–20 seconds before Hubitat can query the updated state reliably.

If Hubitat polls too quickly, the API may return an error. Waiting around 20 seconds and refreshing again works.

## Security Notes

Because this is a door lock driver, remote unlock should be treated carefully.

Recommended defaults:

- Remote lock: enabled
- Remote unlock: disabled by default
- Enable remote unlock only after testing
- Do not use unlock automations until you add guardrails

Recommended guardrails for arrival-based unlock automations:

- Only unlock when a specific presence device arrives
- Only unlock when mode is not Night
- Only unlock when an “Auto Unlock Enabled” virtual switch is on
- Auto-lock again after a defined time period
- Send a notification when unlock is triggered

## Cloud Dependency

This is a cloud-based integration.

It is not local LAN, Z-Wave, Zigbee, or Matter. If Sifely cloud or internet access is down, Hubitat control may not work.

## Installation

### Option 1: Hubitat Import URL

In Hubitat:

1. Go to **Drivers Code**
2. Click **+ Add Driver**
3. Click **Import**
4. Paste the raw driver URL:

```text
https://raw.githubusercontent.com/howarddavidp/hubitat-sifely-wifi-lock/main/Sifely-WiFi-Lock-Driver.groovy
