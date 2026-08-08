# mDNS Device Discovery for Hubitat

A discovery-style Hubitat app for finding Google Cast / Chromecast, Philips Hue Bridge, and Matter devices using an mDNS scan plus Hubitat's own internal mDNS cache.

The hub IP is detected dynamically from the Hubitat runtime.

## What it does

- Sends Google Cast mDNS pulses to stimulate device responses.
- Reads Hubitat's internal mDNS cache, preferring `http://<hub-ip>:8080/hub/mdnsDevices/json` and falling back to the HTML page.
- Parses `_googlecast._tcp.local`, `_hue._tcp.local`, and `_matter._tcp.local` endpoints.
- Displays clean resolved Chromecast devices (with model, type, and receiver status), Hue Bridge records, and Matter records (with MAC, vendor, and a "Known As" name).
- Looks up a device's manufacturer from its MAC OUI for a small set of verified vendor prefixes, or infers a brand from its "Known As" name when the MAC is a randomized/locally-administered address (e.g. Android TV's per-network MAC randomization).
- Optionally cross-references your router's exported client-list CSV (fetched live from a URL you provide - see [Known device names](#known-device-names-optional) below) to show real device names for Hue/Matter records, which carry no human-friendly name of their own in mDNS.
- Shows progress and expected-device coverage for Chromecast.
- Provides diagnostics for parsed mDNS sections and raw cached records.
- Keeps a short "previously discovered" history (retained for a configurable number of days) for all three device types without polluting the current list.

## What it does not do

- It does not create Hubitat child devices.
- It does not control Chromecast, Hue, or Matter devices.
- It does not send media, TTS, play, pause, volume, or app-launch commands.
- It does not parse raw UDP replies for the final device list.
- It does not replace Hubitat's native Chromecast Integration.
- It does not identify a Hue/Matter device by name unless mDNS provides one (Hue does; Matter does not) or you've configured the router client-list CSV URL.

## Discovery flow

```text
Run mDNS discovery
  ↓
Send short mDNS probe
  ↓
Fetch router client-list CSV (if configured)
  ↓
Read /hub/mdnsDevices/json from the local hub
  ↓
Parse Google Cast, Hue, and Matter endpoints
  ↓
Display clean resolved devices per section
```

## Hub IP detection

The app dynamically detects the local Hubitat hub IP using the Hubitat app runtime:

```groovy
location?.hubs*.localIP
location?.hub?.localIP
location?.hub?.getDataValue("localIP")
```

It then builds candidate endpoint URLs on both port 80 and 8080:

```groovy
"http://${hubIp}:8080/hub/mdnsDevices/json"
"http://${hubIp}/hub/mdnsDevices/json"
```

There are no hardcoded IP addresses, no `localhost` fallback, no `127.0.0.1` fallback, and no manual URL override.

## Installation

1. In Hubitat, go to **Apps Code**.
2. Click **New App**.
3. Paste the Groovy source code from `Apps/mDNS_Device_Discovery.groovy`.
4. Click **Save**.
5. Go to **Apps**.
6. Click **Add User App**.
7. Select **mDNS Device Discovery**.
8. Open the Discovery Page.
9. Click **Run mDNS discovery**.

## Configuration

### Discovery page refresh interval / probe settings

Standard mDNS pre-scan probe controls (bursts, timing) - see the app's own preference descriptions for current defaults.

### Known device names (optional)

Matter's mDNS records carry no human-friendly name (Hue's does), so the app can optionally cross-reference a router-exported device list to show real names instead of raw mDNS IDs.

1. Export your router's client list as CSV (needs a header row with `Client Name` and `Clients MAC Address` columns, in any position/order).
2. Upload it to Hubitat's own **File Manager** (Settings > File Manager) so it's hosted locally by the hub - this keeps everything on your LAN with no internet dependency and no data leaving your network.
3. Paste the resulting local URL (e.g. `http://<hub-ip>:8080/local/ClientList.csv`) into the **Router client list CSV URL** setting.

The file is re-fetched on every discovery run, so re-uploading an updated export keeps it current with no code changes needed. If unset, "Known As" falls back to matching a Matter/Hue device's MAC or IP against the currently-discovered Chromecast list (useful when e.g. an Android TV's Matter helper service shares its Cast device's identity).

**Note:** don't commit a real router export to a public repo/fork - it's your home network's device inventory. This setting is stored in your own hub's app configuration, not in the app's source code.

## Output

The app displays separate tables per service type.

### Chromecast

