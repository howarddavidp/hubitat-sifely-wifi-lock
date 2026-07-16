# Unofficial Sifely S WiFi Lock Driver for Hubitat

## Status Update

A session/token refresh issue has been observed after extended runtime, where the driver may require **Test Login** before commands work again.

A v0.2 test build is currently being tested with:
- proactive token refresh
- authentication-failure detection
- automatic re-login and retry

The current v0.1 driver remains usable, but users may need to press **Test Login** if commands stop working after 24–48 hours.

Do not replace your working driver yet. v0.2 will be posted after testing confirms the fix.



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
```

5. Click **Import**
6. Click **Save**

### Option 2: Manual Install

1. Open `Sifely-WiFi-Lock-Driver.groovy`
2. Copy the entire file contents
3. In Hubitat, go to **Drivers Code**
4. Click **+ Add Driver**
5. Paste the code
6. Click **Save**

---

## Creating a Lock Device in Hubitat

After installing the driver:

1. Go to **Devices**
2. Click **Add Device**
3. Choose **Virtual**
4. Create a new virtual device
5. Set the device type/driver to:

```text
Sifely WiFi Lock
```

6. Open the new device
7. Enter your Sifely account email and password in device preferences
8. Enter the Sifely `lockId`
9. Save preferences
10. Click **Refresh**

---

## Finding Your Sifely Lock ID

The driver currently requires the Sifely `lockId` for each lock.

For my initial testing, I used a temporary test driver to list my Sifely locks and identify their lock IDs. Future versions may make this easier.

Do not publicly post your lock IDs unless you are comfortable sharing them.

---

## Important Security Warning

Do **not** post logs or screenshots containing:

- Sifely tokens
- Access tokens
- Password hashes
- Account email
- Phone number
- Home IP address
- Lock IDs, unless you intentionally want to disclose them

---

## Current Status

This is beta code and currently tested only on my two Sifely S built-in WiFi locks.

Feedback, cleanup suggestions, and testing from other Sifely users are welcome.

---

## Known Limitations

- Cloud-based only
- Not local control
- Requires Sifely cloud availability
- Requires a valid Sifely account login
- Requires manual entry of the Sifely `lockId`
- Tested only on built-in WiFi Sifely S models so far
- BLE-only models with gateway are likely possible but untested

---

## Disclaimer

This is an unofficial community driver.

Use at your own risk. Door lock automation should be handled carefully. Remote unlock is disabled by default in the driver and should only be enabled after testing and with appropriate safeguards.

---

## License

MIT

