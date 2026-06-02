/**
 *  Chromecast mDNS Discovery
 *
 *  Version: 1.0.0
 *  Author: Gordon Thelander
 *  Purpose: 
 *  Discovery-style Hubitat app for Google Cast / Chromecast devices.
 *
 *  It uses a hybrid pattern:
 *
 *  1. Send a short mDNS probe to stimulate responses.
 *  2. Wait briefly for Hubitat's own mDNS cache to update.
 *  3. Read Hubitat's internal mDNS cache endpoint:
 *
 *  - Run discovery button
 *  - Discovery scan progress
 *  - Device coverage progress
 *  - Clean resolved devices
 *  - Optional diagnostics
 *
 *  What it does:
 *  - Sends an optional short Google Cast mDNS probe.
 *  - Reads Hubitat's mDNS registry/cache.
 *  - Parses _googlecast._tcp.local endpoints.
 *  - Shows the result as a clean discovery scan.
 *  - Accumulates the current discovered device list in app state.
 *
 *  What it does not do:
 *  - It does not create child devices.
 *  - It does not control Chromecast devices.
 *  - It does not parse raw multicast replies for the device list.
 */

import groovy.json.JsonSlurper

definition(
    name: "Chromecast mDNS Discovery",
    namespace: "gordon-thelander",
    author: "Gordon Thelander",
    description: "Discovery-style Google Cast / Chromecast inventory using a short mDNS probe followed by Hubitat's internal mDNS cache. Does not create child devices.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "discoveryPage")
    page(name: "diagnosticsPage")
}

def installed() {
    log.info "${app.name} installed"
    initialise()
}

def updated() {
    log.info "${app.name} updated"
    unsubscribe()
    unschedule()
    initialise()
}

def initialise() {
    state.discoveredDevices = state.discoveredDevices ?: [:]
    state.mdnsSections = state.mdnsSections ?: []
    state.discoveryRunning = false
    state.scanTotal = state.scanTotal ?: 0
    state.scanCompleted = state.scanCompleted ?: 0
    state.discoveryStartedAt = state.discoveryStartedAt ?: null
    state.discoveryCompletedAt = state.discoveryCompletedAt ?: null
    state.lastDiscoveryMessage = state.lastDiscoveryMessage ?: null
    state.lastError = state.lastError ?: null
    state.lastWorkingUrl = state.lastWorkingUrl ?: null
    state.rawJsonSample = state.rawJsonSample ?: null

    unschedule()

    if (autoRefreshMinutes && safeInt(autoRefreshMinutes) > 0) {
        Integer mins = Math.max(1, Math.min(60, safeInt(autoRefreshMinutes)))
        schedule("0 */${mins} * ? * *", "backgroundRefreshDiscovery")
    }

    if (logEnable) {
        runIn(1800, "disableDebugLogging")
    }
}

def disableDebugLogging() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "Debug logging disabled automatically"
}

def mainPage() {
    return dynamicPage(name: "mainPage", title: "Chromecast mDNS Discovery", install: true, uninstall: true) {
        section("Discovery-only mode") {
            paragraph "<b>Purpose:</b> Google Cast / Chromecast discovery only. This app does not create child devices and does not control Chromecast devices."
            paragraph "<b>Method:</b> reads Hubitat's internal mDNS device cache and presents it as a clean discovery scan. This avoids unreliable raw multicast discovery from custom Groovy."
        }

        section("mDNS discovery configuration") {
            input(
                name: "discoveryRefreshSeconds",
                type: "number",
                title: "Discovery page refresh interval, seconds",
                defaultValue: 1,
                required: true,
                submitOnChange: true
            )

            input(
                name: "expectedCleanDeviceCount",
                type: "number",
                title: "Expected number of Chromecast devices",
                description: "Used for coverage display. Set to 0 to disable.",
                defaultValue: 15,
                required: true,
                submitOnChange: true
            )

            input(
                name: "staleAfterMinutes",
                type: "number",
                title: "Mark mDNS record stale after minutes",
                description: "Default 120. Uses Hubitat's Last updated timestamp.",
                defaultValue: 120,
                required: true,
                submitOnChange: true
            )

            input(
                name: "enablePreScanMdnsProbe",
                type: "bool",
                title: "Send mDNS probe before reading Hubitat cache?",
                description: "Recommended on. Sends a short Google Cast mDNS probe, waits briefly, then reads /hub/mdnsDevices/json.",
                defaultValue: true,
                required: true,
                submitOnChange: true
            )

            input(
                name: "preScanProbeBursts",
                type: "number",
                title: "Pre-scan mDNS probe bursts",
                description: "Default 2 for a ~5 second scan. This is only to stimulate Hubitat's own mDNS cache, not to parse raw replies.",
                defaultValue: 2,
                required: true,
                submitOnChange: true
            )

            input(
                name: "preScanPauseMs",
                type: "number",
                title: "Pause between pre-scan bursts, milliseconds",
                defaultValue: 500,
                required: true,
                submitOnChange: true
            )

            input(
                name: "preScanLateWaitMs",
                type: "number",
                title: "Wait after pre-scan before reading cache, milliseconds",
                defaultValue: 1000,
                required: true,
                submitOnChange: true
            )

            input(
                name: "autoRefreshMinutes",
                type: "number",
                title: "Background refresh interval, minutes",
                description: "0 disables automatic background refresh.",
                defaultValue: 0,
                required: true,
                submitOnChange: true
            )

            input(
                name: "showUnresolvedMdnsRecords",
                type: "bool",
                title: "Show non-Chromecast mDNS records in diagnostics?",
                description: "Shows Hue/Matter/other service records retained from Hubitat's mDNS cache.",
                defaultValue: false,
                required: true,
                submitOnChange: true
            )

        }

        section("Discovery") {
            href(
                name: "goDiscovery",
                page: "discoveryPage",
                title: "<b>Open Discovery Page</b>",
                description: getDiscoverySummaryText()
            )
        }

        section("Diagnostics") {
            input(
                name: "storeRawJsonSample",
                type: "bool",
                title: "Store raw mDNS cache sample for diagnostics?",
                defaultValue: true,
                required: true,
                submitOnChange: true
            )

            input(
                name: "logEnable",
                type: "bool",
                title: "Enable debug logging for 30 minutes?",
                defaultValue: false,
                required: true,
                submitOnChange: true
            )

            input(
                name: "descriptionTextEnable",
                type: "bool",
                title: "Enable description text logging?",
                defaultValue: true,
                required: true,
                submitOnChange: true
            )

            href(
                name: "goDiagnostics",
                page: "diagnosticsPage",
                title: "Open diagnostics",
                description: "Raw mDNS cache sections, dynamically detected hub IP and parser status"
            )
        }
    }
}

