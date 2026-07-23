/**
 *  ESPHome PumpSaver Monitor
 *
 *  Hubitat driver for the lizbit-official/esphome-pumpsaver component, which
 *  decodes the SymCom/Littelfuse PumpSaver Plus IR broadcast (see
 *  https://github.com/lizbit-official/esphome-pumpsaver and the protocol spec
 *  at https://github.com/lizbit-official/pumpsaver-ir-protocol).
 *
 *  Built on Jonathan Bradshaw's ESPHome Native API Library toolkit
 *  (namespace 'esphome', library 'espHomeApiHelper') -- the same library
 *  already used by the Konnected Alarm Panel / GDOv2-Q drivers on this hub.
 *  Entity-discovery pattern modeled on the toolkit's own
 *  ESPHome-EverythingPresenceOne.groovy example driver.
 *
 *  Requires two small additions to the device's ESPHome YAML for the Reboot
 *  command and Uptime attribute to work (both are stock ESPHome platforms,
 *  no changes to the pumpsaver component itself):
 *
 *    button:
 *      - platform: restart
 *        name: "Restart"
 *
 *    sensor:
 *      - platform: uptime
 *        name: "Uptime"
 *        update_interval: 60s
 *
 *  MIT License
 */
metadata {
    definition(
        name: 'ESPHome PumpSaver Monitor',
        namespace: 'esphome',
        author: 'you',
        singleThreaded: true,
        importUrl: '') {

        capability 'Sensor'
        capability 'Refresh'
        capability 'Initialize'

        command 'reboot'

        // NOTE: PowerMeter/VoltageMeasurement capabilities were dropped on
        // purpose -- Hubitat capabilities lock their attribute to a fixed
        // name ('power'/'voltage'), which can't be renamed. These are plain
        // custom attributes instead so the display names below apply. Trade-
        // off: this device won't show up in capability-filtered pickers
        // (e.g. a Rule Machine "select a Power Meter" list).
        attribute 'Pump Voltage (V)', 'number'
        attribute 'Pump Power (W)', 'number'
        attribute 'Pump Current (A)', 'number'
        attribute 'Pump Dry-Well Trip Point (W)', 'number'
        attribute 'Pump Restart Delay Setting (min)', 'number'
        attribute 'Pump Restart Countdown (s)', 'number'
        attribute 'Calibration Voltage (V)', 'number'

        // counters
        attribute 'Pump Starts', 'number'
        attribute 'Pump Run Time (min)', 'number'

        // fault history
        attribute 'Pump Last Fault At (min)', 'number'
        attribute 'Pump Fault Sequence', 'number'
        attribute 'Pump Last Fault', 'string'

        // state / diagnostics
        attribute 'Pump Running', 'enum', ['active', 'inactive']
        attribute 'IR Link', 'enum', ['true', 'false']
        attribute 'IR Last Seen (s)', 'number'
        attribute 'IR Signal Rate (words/s)', 'number'
        attribute 'IR Decode Errors (per min)', 'number'
        attribute 'Uptime (s)', 'number'
        attribute 'WiFi Signal (dBm)', 'number'

        // populated automatically by the ESPHome API Library
        attribute 'networkStatus', 'enum', ['connecting', 'online', 'offline']
    }

    preferences {
        input name: 'ipAddress', // required setting for API library
            type: 'text',
            title: '<b>Device IP Address</b>',
            required: true

        input name: 'password', // optional setting for API library
            type: 'text',
            title: '<b>Device Password</b>',
            description: '<i>(if required)</i>',
            required: false

        input name: 'txtEnable', type: 'bool', title: '<b>Enable descriptionText logging</b>',
            defaultValue: true

        input name: 'logEnable', type: 'bool', title: '<b>Enable debug logging</b>',
            defaultValue: false, description: '<i>Turns on debug logging for 30 minutes.</i>'
    }
}

void installed() {
    log.info "${device} driver installed"
}

void initialize() {
    state.entities = state.entities ?: [:]
    // API library command to open the socket; it auto-reconnects as needed
    openSocket()

    if (logEnable) {
        runIn(1800, 'logsOff')
    }
}

