/**
 * MCOHome / SFire MH-S512 (MHS512 / MHS512SL) Dual Touch Switch - Hubitat driver
 *
 * Version: 1.0.0 (beta)
 * Author:  Eduardo
 *
 * Creates one child "Generic Component Switch" per gang (endpoint 1 and 2) so
 * each relay can be controlled independently. The parent on/off controls both.
 *
 * Fixes for this firmware (same family as the MH-S511):
 *  - After a hub command the device may send a bogus SwitchBinaryReport with the
 *    opposite value; contradicting binary reports are ignored for a few seconds.
 *  - Local (physical) presses: the hub is added to association groups 2 and 3
 *    (one per gang) and to the multi channel lifeline so presses get reported.
 *
 * Licensed under the MIT License
 */

metadata {
    definition(name: "MCOHome MH-S512 Dual Switch", namespace: "eduardo", author: "Eduardo") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Configuration"

        command "recreateChildDevices"
    }

    preferences {
        input name: "ignoreSeconds", type: "number", title: "Ignore contradicting reports for N seconds after a change", defaultValue: 5
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

@groovy.transform.Field static final List<Integer> ENDPOINTS = [1, 2]

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

void installed() {
    createChildDevices()
    runIn(2, "configure")
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    createChildDevices()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.warn "Debug logging disabled"
}

List<String> configure() {
    createChildDevices()
    List<String> cmds = [
        // Multi channel lifeline so reports arrive encapsulated per endpoint
        secure(zwave.associationV2.associationRemove(groupingIdentifier: 1, nodeId: [])),
        secure(zwave.multiChannelAssociationV2.multiChannelAssociationSet(
            groupingIdentifier: 1,
            nodeId: [],
            multiChannelNodeIds: [[nodeId: zwaveHubNodeId, bitAddress: 0, endPointId: 0]]
        )),
        // Groups 2 and 3 send Basic Set on a local press of gang 1 and gang 2
        secure(zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: [zwaveHubNodeId])),
        secure(zwave.associationV2.associationSet(groupingIdentifier: 3, nodeId: [zwaveHubNodeId])),
        secure(zwave.multiChannelAssociationV2.multiChannelAssociationGet(groupingIdentifier: 1)),
        secure(zwave.associationV2.associationGet(groupingIdentifier: 2)),
        secure(zwave.associationV2.associationGet(groupingIdentifier: 3))
    ]
    cmds += refreshCmds()
    return delayBetween(cmds, 800)
}

void recreateChildDevices() {
    childDevices.each { deleteChildDevice(it.deviceNetworkId) }
    createChildDevices()
}

private void createChildDevices() {
    ENDPOINTS.each { Integer ep ->
        String dni = childDni(ep)
        if (!getChildDevice(dni)) {
            addChildDevice("hubitat", "Generic Component Switch", dni,
                [name: "${device.displayName} - Button ${ep}",
                 label: "${device.displayName} - Button ${ep}",
                 isComponent: false])
            log.info "Created child for endpoint ${ep}"
        }
    }
}

private String childDni(Integer ep) {
    return "${device.id}-ep${ep}"
}

private Integer endpointFromChild(def cd) {
    return cd.deviceNetworkId.split("-ep")[-1] as Integer
}

// ---------------------------------------------------------------------------
// Parent commands (control both gangs)
// ---------------------------------------------------------------------------

List<String> on() {
    return delayBetween(ENDPOINTS.collectMany { setEndpointCmds(it, true) }, 300)
}

List<String> off() {
    return delayBetween(ENDPOINTS.collectMany { setEndpointCmds(it, false) }, 300)
}

List<String> refresh() {
    return delayBetween(refreshCmds(), 300)
}

private List<String> refreshCmds() {
    return ENDPOINTS.collect { ep -> encap(zwave.switchBinaryV1.switchBinaryGet(), ep) }
}

private List<String> setEndpointCmds(Integer ep, Boolean turnOn) {
    String target = turnOn ? "on" : "off"
    // Remember what we commanded so bogus reports right after can be discarded
    markChange(ep, target)
    applyEndpointState(ep, target, "digital")
    // No follow-up Get: this firmware answers with a wrong value shortly after switching
    return [encap(zwave.switchBinaryV1.switchBinarySet(switchValue: turnOn ? 0xFF : 0x00), ep)]
}

// ---------------------------------------------------------------------------
// Child (component) commands
// ---------------------------------------------------------------------------

void componentOn(def cd) {
    sendCmds(setEndpointCmds(endpointFromChild(cd), true))
}

void componentOff(def cd) {
    sendCmds(setEndpointCmds(endpointFromChild(cd), false))
}

void componentRefresh(def cd) {
    sendCmds([encap(zwave.switchBinaryV1.switchBinaryGet(), endpointFromChild(cd))])
}