def discoveryPage() {
    return dynamicPage(
        name: "discoveryPage",
        title: "Chromecast mDNS Discovery",
        nextPage: "mainPage",
        install: false,
        refreshInterval: getDiscoveryRefreshSeconds()
    ) {
        section("Discovery controls") {
            paragraph buildDiscoveryStatusHtml()

            input(
                name: "mdnsDiscoverBtn",
                type: "button",
                title: "Run Chromecast discovery",
                submitOnChange: true
            )

            input(
                name: "clearDiscoveredBtn",
                type: "button",
                title: "Clear/reset accumulated discovery list",
                submitOnChange: true
            )
        }

        section("Clean resolved devices") {
            paragraph buildDiscoveredDevicesHtml()
        }

        section("Navigation") {
            href(
                name: "backToMain",
                page: "mainPage",
                title: "Back to main settings",
                description: "Return to the main app page"
            )
        }
    }
}

def diagnosticsPage() {
    return dynamicPage(
        name: "diagnosticsPage",
        title: "Chromecast mDNS Discovery Diagnostics",
        nextPage: "mainPage",
        install: false
    ) {
        section("Discovery status") {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(state.lastDiscoveryMessage ?: '')}</pre>"
            if (state.lastError) {
                paragraph "<pre style='white-space:pre-wrap;font-size:11px;color:#8a1f11;'>${htmlEncode(state.lastError)}</pre>"
            }
        }

        section("mDNS cache source") {
            paragraph buildSourceUrlHtml()
        }

        section("Parsed mDNS service sections") {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(toPrettyText(state.mdnsSections ?: []))}</pre>"
        }

        section("Raw cached devices") {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(toPrettyText(state.discoveredDevices ?: [:]))}</pre>"
        }

        if (state.rawJsonSample) {
            section("Raw mDNS cache sample") {
                paragraph "<pre style='white-space:pre-wrap;font-size:10px;'>${htmlEncode(state.rawJsonSample)}</pre>"
            }
        }
    }
}

def appButtonHandler(String btn) {
    if (logEnable) {
        log.debug "Button pressed: ${btn}"
    }

    switch (btn) {
        case "mdnsDiscoverBtn":
            startMdnsDiscovery()
            break
        case "clearDiscoveredBtn":
            clearDiscoveredDevices()
            break
        default:
            log.warn "Unhandled button: ${btn}"
            break
    }
}

def startMdnsDiscovery() {
    state.discoveryRunning = true
    state.discoveryStartedAt = formatNow()
    state.discoveryCompletedAt = null
    state.lastCheckedIp = "Hubitat mDNS cache"
    state.lastError = null

    Boolean doProbe = enablePreScanMdnsProbe != false
    Integer bursts = doProbe ? getPreScanProbeBursts() : 0

    state.asyncDoProbe = doProbe
    state.asyncProbeBursts = bursts
    state.asyncProbeIndex = 0
    state.asyncPhase = doProbe && bursts > 0 ? "probe" : "read"
    state.asyncStartedMs = nowMs()
    state.asyncNextStepMs = nowMs()
    state.asyncSettleMs = getPreScanLateWaitMs()

    // UI-compatible page-driven scan steps:
    // 1..N = optional probe bursts
    // +1 = cache settle wait
    // +1 = JSON endpoint read
    // +1 = resolve/display complete
    state.scanTotal = bursts + 3
    state.scanCompleted = 0

    state.lastDiscoveryMessage = doProbe ?
        "Discovery started. Google Cast mDNS pre-scan will complete in about 5 seconds." :
        "Discovery started. Hubitat mDNS cache read will complete in about 5 seconds."

    log.info state.lastDiscoveryMessage
}