void updated() {
    log.info "${device} driver configuration updated"
    initialize()
}

void refresh() {
    log.info "${device} refresh"
    espHomeDeviceInfoRequest()
    // re-request entity list + live states in case anything was missed
    espHomeListEntitiesRequest()
    espHomeSubscribeStatesRequest()
}

void reboot() {
    if (state.rebootKey) {
        log.warn "${device} sending reboot command"
        espHomeButtonCommand(key: state.rebootKey)
    } else {
        log.warn "${device}: no Restart button entity discovered yet -- add " +
            "'button: - platform: restart' to the device's YAML, or wait for " +
            "the next refresh/reconnect to pick it up"
    }
}

void uninstalled() {
    closeSocket('driver uninstalled')
    log.info "${device} driver uninstalled"
}

void logsOff() {
    device.updateSetting('logEnable', false)
    log.info "${device} debug logging disabled"
}

// the parse method is invoked by the API library when messages are received
void parse(final Map message) {
    if (logEnable) { log.debug "ESPHome received: ${message}" }

    switch (message.type) {
        case 'entity':
            parseKeys(message)
            break
        case 'state':
            parseState(message)
            break
        default:
            break
    }
}

/**
 * Map discovered entities to friendly keys by (case-insensitive) name/objectId
 * matching. Logs anything it can't place so unmapped entities are easy to spot
 * and add a case for, rather than silently dropped.
 */
void parseKeys(final Map message) {
    if (state.entities == null) { state.entities = [:] }
    final long key = message.key as long
    final String id = (message.objectId ?: message.name ?: '').toString().toLowerCase()

    // the restart button is matched on platform, not name, since "restart"
    // also appears in the restart-delay/countdown sensor names
    if (message.platform == 'button') {
        state.rebootKey = key
        if (txtEnable) { log.info "${device}: found Restart button entity" }
        return
    }

    if (id.contains('calibration')) {
        state.entities['calibration_voltage'] = key
    } else if (id.contains('fault') && id.contains('at')) {
        state.entities['last_fault_at'] = key
    } else if (id.contains('fault') && id.contains('sequence')) {
        state.entities['fault_sequence'] = key
    } else if (id.contains('fault')) {
        state.entities['last_fault'] = key
    } else if (id.contains('running')) {
        state.entities['pump_running'] = key
    } else if (id.contains('link')) {
        state.entities['link_ok'] = key
    } else if (id.contains('signal') && id.contains('rate')) {
        state.entities['signal_rate'] = key
    } else if (id.contains('decode')) {
        state.entities['decode_errors'] = key
    } else if (id.contains('seen')) {
        state.entities['last_seen'] = key
    } else if (id.contains('dry') || id.contains('trip')) {
        state.entities['drywell_trip'] = key
    } else if (id.contains('restart') && (id.contains('delay') || id.contains('setting'))) {
        state.entities['restart_delay_set'] = key
    } else if (id.contains('restart') && (id.contains('countdown') || id.contains('remaining'))) {
        state.entities['restart_remaining'] = key
    } else if (id.contains('starts')) {
        state.entities['pump_starts'] = key
    } else if (id.contains('run') && id.contains('time')) {
        state.entities['run_minutes'] = key
    } else if (id.contains('uptime')) {
        state.entities['uptime'] = key
    } else if (id.contains('wifi') && id.contains('signal')) {
        state.entities['wifi_signal'] = key
    } else if (id.contains('voltage')) {
        state.entities['voltage'] = key
    } else if (id.contains('current')) {
        state.entities['current'] = key
    } else if (id.contains('power')) {
        state.entities['power'] = key
    } else if (logEnable) {
        log.debug "ESPHome entity not mapped: ${message}"
    }
}

