# Hubitat ESPHome PumpSaver Monitor

A Hubitat driver allows monitoring of a SymCom / Littelfuse **PumpSaver Plus** well
pump-protection device as a single Hubitat device — live voltage/current/power,
fault history, run counters, and IR-link diagnostics — with no wiring into the relay
itself. This is all made possible by one piece of killer reverse-engineering work:

## Credit where it's due

**None of this exists without [lizbit-official](https://github.com/lizbit-official)**,
who reverse-engineered the PumpSaver's undocumented baseband IR broadcast from
scratch and published both the protocol spec and a clean ESPHome component:

- [`pumpsaver-ir-protocol`](https://github.com/lizbit-official/pumpsaver-ir-protocol) —
  the protocol documentation (word format, register map, fault-ring semantics) that
  makes any of this possible in the first place.
- [`esphome-pumpsaver`](https://github.com/lizbit-official/esphome-pumpsaver) — the
  ESPHome component this driver connects to. All of the actual decoding — RMT timing,
  register parsing, fault history — happens there, not in this repo. This driver is
  just a thin Hubitat-side presentation layer on top of her work.

This driver also builds on [Jonathan Bradshaw's (bradsjm) ESPHome Native API Library
toolkit](https://github.com/bradsjm/hubitat-drivers/blob/main/ESPHome/README.md)
(`esphome.espHomeApiHelper`), which does all the heavy lifting of speaking ESPHome's
native protobuf API from Groovy. If you're already running Konnected devices on
Hubitat, you likely already have this library installed.

If you find this useful, please go star/support the original repos above — this
driver is a small add-on to much bigger wizardry.

## What this gives you

A single Hubitat device with:

- **Live telemetry**: voltage, current, power, calibration voltage
- **Protection settings**: dry-well trip point, restart delay setting, restart countdown
- **Counters**: pump starts, run time
- **Fault history**: last fault (rendered text), last fault timestamp, a fault-sequence
  counter designed specifically for triggering Rule Machine automations
- **Diagnostics**: IR signal rate, IR decode errors, IR last-seen age, IR link status,
  device uptime, WiFi signal strength, and the ESPHome library's own connection status
- **Commands**: `Refresh`, `Initialize`, and `Reboot` (remotely restarts the ESP32)

There's intentionally no interactivity beyond that — the PumpSaver itself isn't
controlled or configured by this integration, only observed.

## Requirements

- A Hubitat hub
- An ESP32 and a phototransistor 
  (see [`esphome-pumpsaver`](https://github.com/lizbit-official/esphome-pumpsaver), she has
  great hardware notes in her documentation
  
- The `esphome.espHomeApiHelper` library installed on your hub (see
  [bradsjm's docs](https://github.com/bradsjm/hubitat-drivers/blob/main/ESPHome/README.md)
  if you don't already have it from a Konnected device or similar)
- Two small additions to your device's YAML, both stock ESPHome platforms —
  no changes to the `pumpsaver` component itself:

  ```yaml
  button:
    - platform: restart
      name: "Restart"

  sensor:
    - platform: uptime
      name: "Uptime"
      update_interval: 60s
    - platform: wifi_signal
      name: "WiFi Signal"
      update_interval: 60s
  ```

## Installation

1. **Drivers Code → New Driver**, paste in `ESPHome-PumpSaver.groovy`, Save.
2. **Devices → Add Device → Virtual**, select **ESPHome PumpSaver Monitor** as the type.
3. In device preferences, set the **Device IP Address** of your ESP32 (a DHCP
   reservation is strongly recommended so this doesn't change).
   Leave **Password** blank unless you set one in your YAML's `api:` block.
4. Click **Initialize**. Attributes populate as ESPHome reports each entity — some
   (like the fault-history fields) only get their first value once the PumpSaver
   component completes its own acquisition cycle, so don't expect everything to
   populate instantly on a fresh connection.

## Notes on design choices

- **Entity matching is name-based**, not by hardcoded key — ESPHome assigns entity
  keys at compile time, and they can shift between builds. The driver matches on
  (case-insensitive) substrings of each entity's configured `name:`, and logs
  anything it can't place (`ESPHome entity not mapped`) so a mismatch is easy to
  spot and fix rather than silently dropped.
- **`PowerMeter`/`VoltageMeasurement` capabilities were deliberately dropped** in
  favor of plain custom attributes (`Pump Voltage (V)`, `Pump Power (W)`) — Hubitat
  capabilities lock their attribute to a fixed name, which would have meant
  inconsistent naming next to everything else. Trade-off: this device won't appear
  in capability-filtered device pickers (e.g. a Rule Machine "select a Power Meter"
  list) — Rule Machine's generic attribute picker still sees everything fine.

## License

MIT.  All trademarks mentioned above are property of their respective owners and
this project is not endorsed by those owners, or anyone really.