def advanceDiscoveryFromPageRefresh() {
    if (state.discoveryRunning != true) {
        return
    }

    Long now = nowMs()
    Long due = safeLong(state.asyncNextStepMs ?: 0L)

    if (due > 0L && now < due) {
        return
    }

    String phase = state.asyncPhase?.toString() ?: "read"
    Integer bursts = safeInt(state.asyncProbeBursts)
    Integer index = safeInt(state.asyncProbeIndex)

    if (phase == "probe") {
        if (index < bursts) {
            index = index + 1
            state.asyncProbeIndex = index
            state.scanCompleted = index
            state.lastDiscoveryMessage = "Discovery pre-scan ${index}/${bursts}. Sending Google Cast mDNS probe."

            sendMdnsGoogleCastProbe()

            if (index < bursts) {
                state.asyncNextStepMs = now + getPreScanPauseMs()
            } else {
                state.asyncPhase = "settle"
                state.scanCompleted = bursts + 1
                state.lastDiscoveryMessage = "Discovery pre-scan complete. Waiting ${getPreScanLateWaitMs()} ms for Hubitat mDNS cache to update."
                state.asyncNextStepMs = now + getPreScanLateWaitMs()
            }

            return
        }

        state.asyncPhase = "settle"
        state.asyncNextStepMs = now + getPreScanLateWaitMs()
        return
    }

    if (phase == "settle") {
        state.asyncPhase = "read"
        state.asyncNextStepMs = now
        state.lastDiscoveryMessage = "Discovery settle complete. Reading Hubitat mDNS cache next."
        return
    }

    if (phase == "read") {
        state.scanCompleted = bursts + 2
        state.lastDiscoveryMessage = "Discovery in progress. Reading Hubitat mDNS service registry."

        Map result = fetchMdnsCache()

        if (result.ok == true) {
            state.discoveredDevices = result.devices ?: [:]
            state.mdnsSections = result.sections ?: []
            state.lastWorkingUrl = result.url
            state.rawJsonSample = storeRawJsonSample == false ? null : trimForStorage(result.rawText?.toString(), 100000)

            Integer clean = getVisibleDiscoveredDevices()?.size() ?: 0
            Integer allSections = state.mdnsSections?.size() ?: 0

            state.lastDiscoveryMessage = "Discovery cache read complete. Clean devices: ${clean}. mDNS service sections: ${allSections}."
            state.lastError = clean == 0 ? "mDNS cache was read successfully but no Google Cast devices were resolved." : null

            if (descriptionTextEnable) {
                log.info state.lastDiscoveryMessage
            }
        } else {
            state.discoveredDevices = [:]
            state.mdnsSections = []
            state.rawJsonSample = result.rawText ? trimForStorage(result.rawText?.toString(), 100000) : null
            state.lastError = result.error ?: "Unknown mDNS cache discovery error"
            state.lastDiscoveryMessage = "Discovery scan failed. ${state.lastError}"
            log.warn state.lastDiscoveryMessage
        }

        state.asyncPhase = "finish"
        state.asyncNextStepMs = now + 500L
        return
    }

    if (phase == "finish") {
        state.scanCompleted = state.scanTotal ?: safeInt(state.scanCompleted)
        finishMdnsDiscovery()
        return
    }
}


def sendMdnsGoogleCastProbe() {
    // These probes are cache stimulation only. The device list is still read from /hub/mdnsDevices/json.
    sendMdnsQuery("_googlecast._tcp.local PTR", "0000000000010000000000000b5f676f6f676c6563617374045f746370056c6f63616c00000c0001")
    sendMdnsQuery("_googlezone._tcp.local PTR", "0000000000010000000000000b5f676f6f676c657a6f6e65045f746370056c6f63616c00000c0001")
    sendMdnsQuery("_services._dns-sd._udp.local PTR", "000000000001000000000000095f7365727669636573075f646e732d7364045f756470056c6f63616c00000c0001")
}

def sendMdnsQuery(String label, String queryHex) {
    try {
        sendHubCommand(
            new hubitat.device.HubAction(
                queryHex,
                hubitat.device.Protocol.LAN,
                [
                    type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
                    destinationAddress: "224.0.0.251:5353",
                    encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
                    ignoreWarning: true
                ]
            )
        )

        if (logEnable) {
            log.debug "mDNS pre-scan probe sent for ${label}."
        }
    } catch (Exception e) {
        state.lastError = "mDNS pre-scan probe failed for ${label}: ${e.message}"
        log.warn state.lastError
    }
}


def backgroundRefreshDiscovery() {
    if (state.discoveryRunning == true) {
        return
    }

    if (logEnable) {
        log.debug "Background Chromecast discovery refresh starting"
    }

    startMdnsDiscovery()
}

def finishMdnsDiscovery() {
    state.discoveryRunning = false
    state.scanCompleted = state.scanTotal ?: 4
    state.discoveryCompletedAt = formatNow()

    Integer clean = getVisibleDiscoveredDevices()?.size() ?: 0
    Integer raw = state.discoveredDevices?.size() ?: 0
    Integer unresolved = getUnresolvedRecordCount()

    if (!state.lastDiscoveryMessage || !state.lastDiscoveryMessage.toString().startsWith("Discovery scan failed")) {
        state.lastDiscoveryMessage = "Discovery scan completed. Clean devices: ${clean}. Raw records: ${raw}. Hidden unresolved: ${unresolved}."
    }

    log.info state.lastDiscoveryMessage
}

Map fetchMdnsCache() {
    List urls = getMdnsJsonUrlCandidates()
    List failures = []

    for (String url in urls) {
        try {
            Map result = fetchAndParseMdnsJson(url)
            if (result.ok == true) {
                result.url = url
                return result
            }

            failures << "${url} -> ${result.error ?: 'not ok'}"
        } catch (Exception e) {
            failures << "${url} -> ${e.message}"
            log.warn "mDNS cache candidate failed: ${url} -> ${e.message}"
        }
    }

    return [
        ok: false,
        error: failures.join(" | "),
        devices: [:],
        sections: []
    ]
}

