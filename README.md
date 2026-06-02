# Chromecast mDNS Discovery for Hubitat

A discovery-style Hubitat app for finding Google Cast / Chromecast devices using and mDNS scan plus Hubitat's own internal mDNS cache.


The hub IP is detected dynamically from the Hubitat runtime.

## What it does

- Sends a short Google Cast mDNS probe to stimulate device responses.
- Waits briefly for Hubitat's internal mDNS cache to update.
- Reads Hubitat's internal mDNS JSON endpoint.
- Parses `_googlecast._tcp.local` endpoints from `http://<hub-ip>:8080/hub/mdnsDevices/json`.
- Displays clean resolved Chromecast / Google Cast devices.
- Shows progress and expected-device coverage.
- Provides diagnostics for parsed mDNS sections and raw cached records.
- Does not require a hardcoded hub IP address.

## What it does not do

- It does not create Hubitat child devices.
- It does not control Chromecast devices.
- It does not send media, TTS, play, pause, volume, or app-launch commands.
- It does not parse raw UDP replies for the final device list.
- It does not require a browser bookmarklet or manual JSON paste.
- It does not replace Hubitat's native Chromecast Integration.

## Discovery flow

```text
Run Chromecast discovery
  ↓
Send short mDNS probe
  ↓
Wait for Hubitat mDNS cache to update
  ↓
Read /hub/mdnsDevices/json from the local hub
  ↓
Parse Google Cast endpoints
  ↓
Display clean resolved devices
```

## Default scan timing

The current version is tuned for a short scan of about 5 seconds.

| Setting | Default |
|---|---:|
| Discovery page refresh interval | 1 second |
| Pre-scan mDNS probe bursts | 2 |
| Pause between pre-scan bursts | 500 ms |
| Wait after pre-scan before reading cache | 1000 ms |
| Expected Chromecast devices | 15 |

Expected timing:

```text
0 sec - start
1 sec - probe 1
2 sec - probe 2
3 sec - settle
4 sec - read JSON
5 sec - complete
```

## Hub IP detection

The app dynamically detects the local Hubitat hub IP using the Hubitat app runtime:

```groovy
location?.hubs*.localIP
location?.hub?.localIP
location?.hub?.getDataValue("localIP")
```

It then builds a single local endpoint URL:

```groovy
"http://${hubIp}:8080/hub/mdnsDevices/json"
```

There are no hardcoded IP addresses, no `localhost` fallback, no `127.0.0.1` fallback, and no manual URL override.

## Installation

1. In Hubitat, go to **Apps Code**.
2. Click **New App**.
3. Paste the Groovy source code.
4. Click **Save**.
5. Go to **Apps**.
6. Click **Add User App**.
7. Select **Chromecast mDNS Discovery**.
8. Open the Discovery Page.
9. Click **Run Chromecast discovery**.

## Configuration

### Discovery page refresh interval

Controls how often the Hubitat dynamic page refreshes while the scan is running.

Recommended:

```text
1 second
```

### Expected number of Chromecast devices

Used only for the device coverage progress bar.

Example:

```text
15
```

Set to `0` to disable expected coverage tracking.

### Stale record threshold

Marks records as stale based on Hubitat's `lastUpdated` timestamp.

Default:

```text
120 minutes
```

### Send mDNS probe before reading Hubitat cache

Recommended:

```text
On
```

This sends a short multicast mDNS query before reading the internal cache. The app does not use the raw UDP replies as the source of truth. It only uses the probe to encourage Hubitat's cache to refresh.

### Pre-scan mDNS probe bursts

Default:

```text
2
```

Increase this only if devices are slow to appear in the Hubitat mDNS cache.

### Wait after pre-scan before reading cache

Default:

```text
1000 ms
```

Increase this if the cache appears to update slowly on your network.

## Output

The app displays a clean table of resolved Google Cast devices.

Typical fields:

