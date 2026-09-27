/**
 * MCOHome / SFire MH-S511 (MHS511 / MHS511SL) Single Touch Switch - Hubitat driver
 *
 * Version: 1.0.0
 * Author:  Eduardo
 *
 * Fixes for this firmware:
 *  - After a hub command the device sends a bogus SwitchBinaryReport with the
 *    opposite value; contradicting binary reports are ignored for a few seconds.
 *  - Local (physical) presses are not sent to the lifeline; the hub is added to
 *    association group 2 so presses arrive as Basic frames, which are trusted.
 *
 * Licensed under the MIT License
 */

metadata {
    definition(name: "MCOHome MH-S511 Switch", namespace: "eduardo", author: "Eduardo") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Configuration"
    }

    preferences {
        input name: "ignoreSeconds", type: "number", title: "Ignore contradicting reports for N seconds after a command", defaultValue: 5
        input name: "pollSeconds", type: "number", title: "Poll state every N seconds (0 = off, min 10)", defaultValue: 0
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

@groovy.transform.Field static final Map CMD_VERSIONS = [
    0x20: 1, // Basic
    0x25: 1, // Switch Binary
    0x60: 3, // Multi Channel
    0x85: 2, // Association
    0x8E: 2  // Multi Channel Association
]

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    runIn(2, "configure")
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    schedulePolling()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.warn "Debug logging disabled"
}

List<String> configure() {
    // Group 1 (lifeline) is already managed by the hub as a multi channel
    // association to endpoint 0. The firmware does not seem to report local
    // presses there, so also add the hub to group 2 (Basic Set on local press).
    List<String> cmds = [
        secure(zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: [zwaveHubNodeId])),
        secure(zwave.associationV2.associationGet(groupingIdentifier: 1)),
        secure(zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: 1)),
        secure(zwave.associationV2.associationGet(groupingIdentifier: 2)),
        secure(zwave.associationV2.associationGroupingsGet())
    ]
    schedulePolling()
    return delayBetween(cmds, 800)
}

private void schedulePolling() {
    unschedule("poll")
    Integer secs = pollInterval()
    if (secs > 0) {
        // Fallback when the device never reports local presses
        runIn(secs, "poll")
        log.info "Polling every ${secs} s"
    }
}

private Integer pollInterval() {
    Integer secs = (pollSeconds ?: 0) as Integer
    return secs > 0 ? Math.max(secs, 10) : 0
}

void poll() {
    // Skip while a recent change is settling, the firmware answers wrong right after switching
    Long windowMs = ((ignoreSeconds ?: 5) as Long) * 1000L
    if (now() - ((state.lastCmdTime ?: 0L) as Long) >= windowMs) {
        sendCmds([secure(zwave.switchBinaryV1.switchBinaryGet())])
    }
    Integer secs = pollInterval()
    if (secs > 0) runIn(secs, "poll")
}

private void sendCmds(List<String> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 800), hubitat.device.Protocol.ZWAVE))
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

List<String> on() {
    return setSwitch(true)
}

List<String> off() {
    return setSwitch(false)
}

List<String> refresh() {
    return [secure(zwave.switchBinaryV1.switchBinaryGet())]
}

private List<String> setSwitch(Boolean turnOn) {
    String target = turnOn ? "on" : "off"
    // Remember what we commanded so bogus reports right after can be discarded
    state.lastCmdValue = target
    state.lastCmdTime = now()
    sendEvent(name: "switch", value: target, type: "digital")
    // No follow-up Get: this firmware answers with a wrong value shortly after switching
    return [secure(zwave.switchBinaryV1.switchBinarySet(switchValue: turnOn ? 0xFF : 0x00))]
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

void parse(String description) {
    if (logEnable) log.debug "parse: ${description}"
    def cmd = zwave.parse(description, CMD_VERSIONS)
    if (cmd) {
        zwaveEvent(cmd)
    } else {
        log.warn "Unable to parse: ${description}"
    }
}

void zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
    // Some firmware sends reports encapsulated from endpoint 1 even on single-gang units
    def encapsulated = cmd.encapsulatedCommand(CMD_VERSIONS)
    if (encapsulated) zwaveEvent(encapsulated)
}

void zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd) {
    if (logEnable) log.debug "SwitchBinaryReport: ${cmd}"
    // Binary reports are the unreliable ones (bogus value right after switching)
    handleBinaryReport(cmd.value)
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
    if (logEnable) log.debug "BasicReport: ${cmd}"
    // Basic frames come from group 2 on a local press: always trustworthy
    handlePhysical(cmd.value)
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
    if (logEnable) log.debug "BasicSet: ${cmd}"
    handlePhysical(cmd.value)
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
    Boolean ok = (cmd.nodeId ?: []).contains(zwaveHubNodeId as Integer)
    log.info "Group ${cmd.groupingIdentifier} (association) nodes: ${cmd.nodeId} -> hub ${ok ? 'OK' : 'not here'}"
}

void zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd) {
    Integer hub = zwaveHubNodeId as Integer
    // The hub may appear as a plain node or as a multi channel node (endpoint 0)
    Boolean ok = (cmd.nodeId ?: []).contains(hub) ||
                 (cmd.multiChannelNodeIds ?: []).any { (it.nodeId as Integer) == hub }
    log.info "Group ${cmd.groupingIdentifier} (multi channel) nodes: ${cmd.nodeId} / ${cmd.multiChannelNodeIds} -> hub ${ok ? 'OK' : 'not here'}"
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
    log.info "Device supports ${cmd.supportedGroupings} association groups"
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "Unhandled command: ${cmd}"
}

private void handlePhysical(Integer value) {
    String newValue = value ? "on" : "off"
    // Record it as the latest known change so a bogus binary report right after is discarded
    state.lastCmdValue = newValue
    state.lastCmdTime = now()
    if (device.currentValue("switch") == newValue) return
    sendEvent(name: "switch", value: newValue, type: "physical",
              descriptionText: "${device.displayName} was turned ${newValue} [physical]")
    log.info "${device.displayName} was turned ${newValue} [physical]"
}

private void handleBinaryReport(Integer value) {
    String newValue = value ? "on" : "off"
    Long windowMs = ((ignoreSeconds ?: 5) as Long) * 1000L
    Long elapsed = now() - ((state.lastCmdTime ?: 0L) as Long)

    if (elapsed < windowMs && state.lastCmdValue && newValue != state.lastCmdValue) {
        // Contradicts a change made moments ago: firmware glitch, keep current state
        if (logEnable) log.debug "Ignoring '${newValue}' binary report ${elapsed} ms after '${state.lastCmdValue}'"
        return
    }
    if (device.currentValue("switch") == newValue) return
    sendEvent(name: "switch", value: newValue, descriptionText: "${device.displayName} is ${newValue}")
    log.info "${device.displayName} is ${newValue}"
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private String secure(hubitat.zwave.Command cmd) {
    // Applies S0/S2 encryption if the device was paired securely; no-op otherwise
    return zwaveSecureEncap(cmd.format())
}