Map fetchAndParseMdnsJson(String url) {
    Map out = [
        ok: false,
        url: url,
        error: null,
        rawText: null,
        devices: [:],
        sections: []
    ]

    Map params = [
        uri: url,
        contentType: "application/json",
        timeout: 15,
        headers: [
            "Accept": "application/json,text/plain,*/*"
        ]
    ]

    httpGet(params) { resp ->
        Integer status = safeInt(resp?.status)

        if (status < 200 || status >= 300) {
            out.error = "HTTP ${status}"
            return
        }

        Object data = resp?.data
        Object json = data
        String rawText = null

        try {
            rawText = data?.toString()
        } catch (Exception ignored) {}

        out.rawText = rawText

        if (data instanceof String) {
            String s = data.toString()
            out.rawText = s

            if (!s.trim()) {
                out.error = "empty response"
                return
            }

            json = new JsonSlurper().parseText(s)
        }

        Map parsed = parseHubitatMdnsJson(json)
        out.devices = parsed.devices ?: [:]
        out.sections = parsed.sections ?: []

        if (!out.sections && !out.devices) {
            out.error = "No mDNS service sections or devices found in cache"
            return
        }

        out.ok = true
    }

    return out
}

Map parseHubitatMdnsJson(Object json) {
    Map devices = [:]
    List sections = []

    if (!(json instanceof Map)) {
        return [devices: devices, sections: sections]
    }

    Map root = json as Map
    Object serviceTypesObj = root.get("serviceTypes")

    if (!(serviceTypesObj instanceof List)) {
        return [devices: devices, sections: sections]
    }

    (serviceTypesObj as List).each { svcObj ->
        if (!(svcObj instanceof Map)) {
            return
        }

        Map svcMap = svcObj as Map
        String serviceType = cleanupService(svcMap.get("serviceType")?.toString())
        Integer declaredCount = safeNullableInt(svcMap.get("count"))
        Object endpointsObj = svcMap.get("endpoints")

        Integer parsedCount = 0
        Integer cleanCount = 0

        if (endpointsObj instanceof List) {
            (endpointsObj as List).each { epObj ->
                if (!(epObj instanceof Map)) {
                    return
                }

                Map ep = epObj as Map
                Map item = normaliseHubitatEndpoint(ep, serviceType)

                if (item.name || item.ip || item.host) {
                    parsedCount = parsedCount + 1
                }

                if (serviceType?.toLowerCase()?.contains("googlecast")) {
                    String key = makeDiscoveryKey(item)
                    devices[key] = item
                    cleanCount = cleanCount + 1
                }
            }
        }

        sections << [
            serviceType: serviceType,
            declaredCount: declaredCount,
            parsedCount: parsedCount,
            cleanGoogleCastCount: cleanCount
        ]
    }

    return [devices: devices, sections: sections]
}

Map normaliseHubitatEndpoint(Map m, String service) {
    Map txt = m.get("txtProperties") instanceof Map ? (m.get("txtProperties") as Map) : [:]

    String name = stringFirst(m.get("friendlyName"), m.get("name"), txt.get("fn"), m.get("eventName"))
    String host = stringFirst(m.get("server"))
    String ip = stringFirst(m.get("ip4Address"), m.get("ipv4Address"), m.get("ipAddress"), m.get("ip"))
    String port = stringFirst(m.get("port"))
    String lastUpdated = stringFirst(m.get("lastUpdated"))
    String model = stringFirst(m.get("model"), txt.get("md"))
    String status = stringFirst(m.get("status"), txt.get("st"))
    String receiverStatus = stringFirst(m.get("receiverStatus"), txt.get("rs"))
    String deviceId = stringFirst(m.get("deviceId"), txt.get("id"))
    String macAddress = stringFirst(m.get("macAddress"))

    Map item = [
        key: null,
        ip: ip ?: "",
        port: port ?: "",
        name: cleanupName(name),
        model: model ?: "Google Cast Device",
        firmware: stringFirst(m.get("version"), txt.get("ve"), "mDNS"),
        source: "mDNS",
        serviceType: service ?: "",
        host: cleanupHost(host),
        mdnsId: deviceId ?: "",
        instance: stringFirst(m.get("eventName")),
        macAddress: macAddress ?: "",
        status: status ?: "",
        receiverStatus: receiverStatus ?: "",
        discoveredAt: stringFirst(state.discoveryStartedAt, formatNow()),
        lastSeenDuringDiscovery: lastUpdated ?: formatNow(),
        lastUpdated: lastUpdated ?: "",
        stale: isStaleLastUpdated(lastUpdated),
        updatedMinutesAgo: minutesSinceLastUpdated(lastUpdated)
    ]

    item.type = inferDeviceType(item)
    item.badge = inferBadge(item)
    item.key = makeDiscoveryKey(item)

    return item
}

String makeDiscoveryKey(Map item) {
    String identity = item.mdnsId ?: item.host ?: "${item.name}-${item.ip}-${item.port}"
    return "mdns-${sanitizeKey(identity)}"
}