void parseState(final Map message) {
    if (message.key == null || message.hasState == false) { return }
    final long key = message.key as long

    switch (key) {
        case state.entities['voltage']:
            updateAttribute('Pump Voltage (V)', message.state, 'V', 1)
            break
        case state.entities['current']:
            updateAttribute('Pump Current (A)', message.state, 'A', 2)
            break
        case state.entities['power']:
            updateAttribute('Pump Power (W)', message.state, 'W', 0)
            break
        case state.entities['calibration_voltage']:
            updateAttribute('Calibration Voltage (V)', message.state, 'V', 1)
            break
        case state.entities['drywell_trip']:
            updateAttribute('Pump Dry-Well Trip Point (W)', message.state, 'W', 0)
            break
        case state.entities['restart_delay_set']:
            updateAttribute('Pump Restart Delay Setting (min)', message.state, 'min', 0)
            break
        case state.entities['restart_remaining']:
            updateAttribute('Pump Restart Countdown (s)', message.state, 's', 0)
            break
        case state.entities['pump_starts']:
            updateAttribute('Pump Starts', message.state as Integer)
            break
        case state.entities['run_minutes']:
            updateAttribute('Pump Run Time (min)', message.state as Integer, 'min')
            break
        case state.entities['last_fault_at']:
            updateAttribute('Pump Last Fault At (min)', message.state as Integer, 'min')
            break
        case state.entities['last_fault']:
            updateAttribute('Pump Last Fault', message.state as String)
            break
        case state.entities['fault_sequence']:
            // last_fault / last_fault_at are published before this counter
            // advances (see protocol spec), so both are already current by
            // the time this fires -- just detect the bump and log it.
            final Integer prior = (device.currentValue('Pump Fault Sequence') as Integer) ?: 0
            final Integer next = message.state as Integer
            updateAttribute('Pump Fault Sequence', next)
            if (next > prior) {
                final String fault = device.currentValue('Pump Last Fault') ?: 'unknown fault'
                log.warn "PumpSaver fault: ${fault}"
                sendEvent(name: 'Pump Last Fault', value: fault, isStateChange: true,
                    descriptionText: "PumpSaver fault: ${fault}")
            }
            break
        case state.entities['pump_running']:
            updateAttribute('Pump Running', message.state ? 'active' : 'inactive')
            break
        case state.entities['link_ok']:
            updateAttribute('IR Link', message.state ? 'true' : 'false')
            break
        case state.entities['last_seen']:
            updateAttribute('IR Last Seen (s)', message.state, 's', 0)
            break
        case state.entities['signal_rate']:
            updateAttribute('IR Signal Rate (words/s)', message.state, null, 1)
            break
        case state.entities['decode_errors']:
            updateAttribute('IR Decode Errors (per min)', message.state, null, 1)
            break
        case state.entities['uptime']:
            updateAttribute('Uptime (s)', message.state as Integer, 's')
            break
        case state.entities['wifi_signal']:
            updateAttribute('WiFi Signal (dBm)', message.state as Integer, 'dBm')
            break
    }
}

/**
 * Update a device attribute and log if it changed and descriptionText logging is on.
 * decimals, if given, rounds the value cleanly -- values arrive as 32-bit
 * floats over the wire, which can't exactly represent most decimal
 * fractions (e.g. 0.21 arrives as 0.209999993...); this rounds that back to
 * the same precision already set via accuracy_decimals in the YAML.
 */
private void updateAttribute(final String attribute, final Object rawValue, final String unit = null,
                              final Integer decimals = null) {
    Object value = rawValue
    if (decimals != null && value instanceof Number) {
        BigDecimal rounded = new BigDecimal(value.toString()).setScale(decimals, BigDecimal.ROUND_HALF_UP)
        value = decimals == 0 ? rounded.intValue() : rounded
    }
    // Only send an event when the value actually changed -- previously this
    // sent on every single incoming message regardless of whether anything
    // changed, which is what drove the excessive event rate. Matches the
    // pattern Konnected's own driver uses (gates the sendEvent call itself,
    // not just the log line).
    if (device.currentValue(attribute) == value) { return }
    final String descriptionText = "${attribute} was set to ${value}${unit ?: ''}"
    if (settings.txtEnable) {
        log.info descriptionText
    }
    sendEvent(name: attribute, value: value, unit: unit, descriptionText: descriptionText)
}

// Put this line at the end of the driver to include the ESPHome API library helper
#include esphome.espHomeApiHelper