private void sendCmds(List<String> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, 300), hubitat.device.Protocol.ZWAVE))
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
    def encapsulated = cmd.encapsulatedCommand(CMD_VERSIONS)
    if (encapsulated) zwaveEvent(encapsulated, cmd.sourceEndPoint as Integer)
}

void zwaveEvent(hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport cmd, Integer ep = null) {
    if (logEnable) log.debug "SwitchBinaryReport (ep ${ep}): ${cmd}"
    handleBinaryReport(cmd.value, ep)
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, Integer ep = null) {
    if (logEnable) log.debug "BasicReport (ep ${ep}): ${cmd}"
    handlePhysical(cmd.value, ep)
}

void zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd, Integer ep = null) {
    if (logEnable) log.debug "BasicSet (ep ${ep}): ${cmd}"
    handlePhysical(cmd.value, ep)
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd, Integer ep = null) {
    Boolean ok = (cmd.nodeId ?: []).contains(zwaveHubNodeId as Integer)
    log.info "Group ${cmd.groupingIdentifier} (association) nodes: ${cmd.nodeId} -> hub ${ok ? 'OK' : 'not here'}"
}

void zwaveEvent(hubitat.zwave.commands.multichannelassociationv2.MultiChannelAssociationReport cmd, Integer ep = null) {
    Integer hub = zwaveHubNodeId as Integer
    Boolean ok = (cmd.nodeId ?: []).contains(hub) ||
                 (cmd.multiChannelNodeIds ?: []).any { (it.nodeId as Integer) == hub }
    log.info "Group ${cmd.groupingIdentifier} (multi channel) nodes: ${cmd.nodeId} / ${cmd.multiChannelNodeIds} -> hub ${ok ? 'OK' : 'not here'}"
}

void zwaveEvent(hubitat.zwave.Command cmd, Integer ep = null) {
    if (logEnable) log.debug "Unhandled command (ep ${ep}): ${cmd}"
}

// ---------------------------------------------------------------------------
// State handling
// ---------------------------------------------------------------------------

private void handlePhysical(Integer value, Integer ep) {
    if (ep == null || ep == 0) {
        // Group 2/3 Basic frames are not encapsulated, so the gang is unknown:
        // ask both endpoints once the firmware has settled
        runIn(((ignoreSeconds ?: 5) as Integer) + 1, "refreshAfterPhysical")
        return
    }
    String newValue = value ? "on" : "off"
    markChange(ep, newValue)
    applyEndpointState(ep, newValue, "physical")
}

void refreshAfterPhysical() {
    // Clear windows so the answers to this poll are accepted as-is
    ENDPOINTS.each { state.remove("lastTime${it}".toString()) }
    sendCmds(refreshCmds())
}

private void handleBinaryReport(Integer value, Integer ep) {
    if (ep == null || ep == 0) {
        // Non-encapsulated binary report: gang unknown, poll both endpoints
        sendCmds(refreshCmds())
        return
    }
    String newValue = value ? "on" : "off"
    Long windowMs = ((ignoreSeconds ?: 5) as Long) * 1000L
    Long elapsed = now() - ((state."lastTime${ep}" ?: 0L) as Long)
    String lastValue = state."lastValue${ep}"
    if (elapsed < windowMs && lastValue && newValue != lastValue) {
        // Contradicts a change made moments ago: firmware glitch, keep current state
        if (logEnable) log.debug "Ignoring '${newValue}' on ep ${ep}, ${elapsed} ms after '${lastValue}'"
        return
    }
    applyEndpointState(ep, newValue, null)
}

private void markChange(Integer ep, String value) {
    state."lastValue${ep}" = value
    state."lastTime${ep}" = now()
}

private void applyEndpointState(Integer ep, String newValue, String type) {
    def child = getChildDevice(childDni(ep))
    if (child && child.currentValue("switch") != newValue) {
        String suffix = type ? " [${type}]" : ""
        child.parse([[name: "switch", value: newValue, descriptionText: "${child.displayName} was turned ${newValue}${suffix}"]])
        log.info "${child.displayName} was turned ${newValue}${suffix}"
    }
    updateParentState(ep, newValue)
}

private void updateParentState(Integer ep, String newValue) {
    // Parent shows "on" if any gang is on (child state may not be committed yet, so use newValue for ep)
    Boolean anyOn = ENDPOINTS.any { e ->
        (e == ep) ? newValue == "on" : getChildDevice(childDni(e))?.currentValue("switch") == "on"
    }
    sendEvent(name: "switch", value: anyOn ? "on" : "off")
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private String encap(hubitat.zwave.Command cmd, Integer ep) {
    return secure(zwave.multiChannelV3.multiChannelCmdEncap(sourceEndPoint: 0, destinationEndPoint: ep).encapsulate(cmd))
}

private String secure(hubitat.zwave.Command cmd) {
    // Applies S0/S2 encryption if the device was paired securely; no-op otherwise
    return zwaveSecureEncap(cmd.format())
}