def clearDiscoveredDevices() {
    state.discoveredDevices = [:]
    state.mdnsSections = []
    state.discoveryRunning = false
    state.scanTotal = 0
    state.scanCompleted = 0
    state.discoveryStartedAt = null
    state.discoveryCompletedAt = null
    state.lastCheckedIp = null
    state.lastDiscoveryMessage = null
    state.lastError = null
    state.rawJsonSample = null
    unschedule("backgroundRefreshDiscovery")
    state.asyncDoProbe = null
    state.asyncProbeBursts = null
    state.asyncProbeIndex = null
    state.asyncSettleSeconds = null
    state.asyncPhase = null
    state.asyncNextStepMs = null
    state.asyncStartedMs = null
    state.asyncSettleMs = null
    initialise()
    log.info "Accumulated Chromecast mDNS discovery list cleared."
}

Map getVisibleDiscoveredDevices() {
    Map raw = state.discoveredDevices ?: [:]
    Map visible = [:]

    raw.each { key, item ->
        Boolean hasIp = item?.ip && isValidSimpleIp(item.ip.toString())
        Boolean hasFriendlyName = item?.name && !isUnresolvedName(item.name.toString())
        Boolean unresolved = isUnresolvedMdnsRecord(item)

        if (!hasIp || !hasFriendlyName || unresolved) {
            return
        }

        String displayKey = item?.mdnsId ? "cast-${sanitizeKey(item.mdnsId)}" : makeDni(item.ip.toString(), item.port?.toString())
        Map existing = visible[displayKey]

        if (!existing) {
            visible[displayKey] = item + [rawKey: key]
        } else {
            visible[displayKey] = chooseBetterDiscoveryItem(existing, item + [rawKey: key])
        }
    }

    return visible
}

Map getRawDiagnosticDevices() {
    Map raw = state.discoveredDevices ?: [:]
    Map diagnostics = [:]

    raw.each { key, item ->
        if (isUnresolvedMdnsRecord(item) || !(item?.ip && isValidSimpleIp(item.ip.toString()))) {
            diagnostics[key] = item
        }
    }

    return diagnostics
}

Integer getUnresolvedRecordCount() {
    return getRawDiagnosticDevices()?.size() ?: 0
}

Map chooseBetterDiscoveryItem(Map a, Map b) {
    Integer scoreA = discoveryDisplayScore(a)
    Integer scoreB = discoveryDisplayScore(b)

    if (scoreB > scoreA) {
        return b
    }

    Map merged = a
    merged.ip = merged.ip ?: b.ip
    merged.port = merged.port ?: b.port
    merged.model = betterModelName(merged.model, b.model)
    merged.source = mergeSourceText(merged.source, b.source)
    merged.mdnsId = merged.mdnsId ?: b.mdnsId
    merged.instance = merged.instance ?: b.instance
    merged.receiverStatus = merged.receiverStatus ?: b.receiverStatus
    return merged
}

Integer discoveryDisplayScore(Map item) {
    Integer score = 0

    if (item?.ip && isValidSimpleIp(item.ip.toString())) {
        score += 20
    }

    if (item?.name && !isUnresolvedName(item.name.toString())) {
        score += 50
    }

    if (item?.model && !item.model.toString().equalsIgnoreCase("Google Cast Device")) {
        score += 10
    }

    if (item?.port) {
        score += 5
    }

    return score
}

Boolean isUnresolvedMdnsRecord(Map item) {
    if (!item) {
        return true
    }

    String name = item.name?.toString() ?: ""
    String model = item.model?.toString() ?: ""
    Boolean hasIp = item?.ip && isValidSimpleIp(item.ip.toString())
    Boolean hasFriendlyName = name && !isUnresolvedName(name)

    if (hasIp && hasFriendlyName) {
        return false
    }

    if (!hasFriendlyName) {
        return true
    }

    if (!hasIp && model.equalsIgnoreCase("Google Cast Device")) {
        return true
    }

    if (!hasIp && !item.mdnsId) {
        return true
    }

    return false
}

Boolean isUnresolvedName(String name) {
    if (!name) {
        return true
    }

    String lower = name.toLowerCase().trim()

    if (lower.contains("_googlezone._tcp.local") || lower.contains("_googlecast._tcp.local")) {
        return true
    }

    if (lower == "_googlecast._tcp.local" || lower == "_googlezone._tcp.local") {
        return true
    }

    if (lower == "google cast device" || lower == "google cast / dial") {
        return true
    }

    if (lower ==~ /[a-f0-9]{6,}[-a-f0-9_\.]+/) {
        return true
    }

    if (lower ==~ /.*[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}.*/) {
        return true
    }

    return false
}

String betterModelName(existingModel, newModel) {
    String existing = existingModel?.toString()
    String fresh = newModel?.toString()

    if (!existing || existing == "Unknown" || existing == "Google Cast Device") {
        return fresh ?: existing
    }

    return existing
}

String mergeSourceText(existingSource, newSource) {
    String existing = existingSource?.toString()
    String fresh = newSource?.toString()

    if (!existing) {
        return fresh
    }

    if (!fresh || existing.contains(fresh)) {
        return existing
    }

    return "${existing}+${fresh}"
}

def normalizeDiscoveryStateForUi() {
    Integer total = safeInt(state.scanTotal)
    Integer completed = safeInt(state.scanCompleted)

    if (state.discoveryRunning == true && total > 0 && completed >= total) {
        state.scanCompleted = total
        state.discoveryRunning = false
        state.discoveryCompletedAt = state.discoveryCompletedAt ?: formatNow()
        state.lastDiscoveryMessage = state.lastDiscoveryMessage ?: "Discovery scan completed."
    }
}