| Field | Meaning |
|---|---|
| Name | Friendly Google Cast device name |
| IP | IPv4 address |
| Port | Google Cast service port |
| Model | Device model from mDNS TXT data |
| Type | Inferred display, speaker, TV/streamer, or cast group |
| Status | Receiver/app status where available |
| Source | mDNS JSON or HTML |
| Last seen | Timestamp from Hubitat's mDNS cache |

### Hue Bridge / Matter

| Field | Meaning |
|---|---|
| Name | mDNS device/instance name (Hue: friendly name; Matter: fixed 33-char fabricId-nodeId, no friendly name exists in the protocol) |
| Host | mDNS server hostname |
| IP | IPv4 address |
| Port | Service port |
| MAC | Device MAC address |
| Vendor | Manufacturer from a small verified MAC-OUI table, or a brand inferred from "Known As" when the MAC is randomized |
| Known As | Cross-referenced human-friendly name - see [Known device names](#known-device-names-optional) |
| Last seen | Timestamp from Hubitat's mDNS cache |

Both current and "previously discovered" (faded, retained for a configurable number of days) tables share column widths within their section so headers stay aligned, and wrap in a horizontally-scrollable container so wide tables remain usable on mobile.

## Device types (Chromecast)

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

- Last discovery status and last error, if any.
- Detected hub IP and endpoint source order.
- Parsed mDNS service sections.
- Raw cached device maps and history for all three device types (Chromecast, Hue, Matter).
- Optional raw mDNS cache sample.

## Why this approach exists

Hubitat's native mDNS registry can already see these devices reliably. Raw multicast discovery from a custom Groovy app can be inconsistent because responses may arrive split across PTR, SRV, TXT, and A records, and Hubitat's app runtime does not always expose those replies cleanly.

This app uses a hybrid approach:

- mDNS probe for stimulation (Chromecast only).
- Hubitat's internal JSON cache for reliable device data across all three service types.
- Discovery-style UI for usability.

That avoids the unreliable part of raw mDNS parsing while keeping the user experience of a normal discovery scan.

## Known limitations

- The internal Hubitat mDNS endpoint is not an officially documented public API.
- If Hubitat changes the internal JSON structure, the parser may need updating.
- This app is discovery-only. It does not provide device control.
- Device presence depends on what Hubitat currently has in its mDNS cache.
- Matter device names are protocol-fixed IDs, not friendly names - use the router client-list CSV if you want real names.
- The Vendor MAC-OUI table only covers prefixes the author has personally verified, not a general-purpose OUI database.

## Troubleshooting

### Discovery fails with connection refused

Confirm the app is using the dynamic port `8080` endpoint:

```text
http://<hub-ip>:8080/hub/mdnsDevices/json
```

Port `80` may fail from the Hubitat app runtime even when the browser UI works. The app tries both.

### No devices found

Check:

- The hub can see devices under Hubitat's native mDNS devices page.
- The devices are on the same network/VLAN as the hub.
- Multicast/mDNS is not blocked by Wi-Fi isolation, VLAN rules, or firewall rules.
- The expected service section (`_googlecast._tcp.local`, `_hue._tcp.local`, or `_matter._tcp.local`) exists in diagnostics.

### "Known As" isn't populated

Confirm the **Router client list CSV URL** setting points to an actual URL the hub can fetch (not a file path on your computer - the hub cannot read your PC's filesystem), and that the CSV has `Client Name` and `Clients MAC Address` columns in its header row.

## Version history

### v1.5.4

- Added a horizontal-scroll wrapper to both device tables so wide tables remain usable on mobile.

### v1.5.0 - v1.5.3

- Added optional router client-list CSV cross-referencing (fetched live from a configurable URL, not baked into source) to resolve real device names for Hue/Matter records.
- Fixed a Hubitat app-sandbox restriction on direct `java.io.*` class references in the CSV-fetch response handling.

### v1.4.x

- Added MAC-OUI based Vendor lookup, with a brand-from-name fallback for randomized MACs.
- Added a "Known As" cross-reference against the currently-discovered Chromecast list.

### v1.3.0

- Renamed from "Chromecast mDNS Discovery" to "mDNS Device Discovery".
- Added Hue Bridge and Matter device discovery alongside Chromecast, with full current/history parity across all three types.
- Fixed column-width alignment issues (Matter's fixed 33-character names needed more buffer than Chromecast's shorter friendly names allowed for).

### v1.2.0 and earlier

See prior releases for the original Chromecast-only discovery implementation.

## Safety and scope

This app is local-network discovery tooling only. It does not authenticate to Google, does not call cloud APIs by default, does not create devices, and does not send commands to any discovered device. The optional router client-list CSV fetch only talks to a URL you configure yourself (recommended: your own hub's local File Manager).

## License

Apache License 2.0 - see [LICENSE](LICENSE).