| Field | Meaning |
|---|---|
| Name | Friendly Google Cast device name |
| IP | IPv4 address |
| Port | Google Cast service port |
| Model | Device model from mDNS TXT data |
| Type | Inferred display, speaker, TV/streamer, or cast group |
| Status | Receiver/app status where available |
| Source | mDNS |
| Last seen | Timestamp from Hubitat's mDNS cache |

## Device types

The app infers device type from name, model, host, and port.

Examples:

| Type | Detection example |
|---|---|
| Speaker | Google Home, Google Home Mini, Nest Audio, speaker name |
| Smart Display | Google Nest Hub, Nest Hub Max, Fuchsia host, display name |
| Google TV / Streamer | Chromecast, Google TV Streamer, TV name |
| Cast Group | Google Cast Group or port `32026` |

## Diagnostics

The Diagnostics page shows:

- Last discovery status.
- Last error, if any.
- Detected hub IP.
- Dynamic mDNS source URL.
- Last working source.
- Parsed mDNS service sections.
- Raw cached device map.
- Optional raw mDNS cache sample.

Useful service sections include:

```text
_googlecast._tcp.local
_hue._tcp.local
_matter._tcp.local
```

Only `_googlecast._tcp.local` records are used for the clean Chromecast device list.

## Why this approach exists

Hubitat's native mDNS registry can already see Google Cast devices reliably. Raw multicast discovery from a custom Groovy app can be inconsistent because responses may arrive split across PTR, SRV, TXT, and A records, and Hubitat's app runtime does not always expose those replies cleanly.

This app uses a hybrid approach:

- mDNS probe for stimulation.
- Hubitat internal JSON cache for reliable device data.
- Discovery-style UI for usability.

That avoids the unreliable part of raw mDNS parsing while keeping the user experience of a normal discovery scan.

## Known limitations

- The internal Hubitat mDNS endpoint is not an officially documented public API.
- The app depends on Hubitat exposing `/hub/mdnsDevices/json` locally on port `8080`.
- If Hubitat changes the internal JSON structure, the parser may need updating.
- This app is discovery-only. It does not provide Chromecast control.
- Device presence depends on what Hubitat currently has in its mDNS cache.

## Troubleshooting

### Discovery fails with connection refused

Confirm the app is using the dynamic port `8080` endpoint:

```text
http://<hub-ip>:8080/hub/mdnsDevices/json
```

Port `80` may fail from the Hubitat app runtime even when the browser UI works.

### No devices found

Check:

- The hub can see devices under Hubitat's native mDNS devices page.
- The devices are on the same network/VLAN as the hub.
- Multicast/mDNS is not blocked by Wi-Fi isolation, VLAN rules, or firewall rules.
- The expected service section `_googlecast._tcp.local` exists in diagnostics.

### Progress bar does not move

The app uses page-driven progress. Ensure the Discovery Page refresh interval is set to:

```text
1 second
```

### Device coverage is below expected count

The expected count is only a display target. If you expect 15 devices but Hubitat currently sees 14, the scan can still be working correctly. Check diagnostics to see what Hubitat's mDNS cache currently contains.

## Version history

### v1.0.1

- Tuned to complete in approximately 5 seconds.
- Uses 1 second page refresh.
- Uses 2 mDNS probe bursts.
- Uses 1000 ms cache settle wait.
- Keeps dynamic hub IP detection.
- Uses port `8080` for the internal mDNS JSON endpoint.
- Discovery-only. No child devices and no control commands.

### v1.0.0

- Initial cleaned GitHub version.
- Hybrid mDNS probe plus Hubitat JSON cache lookup.
- Discovery-style UI with progress and coverage.

## Safety and scope

This app is local-network discovery tooling only. It does not authenticate to Google, does not call cloud APIs, does not create devices, and does not send commands to Chromecast devices.

## License

MIT License suggested, unless you choose a different license for the repository.