String buildDiscoveryStatusHtml() {
    advanceDiscoveryFromPageRefresh()
    normalizeDiscoveryStateForUi()

    Integer rawCount = state.discoveredDevices?.size() ?: 0
    Integer cleanCount = getVisibleDiscoveredDevices()?.size() ?: 0
    Integer unresolvedCount = getUnresolvedRecordCount()
    Integer expectedCount = getExpectedCleanDeviceCount()

    Integer totalBursts = safeInt(state.scanTotal)
    Integer completedBursts = safeInt(state.scanCompleted)
    String msg = state.lastDiscoveryMessage ?: ""

    Integer displayCompletedBursts = completedBursts
    Boolean effectivelyAtEnd = (totalBursts > 0 && completedBursts >= totalBursts)

    if (effectivelyAtEnd) {
        displayCompletedBursts = totalBursts
    }

    Integer runProgress = 0
    if (totalBursts > 0) {
        runProgress = Math.min(100, Math.max(0, ((displayCompletedBursts * 100) / totalBursts).toInteger()))
    }

    Boolean scanComplete = (state.discoveryRunning != true && state.discoveryCompletedAt) || effectivelyAtEnd

    if (scanComplete) {
        runProgress = 100
    }

    Integer deviceCoverage = 0
    String coverageText = ""

    if (expectedCount > 0) {
        deviceCoverage = Math.min(100, Math.max(0, ((cleanCount * 100) / expectedCount).toInteger()))
        coverageText = "${cleanCount} of ${expectedCount} expected clean device(s) (${deviceCoverage}%)"
    } else {
        deviceCoverage = cleanCount > 0 ? 100 : 0
        coverageText = "${cleanCount} clean device(s) found"
    }

    String runState = state.discoveryRunning == true && !effectivelyAtEnd ? "Discovery scan running" : (scanComplete ? "Discovery scan completed${state.discoveryCompletedAt ? ': ' + state.discoveryCompletedAt : ''}" : "Discovery scan not yet run")

    StringBuilder builder = new StringBuilder()

    builder << "<div style='font-size:14px;'>"
    builder << "<p><b>${htmlEncode(runState)}</b></p>"

    builder << "<table style='font-size:13px;border-collapse:collapse;'>"
    builder << "<tr><td style='padding-right:20px;'><b>Usable clean devices</b></td><td>${cleanCount}</td></tr>"
    if (expectedCount > 0) {
        builder << "<tr><td style='padding-right:20px;'><b>Expected devices</b></td><td>${expectedCount}</td></tr>"
    }
    builder << "<tr><td style='padding-right:20px;'><b>Raw mDNS records</b></td><td>${rawCount}</td></tr>"
    builder << "<tr><td style='padding-right:20px;'><b>Hidden unresolved records</b></td><td>${unresolvedCount}</td></tr>"
    builder << "</table>"

    builder << "<p style='margin-bottom:4px;'><b>Discovery run progress</b>: internal step ${displayCompletedBursts} of ${totalBursts} (${runProgress}%)"
    if (scanComplete) {
        builder << " - scan completed"
    }
    builder << "</p>"
    builder << "<div style='background:#d9ecb1;border-radius:5px;width:100%;height:14px;margin-top:2px;margin-bottom:10px;'>"
    builder << "<div style='background:#81bc00;border-radius:5px;width:${runProgress}%;height:14px;'>&nbsp;</div>"
    builder << "</div>"

    builder << "<p style='margin-bottom:4px;'><b>Device coverage</b>: ${coverageText}"
    if (scanComplete && expectedCount > 0 && cleanCount < expectedCount) {
        builder << " - scan completed, expected count not fully reached"
    }
    builder << "</p>"
    builder << "<div style='background:#d7e8f7;border-radius:5px;width:100%;height:14px;margin-top:2px;margin-bottom:10px;'>"
    builder << "<div style='background:#2f80c0;border-radius:5px;width:${deviceCoverage}%;height:14px;'>&nbsp;</div>"
    builder << "</div>"

    builder << "<p style='font-size:12px;margin-top:8px;'>"
    builder << "<b>How discovery works:</b> the app advances a short visible staged scan designed to complete in about 5 seconds, optionally sends a short Google Cast mDNS probe, waits briefly, then reads Hubitat's mDNS device cache and lists records that resolve to a friendly Google Cast name and usable IP address. "
    builder << "Other service records can be retained for diagnostics but are hidden from the clean device list."
    builder << "</p>"

    if (msg) {
        builder << "<p style='font-size:12px;'><b>Last status:</b> ${htmlEncode(msg)}</p>"
    }

    if (state.lastError) {
        builder << "<p style='font-size:12px;color:#8a1f11;'><b>Last error:</b> ${htmlEncode(state.lastError)}</p>"
    }

    builder << "</div>"

    return builder.toString()
}

String buildDiscoveredDevicesHtml() {
    Map visibleDevices = getVisibleDiscoveredDevices()
    Map rawDiagnostics = getRawDiagnosticDevices()

    Integer rawCount = state.discoveredDevices?.size() ?: 0
    Integer unresolvedCount = rawDiagnostics?.size() ?: 0

    if (!visibleDevices || visibleDevices.size() == 0) {
        if (rawCount > 0) {
            return "No clean resolved Chromecast devices are currently visible. Raw mDNS records found: ${rawCount}. Hidden unresolved records: ${unresolvedCount}."
        }
        return "No discovered devices currently in the accumulated list."
    }

    StringBuilder builder = new StringBuilder()
    builder << "<p><b>Clean resolved Chromecast devices: ${visibleDevices.size()}</b>"
    builder << "<br><span style='font-size:12px'>Raw mDNS records retained internally: ${rawCount}. Hidden unresolved records: ${unresolvedCount}. Only records with both a friendly name and usable IP are shown below.</span>"
    builder << "</p>"

    builder << "<table style='width:100%;font-size:13px;border-collapse:collapse;'>"
    builder << "<tr><th align='left'>Name</th><th align='left'>IP</th><th align='left'>Port</th><th align='left'>Model</th><th align='left'>Type</th><th align='left'>Status</th><th align='left'>Source</th><th align='left'>Last seen</th></tr>"

    visibleDevices.sort { a, b -> a.value.name <=> b.value.name }.each { key, item ->
        builder << "<tr>"
        builder << "<td>${htmlEncode(item.name)}</td>"
        builder << "<td>${htmlEncode(item.ip ?: 'IP pending')}</td>"
        builder << "<td>${htmlEncode(item.port ?: '')}</td>"
        builder << "<td>${htmlEncode(item.model ?: 'Unknown')}</td>"
        builder << "<td>${htmlEncode(item.type ?: 'Google Cast')}</td>"
        builder << "<td>${htmlEncode(item.receiverStatus ?: item.status ?: '')}</td>"
        builder << "<td>${htmlEncode(item.source ?: 'unknown')}</td>"
        builder << "<td>${htmlEncode(item.lastSeenDuringDiscovery ?: item.discoveredAt ?: '')}</td>"
        builder << "</tr>"
    }

    builder << "</table>"

    if (showUnresolvedMdnsRecords == true) {
        builder << "<p><b>mDNS service sections, diagnostics only:</b></p>"
        builder << "<table style='width:100%;font-size:12px;border-collapse:collapse;'>"
        builder << "<tr><th align='left'>Service type</th><th align='left'>Declared</th><th align='left'>Parsed</th><th align='left'>Google Cast clean</th></tr>"

        (state.mdnsSections ?: []).each { section ->
            builder << "<tr>"
            builder << "<td>${htmlEncode(section.serviceType ?: '')}</td>"
            builder << "<td>${htmlEncode(section.declaredCount ?: '')}</td>"
            builder << "<td>${htmlEncode(section.parsedCount ?: '')}</td>"
            builder << "<td>${htmlEncode(section.cleanGoogleCastCount ?: 0)}</td>"
            builder << "</tr>"
        }

        builder << "</table>"
    }

    return builder.toString()
}

String getDiscoverySummaryText() {
    Integer rawDiscovered = state.discoveredDevices?.size() ?: 0
    Integer cleanDiscovered = getVisibleDiscoveredDevices()?.size() ?: 0
    Integer unresolved = getUnresolvedRecordCount()
    return "${cleanDiscovered} clean resolved device(s), ${rawDiscovered} raw mDNS record(s), ${unresolved} unresolved"
}

String buildSourceUrlHtml() {
    String hubIp = getHubIpAddress()
    String url = hubIp ? "http://${hubIp}:8080/hub/mdnsDevices/json" : "unavailable - hub IP not detected"

    StringBuilder b = new StringBuilder()
    b << "<table style='font-size:13px;border-collapse:collapse;width:100%;'>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Detected hub IP</td><td style='font-family:monospace;'>${htmlEncode(hubIp ?: 'unknown')}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Dynamic mDNS source</td><td style='font-family:monospace;'>${htmlEncode(url)}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Last working source</td><td style='font-family:monospace;'>${htmlEncode(state.lastWorkingUrl ?: 'none yet')}</td></tr>"
    b << "</table>"

    return b.toString()
}


List getMdnsJsonUrlCandidates() {
    String hubIp = getHubIpAddress()

    if (!hubIp) {
        state.lastError = "Could not dynamically determine Hubitat hub IP address from location.hubs/location.hub."
        return []
    }

    return ["http://${hubIp}:8080/hub/mdnsDevices/json"]
}


String getHubIpAddress() {
    List candidates = []

    try {
        def hubs = location?.hubs
        hubs?.each { h ->
            try {
                if (h?.localIP) {
                    candidates << h.localIP.toString()
                }
            } catch (Exception ignored) {}
        }
    } catch (Exception ignored) {}

    try {
        if (location?.hub?.localIP) {
            candidates << location.hub.localIP.toString()
        }
    } catch (Exception ignored) {}

    try {
        if (location?.hub?.getDataValue("localIP")) {
            candidates << location.hub.getDataValue("localIP").toString()
        }
    } catch (Exception ignored) {}

    candidates = candidates
        .findAll { it && isValidSimpleIp(it.toString()) }
        .collect { it.toString() }
        .unique()

    return candidates ? candidates[0] : null
}

String inferDeviceType(Map d) {
    String name = d.name?.toString()?.toLowerCase() ?: ""
    String host = d.host?.toString()?.toLowerCase() ?: ""
    String model = d.model?.toString()?.toLowerCase() ?: ""
    String port = d.port?.toString() ?: ""

    if (port == "32026" || model.contains("cast group")) return "Cast Group"
    if (name.contains("tv") || name.contains("google tv") || model.contains("google tv") || model == "chromecast") return "Google TV / Streamer"
    if (name.contains("display") || host.contains("fuchsia") || model.contains("nest hub")) return "Smart Display"
    if (name.contains("speaker") || model.contains("home") || model.contains("nest audio")) return "Speaker"
    return "Google Cast"
}

String inferBadge(Map d) {
    if (d.stale == true) return "STALE"
    if (d.port?.toString() == "32026" || d.model?.toString()?.toLowerCase()?.contains("cast group")) return "GROUP"
    return "FRESH"
}

Boolean isStaleLastUpdated(String s) {
    Integer mins = minutesSinceLastUpdated(s)
    if (mins == null) {
        return false
    }
    return mins > getStaleAfterMinutes()
}

Integer minutesSinceLastUpdated(String s) {
    Date d = parseHubitatTimestamp(s)
    if (!d) {
        return null
    }

    Long diffMs = new Date().time - d.time
    if (diffMs < 0) {
        return 0
    }

    return (diffMs / 60000L).toInteger()
}

Date parseHubitatTimestamp(String s) {
    if (!s) {
        return null
    }

    String value = s.toString().trim()
    if (!value) {
        return null
    }

    List patterns = [
        "yyyy-MM-dd HH:mm:ss z",
        "yyyy-MM-dd HH:mm z",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm"
    ]

    for (String p in patterns) {
        try {
            return Date.parse(p, value)
        } catch (Exception ignored) {}
    }

    return null
}

Integer getExpectedCleanDeviceCount() {
    Integer count = safeInt(expectedCleanDeviceCount ?: 15)
    if (count < 0) {
        return 0
    }
    if (count > 200) {
        return 200
    }
    return count
}

Integer getStaleAfterMinutes() {
    Integer minutes = safeInt(staleAfterMinutes ?: 120)
    if (minutes < 1) {
        return 1
    }
    if (minutes > 10080) {
        return 10080
    }
    return minutes
}

Integer getPreScanProbeBursts() {
    Integer n = safeInt(preScanProbeBursts ?: 2)
    if (n < 0) {
        return 0
    }
    if (n > 50) {
        return 50
    }
    return n
}

Integer getPreScanPauseMs() {
    Integer ms = safeInt(preScanPauseMs ?: 500)
    if (ms < 50) {
        return 50
    }
    if (ms > 2000) {
        return 2000
    }
    return ms
}

Integer getPreScanLateWaitMs() {
    Integer ms = safeInt(preScanLateWaitMs ?: 1000)
    if (ms < 100) {
        return 100
    }
    if (ms > 15000) {
        return 15000
    }
    return ms
}

Integer getDiscoveryRefreshSeconds() {
    Integer seconds = safeInt(discoveryRefreshSeconds ?: 1)
    if (seconds < 1) {
        return 1
    }
    if (seconds > 60) {
        return 60
    }
    return seconds
}

String cleanupName(String s) {
    if (!s) {
        return ""
    }

    String v = s.trim().replaceAll(/\s+/, " ")
    v = v.replaceAll(/\s+@\s+.*?\.local\.?$/, "")
    return v
}

String cleanupHost(String s) {
    if (!s) {
        return ""
    }

    return s.trim().replaceAll(/\s+/, "")
}

String cleanupService(String s) {
    if (!s) return ""
    return s.trim().replaceAll(/\.$/, "")
}

Object firstNonNull(Object... values) {
    for (Object v in values) {
        if (v != null) return v
    }
    return null
}

String stringFirst(Object... values) {
    Object v = firstNonNull(values)
    return v == null ? "" : v.toString()
}

Integer safeNullableInt(value) {
    if (value == null) return null
    try {
        return value as Integer
    } catch (Exception ignored) {
        return null
    }
}

String sanitizeKey(value) {
    String s = value?.toString() ?: "unknown"
    s = s.toLowerCase()
    s = s.replaceAll("[^a-z0-9]+", "-")
    s = s.replaceAll("^-+", "")
    s = s.replaceAll("-+\$", "")

    if (!s) {
        s = "unknown"
    }

    if (s.length() > 80) {
        s = s.substring(0, 80)
    }

    return s
}

String makeDni(String ip, String port = "") {
    String p = port ? "-${port}" : ""
    return "castmon-${ip.replace('.', '-')}${p}"
}

Boolean isValidSimpleIp(String ip) {
    def parts = ip?.split("\\.")
    if (parts?.size() != 4) {
        return false
    }

    for (p in parts) {
        Integer n = safeInt(p)
        if (n < 0 || n > 255) {
            return false
        }
    }

    return true
}

Long nowMs() {
    return new Date().time
}

Long safeLong(value) {
    try {
        return value as Long
    } catch (Exception ignored) {
        return 0L
    }
}

Integer safeInt(value) {
    try {
        return value as Integer
    } catch (Exception ignored) {
        return 0
    }
}

String formatNow() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

String htmlEncode(value) {
    return value?.toString()
        ?.replace("&", "&amp;")
        ?.replace("<", "&lt;")
        ?.replace(">", "&gt;")
        ?: ""
}

String toPrettyText(value) {
    if (value == null) {
        return ""
    }

    if (value instanceof Map) {
        StringBuilder b = new StringBuilder()
        value.each { k, v ->
            b << "${k}: ${v}\n"
        }
        return b.toString()
    }

    if (value instanceof List) {
        StringBuilder b = new StringBuilder()
        value.eachWithIndex { item, i ->
            b << "${i + 1}. ${item}\n"
        }
        return b.toString()
    }

    return value.toString()
}

String trimForStorage(String s, Integer maxLen) {
    if (!s) {
        return ""
    }

    if (s.length() <= maxLen) {
        return s
    }

    return s.substring(0, maxLen) + "\n\n... trimmed at ${maxLen} characters ..."
}
