/**
 *  Chromecast mDNS Discovery
 *
 *  Version: 1.0.2
 *  Author: Gordon Thelander
 *  Platform: Hubitat Elevation
 *
 *  Purpose:
 *  Discovery-style Hubitat app for Google Cast / Chromecast devices.
 *
 *  Design goals:
 *  - Dynamically detect the Hubitat hub IP address.
 *  - mDNS multicast probe packet pulses to 224.0.0.251:5353 to attempt to wake dormant decices 
 *  - Read Hubitat's own mDNS cache from /hub/mdnsDevices and /hub/mdnsDevices/json.
 *  - Prefer JSON when available, but fall back to parsing the HTML table shown by Hubitat.
 *  - Display only clean Google Cast / Chromecast records with friendly name, IP and port.
 *  - Keep a short history of previously discovered devices without polluting the current list.
 *  - Does not create child devices and does not control Chromecast devices.
 */

import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String APP_VERSION = '2.0.4'
@Field static final String GOOGLECAST_SERVICE = '_googlecast._tcp.local'
@Field static final String GOOGLEZONE_SERVICE = '_googlezone._tcp.local'
@Field static final String SERVICES_SERVICE = '_services._dns-sd._udp.local'

definition(
    name: 'Chromecast mDNS Discovery',
    namespace: 'gordon-thelander',
    author: 'Gordon Thelander',
    description: 'Discovery-only Google Cast / Chromecast inventory using Hubitat mDNS cache. No child devices are created.',
    category: 'Convenience',
    iconUrl: '',
    iconX2Url: '',
    singleInstance: true,
    importUrl: ''
)

preferences {
    page(name: 'mainPage')
    page(name: 'diagnosticsPage')
}

def installed() {
    log.info "${app.name} ${APP_VERSION} installed"
    initialise()
}

def updated() {
    log.info "${app.name} ${APP_VERSION} updated"
    unsubscribe()
    unschedule()
    initialise()
}

def initialise() {
    state.currentDevices = state.currentDevices instanceof Map ? state.currentDevices : [:]
    state.deviceHistory = state.deviceHistory instanceof Map ? state.deviceHistory : [:]
    state.mdnsSections = state.mdnsSections instanceof List ? state.mdnsSections : []
    state.rawSample = state.rawSample ?: null
    state.lastRunAt = state.lastRunAt ?: null
    state.lastSuccessAt = state.lastSuccessAt ?: null
    state.lastWorkingUrl = state.lastWorkingUrl ?: null
    state.lastMessage = state.lastMessage ?: 'Discovery has not run yet.'
    state.lastError = state.lastError ?: null
    state.lastHubIp = state.lastHubIp ?: null

    if (autoRefreshMinutes && safeInt(autoRefreshMinutes) > 0) {
        Integer mins = clampInt(safeInt(autoRefreshMinutes), 1, 60)
        schedule("0 */${mins} * ? * *", 'scheduledDiscovery')
    }

    if (logEnable == true) {
        runIn(1800, 'disableDebugLogging')
    }
}

def disableDebugLogging() {
    app.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info 'Debug logging disabled automatically'
}

def mainPage() {
    return dynamicPage(name: 'mainPage', title: 'Chromecast mDNS Discovery', install: true, uninstall: true) {
        section('Purpose') {
            paragraph "<b>Version:</b> ${APP_VERSION}<br><b>Mode:</b> Discovery only. This app reads Hubitat's mDNS cache and lists Google Cast / Chromecast records. It does not create devices."
        }

        section('Configuration') {
            input name: 'preferJsonEndpoint', type: 'bool', title: 'Prefer /hub/mdnsDevices/json first?', defaultValue: true, required: true, submitOnChange: true
            input name: 'sendProbeBeforeRead', type: 'bool', title: 'Send short mDNS probe before reading cache?', defaultValue: true, required: true, submitOnChange: true
            input name: 'probeBursts', type: 'number', title: 'mDNS probe bursts', description: 'Default 2. Increase only if discovery is unreliable.', defaultValue: 2, required: true, submitOnChange: true
            input name: 'settleDelayMs', type: 'number', title: 'Reserved - no blocking wait used by this version', defaultValue: 0, required: true, submitOnChange: true
            input name: 'previousRetentionDays', type: 'number', title: 'Keep previously discovered devices for days', defaultValue: 7, required: true, submitOnChange: true
            input name: 'autoRefreshMinutes', type: 'number', title: 'Background refresh interval, minutes', description: '0 disables scheduled refresh.', defaultValue: 0, required: true, submitOnChange: true
        }

        section('Optional Chromecast wake shim') {
            input name: 'wakeBeforeDiscovery', type: 'bool', title: 'Wake selected Chromecast child devices before discovery?', defaultValue: false, required: true, submitOnChange: true
            input name: 'chromecastWakeDevices', type: 'capability.speechSynthesis', title: 'Chromecast child devices to wake', description: 'Optional. Sends speak(" ") to selected existing Chromecast Integration child devices.', multiple: true, required: false, submitOnChange: true
            input name: 'wakeDelayMs', type: 'number', title: 'Reserved - no blocking wait used by this version', defaultValue: 0, required: true, submitOnChange: true
        }

        section('Controls') {
            input name: 'runDiscoveryBtn', type: 'button', title: 'Run Chromecast discovery', submitOnChange: true
            input name: 'clearCurrentBtn', type: 'button', title: 'Clear current results', submitOnChange: true
            input name: 'clearAllBtn', type: 'button', title: 'Clear all results and history', submitOnChange: true
        }

        section('Discovery status') {
            paragraph buildStatusHtml()
        }

        section('Clean resolved Chromecast devices') {
            paragraph buildDeviceTableHtml()
        }

        section('Diagnostics') {
            input name: 'storeRawSample', type: 'bool', title: 'Store raw endpoint sample?', defaultValue: true, required: true, submitOnChange: true
            input name: 'showRawSections', type: 'bool', title: 'Show mDNS service section summary?', defaultValue: false, required: true, submitOnChange: true
            input name: 'logEnable', type: 'bool', title: 'Enable debug logging for 30 minutes?', defaultValue: false, required: true, submitOnChange: true
            href name: 'diagnosticsHref', page: 'diagnosticsPage', title: 'Open diagnostics', description: 'Endpoint source, parse summary and raw sample.'
        }
    }
}

def diagnosticsPage() {
    return dynamicPage(name: 'diagnosticsPage', title: 'Chromecast mDNS Discovery Diagnostics', install: false, uninstall: false) {
        section('Source') {
            paragraph buildSourceHtml()
        }

        section('Last message') {
            paragraph "<pre style='white-space:pre-wrap;font-size:12px;'>${htmlEncode(state.lastMessage ?: '')}</pre>"
            if (state.lastError) {
                paragraph "<pre style='white-space:pre-wrap;font-size:12px;color:#8a1f11;'>${htmlEncode(state.lastError ?: '')}</pre>"
            }
        }

        section('mDNS sections') {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(prettyValue(state.mdnsSections ?: []))}</pre>"
        }

        section('Current parsed devices') {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(prettyValue(state.currentDevices ?: [:]))}</pre>"
        }

        section('Previously discovered devices') {
            paragraph "<pre style='white-space:pre-wrap;font-size:11px;'>${htmlEncode(prettyValue(state.deviceHistory ?: [:]))}</pre>"
        }

        if (state.rawSample) {
            section('Raw endpoint sample') {
                paragraph "<pre style='white-space:pre-wrap;font-size:10px;'>${htmlEncode(state.rawSample ?: '')}</pre>"
            }
        }
    }
}

def appButtonHandler(String btn) {
    if (logEnable == true) log.debug "Button pressed: ${btn}"

    switch (btn) {
        case 'runDiscoveryBtn':
            runDiscoveryNow()
            break
        case 'clearCurrentBtn':
            clearCurrentResults()
            break
        case 'clearAllBtn':
            clearAllResults()
            break
        default:
            log.warn "Unhandled button: ${btn}"
            break
    }
}

def scheduledDiscovery() {
    runDiscoveryNow()
}

def runDiscoveryNow() {
    state.lastRunAt = formatNow()
    state.lastError = null
    state.lastMessage = 'Discovery started. Reading Hubitat mDNS cache now.'
    state.discoveryRunning = true

    try {
        if (wakeBeforeDiscovery == true) {
            wakeSelectedChromecastDevices()
        }

        if (sendProbeBeforeRead != false) {
            sendMdnsProbes()
        }

        // Hubitat app code is unreliable when a button press depends on a later runIn()
        // while the dynamic preference page is open. Read immediately so the button action
        // completes in the same execution context and the page updates deterministically.
        completeDiscoveryRead()
    } catch (Exception e) {
        state.discoveryRunning = false
        state.currentDevices = [:]
        state.lastError = "Unexpected discovery error: ${e.message}"
        state.lastMessage = "Discovery failed. ${state.lastError}"
        log.warn state.lastMessage
    }
}

def completeDiscoveryRead() {
    try {
        Map result = fetchMdnsCache()

        if (result.ok == true) {
            Map previousCurrent = state.currentDevices instanceof Map ? state.currentDevices : [:]
            Map newCurrent = result.devices instanceof Map ? result.devices : [:]

            state.currentDevices = newCurrent
            state.mdnsSections = result.sections instanceof List ? result.sections : []
            state.lastWorkingUrl = result.url ?: state.lastWorkingUrl
            state.lastSuccessAt = formatNow()
            state.rawSample = storeRawSample == false ? null : trimForStorage(result.rawText?.toString(), 120000)

            mergeIntoHistory(previousCurrent)
            mergeIntoHistory(newCurrent)
            pruneHistory()

            Integer clean = newCurrent.size()
            Integer sections = state.mdnsSections?.size() ?: 0
            state.lastMessage = "Discovery completed. Clean Chromecast records: ${clean}. mDNS service sections parsed: ${sections}. Source: ${state.lastWorkingUrl ?: 'unknown'}."
            log.info state.lastMessage
        } else {
            state.currentDevices = [:]
            state.mdnsSections = result.sections instanceof List ? result.sections : []
            state.rawSample = result.rawText ? trimForStorage(result.rawText?.toString(), 120000) : null
            state.lastError = result.error ?: 'Unknown discovery failure.'
            state.lastMessage = "Discovery failed. ${state.lastError}"
            log.warn state.lastMessage
        }
    } catch (Exception e) {
        state.currentDevices = [:]
        state.lastError = "Unexpected discovery read error: ${e.message}"
        state.lastMessage = "Discovery failed. ${state.lastError}"
        log.warn state.lastMessage
    } finally {
        state.discoveryRunning = false
    }
}

void wakeSelectedChromecastDevices() {
    if (!chromecastWakeDevices) {
        if (logEnable == true) log.debug 'Wake requested but no Chromecast child devices selected.'
        return
    }

    Integer count = 0
    List failures = []

    chromecastWakeDevices.each { dev ->
        try {
            dev.speak(' ')
            count++
            if (logEnable == true) log.debug "Wake shim sent to ${dev.displayName}"
        } catch (Exception e) {
            failures << "${dev.displayName}: ${e.message}"
        }
    }

    if (failures) {
        state.lastError = "Wake failures: ${failures.join(' | ')}"
        log.warn state.lastError
    }

    if (logEnable == true) log.debug "Wake shim sent to ${count} Chromecast child device(s)."
}

void sendMdnsProbes() {
    Integer bursts = clampInt(safeInt(probeBursts ?: 2), 0, 20)

    for (Integer i = 0; i < bursts; i++) {
        sendMdnsQuery('Google Cast PTR', '0000000000010000000000000b5f676f6f676c6563617374045f746370056c6f63616c00000c0001')
        sendMdnsQuery('Google Zone PTR', '0000000000010000000000000b5f676f6f676c657a6f6e65045f746370056c6f63616c00000c0001')
        sendMdnsQuery('Services PTR', '000000000001000000000000095f7365727669636573075f646e732d7364045f756470056c6f63616c00000c0001')
    }
}

void sendMdnsQuery(String label, String queryHex) {
    try {
        sendHubCommand(new hubitat.device.HubAction(
            queryHex,
            hubitat.device.Protocol.LAN,
            [
                type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
                destinationAddress: '224.0.0.251:5353',
                encoding: hubitat.device.HubAction.Encoding.HEX_STRING,
                ignoreWarning: true
            ]
        ))
        if (logEnable == true) log.debug "mDNS probe sent: ${label}"
    } catch (Exception e) {
        log.warn "mDNS probe failed for ${label}: ${e.message}"
    }
}

Map fetchMdnsCache() {
    List<String> urls = getMdnsEndpointCandidates()
    List failures = []

    for (String url in urls) {
        Map result = fetchAndParseEndpoint(url)
        if (result.ok == true) {
            result.url = url
            return result
        }
        failures << "${url} -> ${result.error ?: 'not ok'}"
    }

    return [
        ok: false,
        error: failures ? failures.join(' | ') : 'No mDNS endpoint candidates available.',
        rawText: null,
        devices: [:],
        sections: []
    ]
}

Map fetchAndParseEndpoint(String url) {
    Map out = [ok: false, url: url, error: null, rawText: null, devices: [:], sections: []]

    try {
        Map params = [
            uri: url,
            timeout: 15,
            headers: ['Accept': 'application/json,text/html,text/plain,*/*']
        ]

        httpGet(params) { resp ->
            Integer status = safeInt(resp?.status)
            if (status < 200 || status >= 300) {
                out.error = "HTTP ${status}"
                return
            }

            Object data = resp?.data
            String raw = data?.toString() ?: ''
            out.rawText = raw

            Map parsed
            if (looksLikeHtml(raw)) {
                parsed = parseMdnsHtml(raw)
            } else {
                parsed = parseMdnsPossiblyJson(data, raw)
            }

            out.devices = parsed.devices instanceof Map ? parsed.devices : [:]
            out.sections = parsed.sections instanceof List ? parsed.sections : []

            if (!out.devices && !out.sections) {
                out.error = 'Endpoint was reachable but no mDNS records were parsed.'
                return
            }

            out.ok = true
        }
    } catch (Exception e) {
        out.error = e.message
    }

    return out
}

Boolean looksLikeHtml(String raw) {
    String s = raw?.trim()?.toLowerCase() ?: ''
    return s.startsWith('<!doctype') || s.startsWith('<html') || s.contains('<table') || s.contains('<tr')
}

Map parseMdnsPossiblyJson(Object data, String raw) {
    Object json = data

    if (data instanceof String) {
        String s = raw?.trim() ?: ''
        if (!s) return [devices: [:], sections: []]
        json = new JsonSlurper().parseText(s)
    }

    return parseMdnsJsonFlexible(json)
}

Map parseMdnsJsonFlexible(Object json) {
    Map devices = [:]
    List sections = []

    if (!json) return [devices: devices, sections: sections]

    if (json instanceof Map) {
        Map root = json as Map

        if (root.serviceTypes instanceof List) {
            parseServiceTypeList(root.serviceTypes as List, devices, sections)
            return [devices: devices, sections: sections]
        }

        if (root.services instanceof List) {
            parseServiceTypeList(root.services as List, devices, sections)
            return [devices: devices, sections: sections]
        }

        root.each { k, v ->
            String serviceType = cleanupService(k?.toString())
            List endpoints = extractEndpointList(v)
            parseEndpointListForService(serviceType, endpoints, devices, sections)
        }

        return [devices: devices, sections: sections]
    }

    if (json instanceof List) {
        List records = json as List
        parseEndpointListForService(GOOGLECAST_SERVICE, records, devices, sections)
    }

    return [devices: devices, sections: sections]
}

void parseServiceTypeList(List serviceTypes, Map devices, List sections) {
    serviceTypes.each { svcObj ->
        if (!(svcObj instanceof Map)) return
        Map svc = svcObj as Map
        String serviceType = cleanupService(stringFirst(svc.serviceType, svc.type, svc.name, svc.service, ''))
        List endpoints = extractEndpointList(svc.endpoints ?: svc.devices ?: svc.records ?: svc.instances)
        parseEndpointListForService(serviceType, endpoints, devices, sections, safeNullableInt(svc.count))
    }
}

List extractEndpointList(Object obj) {
    if (obj instanceof List) return obj as List
    if (obj instanceof Map) {
        Map m = obj as Map
        if (m.endpoints instanceof List) return m.endpoints as List
        if (m.devices instanceof List) return m.devices as List
        if (m.records instanceof List) return m.records as List
        if (m.instances instanceof List) return m.instances as List
        return [m]
    }
    return []
}

void parseEndpointListForService(String serviceType, List endpoints, Map devices, List sections, Integer declaredCount = null) {
    String svc = cleanupService(serviceType)
    Integer parsedCount = 0
    Integer cleanCastCount = 0

    endpoints.each { epObj ->
        if (!(epObj instanceof Map)) return

        Map item = normaliseEndpoint(epObj as Map, svc)
        if (item.name || item.ip || item.host) parsedCount++

        if (isGoogleCastService(svc, item) && isCleanDevice(item)) {
            String key = makeDeviceKey(item)
            devices[key] = item + [key: key]
            cleanCastCount++
        }
    }

    if (svc || parsedCount > 0 || declaredCount != null) {
        sections << [
            serviceType: svc,
            declaredCount: declaredCount != null ? declaredCount : endpoints.size(),
            parsedCount: parsedCount,
            cleanGoogleCastCount: cleanCastCount
        ]
    }
}

Map normaliseEndpoint(Map m, String serviceType) {
    Map txt = firstMap(m.txtProperties, m.txtRecord, m.txt, m.properties)

    String rawName = stringFirst(
        m.friendlyName,
        m.displayName,
        m.name,
        txt.fn,
        txt.nm,
        txt.name,
        m.eventName,
        m.instanceName,
        m.instance
    )

    String host = cleanupHost(stringFirst(m.server, m.host, m.hostname, m.target, m.domainName))
    String ip = stringFirst(m.ip4Address, m.ipv4Address, m.ipAddress, m.address, m.ip, m.hostAddress)
    String port = stringFirst(m.port, m.servicePort)
    String model = stringFirst(m.model, m.modelName, txt.md, txt.model, inferModelFromText(rawName, host))
    String deviceId = stringFirst(m.deviceId, m.id, txt.id, txt.uuid)
    String lastUpdated = stringFirst(m.lastUpdated, m.updated, m.lastSeen, m.lastSeenDuringDiscovery)
    String status = stringFirst(m.status, txt.st)
    String receiverStatus = stringFirst(m.receiverStatus, txt.rs)

    Map item = [
        key: '',
        name: cleanupName(rawName),
        ip: cleanupIp(ip),
        port: port ?: '',
        model: model ?: 'Google Cast Device',
        type: '',
        status: status ?: '',
        receiverStatus: receiverStatus ?: '',
        host: host,
        serviceType: cleanupService(serviceType),
        mdnsId: deviceId ?: '',
        instance: cleanupName(stringFirst(m.eventName, m.instanceName, m.instance, rawName)),
        source: 'mDNS JSON',
        firmware: stringFirst(m.version, txt.ve, txt.version, ''),
        macAddress: stringFirst(m.macAddress, m.mac, ''),
        lastUpdated: lastUpdated ?: '',
        lastSeen: lastUpdated ?: formatNow(),
        discoveredAt: formatNow(),
        lastActiveAt: formatNow(),
        lastActiveMs: nowMs()
    ]

    item.type = inferDeviceType(item)
    return item
}

Map parseMdnsHtml(String html) {
    Map devices = [:]
    List sections = []

    String currentService = ''
    Map sectionStats = [:]

    // Hubitat's rendered page is usually a table per service type. Keep the parser deliberately tolerant.
    String normalised = html ?: ''
    normalised = normalised.replace('\r', '\n')

    // Track service headers even if they appear outside table rows.
    normalised.eachLine { line ->
        String text = stripHtml(line).trim()
        if (text.contains('_googlecast._tcp.local')) currentService = GOOGLECAST_SERVICE
        if (text.contains('_googlezone._tcp.local')) currentService = GOOGLEZONE_SERVICE
        if (text.contains('_services._dns-sd._udp.local')) currentService = SERVICES_SERVICE
    }

    currentService = currentService ?: GOOGLECAST_SERVICE

    def rowMatcher = normalised =~ /(?is)<tr[^>]*>(.*?)<\/tr>/
    while (rowMatcher.find()) {
        String row = rowMatcher.group(1)
        String rowText = stripHtml(row).replaceAll(/\s+/, ' ').trim()

        if (!rowText) continue
        if (rowText.toLowerCase().contains('device') && rowText.toLowerCase().contains('last updated')) continue

        if (rowText.contains('_googlecast._tcp.local')) {
            currentService = GOOGLECAST_SERVICE
            ensureSectionStats(sectionStats, currentService)
            continue
        }

        if (!currentService?.contains('googlecast')) continue

        Map item = parseHtmlDeviceRow(row, currentService)
        if (isCleanDevice(item)) {
            String key = makeDeviceKey(item)
            devices[key] = item + [key: key]
            Map stats = ensureSectionStats(sectionStats, currentService)
            stats.parsedCount = safeInt(stats.parsedCount) + 1
            stats.cleanGoogleCastCount = safeInt(stats.cleanGoogleCastCount) + 1
        }
    }

    // Fallback for pages where Hubitat does not emit regular <tr> rows into resp.data.
    if (!devices) {
        parseHtmlByLines(normalised, currentService, devices, sectionStats)
    }

    sectionStats.each { svc, stats ->
        sections << [
            serviceType: svc,
            declaredCount: stats.declaredCount,
            parsedCount: stats.parsedCount,
            cleanGoogleCastCount: stats.cleanGoogleCastCount
        ]
    }

    if (!sections) {
        sections << [serviceType: currentService ?: 'html', declaredCount: null, parsedCount: devices.size(), cleanGoogleCastCount: devices.size()]
    }

    return [devices: devices, sections: sections]
}

void parseHtmlByLines(String html, String serviceType, Map devices, Map sectionStats) {
    List<String> lines = []
    html.eachLine { line ->
        String text = stripHtml(line).replaceAll(/\s+/, ' ').trim()
        if (text) lines << text
    }

    String svc = serviceType ?: GOOGLECAST_SERVICE
    Map stats = ensureSectionStats(sectionStats, svc)

    for (Integer i = 0; i < lines.size(); i++) {
        String line = lines[i]
        def ipMatch = line =~ /(\d{1,3}(?:\.\d{1,3}){3})\s*:?\s*(\d{2,5})/
        if (!ipMatch.find()) continue

        String ip = ipMatch.group(1)
        String port = ipMatch.group(2)
        if (!isValidSimpleIp(ip)) continue

        String name = line.replaceAll(/\d{1,3}(?:\.\d{1,3}){3}\s*:?\s*\d{2,5}.*/, '').trim()
        if (!name && i > 0) name = lines[i - 1]

        Map item = makeHtmlItem(name, ip, port, '', svc)
        if (isCleanDevice(item)) {
            String key = makeDeviceKey(item)
            devices[key] = item + [key: key]
            stats.parsedCount = safeInt(stats.parsedCount) + 1
            stats.cleanGoogleCastCount = safeInt(stats.cleanGoogleCastCount) + 1
        }
    }
}

Map parseHtmlDeviceRow(String row, String serviceType) {
    List cells = []
    def cellMatcher = row =~ /(?is)<td[^>]*>(.*?)<\/td>/
    while (cellMatcher.find()) {
        cells << stripHtml(cellMatcher.group(1)).replaceAll(/\s+/, ' ').trim()
    }

    String deviceCell = cells ? cells[0]?.toString() : stripHtml(row).replaceAll(/\s+/, ' ').trim()
    String lastUpdated = cells.size() >= 2 ? cells[1]?.toString() : ''

    def ipMatch = deviceCell =~ /(\d{1,3}(?:\.\d{1,3}){3})\s*:?\s*(\d{2,5})/
    if (!ipMatch.find()) return [:]

    String ip = ipMatch.group(1)
    String port = ipMatch.group(2)

    String name = deviceCell.replaceAll(/\d{1,3}(?:\.\d{1,3}){3}\s*:?\s*\d{2,5}.*/, '').trim()
    return makeHtmlItem(name, ip, port, lastUpdated, serviceType)
}

Map makeHtmlItem(String name, String ip, String port, String lastUpdated, String serviceType) {
    String cleanName = cleanupName(name)
    Map item = [
        key: '',
        name: cleanName,
        ip: cleanupIp(ip),
        port: port ?: '',
        model: inferModelFromText(cleanName, ''),
        type: '',
        status: '',
        receiverStatus: '',
        host: extractHostFromName(name),
        serviceType: cleanupService(serviceType ?: GOOGLECAST_SERVICE),
        mdnsId: '',
        instance: cleanName,
        source: 'mDNS HTML',
        firmware: '',
        macAddress: '',
        lastUpdated: lastUpdated ?: '',
        lastSeen: lastUpdated ?: formatNow(),
        discoveredAt: formatNow(),
        lastActiveAt: formatNow(),
        lastActiveMs: nowMs()
    ]

    item.type = inferDeviceType(item)
    return item
}

Map ensureSectionStats(Map sectionStats, String serviceType) {
    String svc = cleanupService(serviceType ?: 'unknown')
    if (!(sectionStats[svc] instanceof Map)) {
        sectionStats[svc] = [declaredCount: null, parsedCount: 0, cleanGoogleCastCount: 0]
    }
    return sectionStats[svc] as Map
}

Boolean isGoogleCastService(String serviceType, Map item = null) {
    // Deliberately strict.
    // Hubitat /hub/mdnsDevices also returns _hue._tcp.local and _matter._tcp.local.
    // Those rows can have generic/default model text and must not be promoted into the
    // Chromecast inventory just because the fallback model contains the word 'Cast'.
    String svc = cleanupService(serviceType)?.toLowerCase() ?: ''
    return svc == GOOGLECAST_SERVICE || svc == "${GOOGLECAST_SERVICE}." || svc.contains('_googlecast._tcp.local')
}

Boolean isCleanDevice(Map item) {
    if (!item) return false
    if (!item.name || isBadName(item.name.toString())) return false
    if (!item.ip || !isValidSimpleIp(item.ip.toString())) return false
    if (!item.port) return false
    return true
}

Boolean isBadName(String name) {
    String n = name?.toLowerCase()?.trim() ?: ''
    if (!n) return true
    if (n == 'device' || n == 'details') return true
    if (n.contains('_googlecast._tcp.local')) return true
    if (n.contains('_googlezone._tcp.local')) return true
    if (n == 'google cast device' || n == 'google cast / dial') return true
    return false
}

String makeDeviceKey(Map item) {
    String id = item.mdnsId ?: ''
    if (id) return "cast-${sanitizeKey(id)}"

    String basis = "${item.name ?: 'cast'}-${item.ip ?: 'ip'}-${item.port ?: 'port'}"
    return "cast-${sanitizeKey(basis)}"
}

void mergeIntoHistory(Map devices) {
    Map history = state.deviceHistory instanceof Map ? state.deviceHistory : [:]

    (devices ?: [:]).each { key, item ->
        if (!(item instanceof Map)) return
        if (!isCleanDevice(item as Map)) return

        Map existing = history[key] instanceof Map ? history[key] as Map : [:]
        Long now = nowMs()

        history[key] = existing + item + [
            key: key,
            firstDiscoveredAt: existing.firstDiscoveredAt ?: item.discoveredAt ?: formatNow(),
            lastActiveAt: formatNow(),
            lastActiveMs: now
        ]
    }

    state.deviceHistory = history
}

void pruneHistory() {
    Map history = state.deviceHistory instanceof Map ? state.deviceHistory : [:]
    Integer days = clampInt(safeInt(previousRetentionDays ?: 7), 1, 365)
    Long cutoff = nowMs() - (days * 24L * 60L * 60L * 1000L)
    Map retained = [:]

    history.each { key, item ->
        Long lastMs = safeLong(item?.lastActiveMs)
        if (lastMs <= 0L) lastMs = nowMs()
        if (lastMs >= cutoff) retained[key] = item
    }

    state.deviceHistory = retained
}

Map getPreviouslyDiscovered() {
    pruneHistory()
    Map current = state.currentDevices instanceof Map ? state.currentDevices : [:]
    Map history = state.deviceHistory instanceof Map ? state.deviceHistory : [:]
    Map previous = [:]

    history.each { key, item ->
        if (!current.containsKey(key)) previous[key] = item
    }

    return previous
}

void clearCurrentResults() {
    state.currentDevices = [:]
    state.mdnsSections = []
    state.rawSample = null
    state.lastMessage = 'Current discovery results cleared. History retained.'
    state.lastError = null
    log.info state.lastMessage
}

void clearAllResults() {
    state.currentDevices = [:]
    state.deviceHistory = [:]
    state.mdnsSections = []
    state.rawSample = null
    state.lastRunAt = null
    state.lastSuccessAt = null
    state.lastWorkingUrl = null
    state.lastMessage = 'All discovery results and history cleared.'
    state.lastError = null
    log.info state.lastMessage
}

List<String> getMdnsEndpointCandidates() {
    String hubIp = getHubIpAddress()

    if (!hubIp) {
        state.lastError = 'Could not dynamically determine Hubitat hub IP address from location.hubs or location.hub.'
        return []
    }

    state.lastHubIp = hubIp

    List<String> jsonFirst = [
        "http://${hubIp}/hub/mdnsDevices/json",
        "http://${hubIp}:8080/hub/mdnsDevices/json",
        "http://${hubIp}/hub/mdnsDevices",
        "http://${hubIp}:8080/hub/mdnsDevices"
    ]

    List<String> htmlFirst = [
        "http://${hubIp}/hub/mdnsDevices",
        "http://${hubIp}:8080/hub/mdnsDevices",
        "http://${hubIp}/hub/mdnsDevices/json",
        "http://${hubIp}:8080/hub/mdnsDevices/json"
    ]

    return preferJsonEndpoint == false ? htmlFirst.unique() : jsonFirst.unique()
}

String getHubIpAddress() {
    List candidates = []

    try {
        location?.hubs?.each { h ->
            try { if (h?.localIP) candidates << h.localIP.toString() } catch (Exception ignored) {}
            try { if (h?.getDataValue('localIP')) candidates << h.getDataValue('localIP').toString() } catch (Exception ignored) {}
            try { if (h?.getDataValue('localIp')) candidates << h.getDataValue('localIp').toString() } catch (Exception ignored) {}
        }
    } catch (Exception ignored) {}

    try { if (location?.hub?.localIP) candidates << location.hub.localIP.toString() } catch (Exception ignored) {}
    try { if (location?.hub?.getDataValue('localIP')) candidates << location.hub.getDataValue('localIP').toString() } catch (Exception ignored) {}
    try { if (location?.hub?.getDataValue('localIp')) candidates << location.hub.getDataValue('localIp').toString() } catch (Exception ignored) {}

    candidates = candidates.findAll { it && isValidSimpleIp(it.toString()) }.collect { it.toString() }.unique()
    return candidates ? candidates[0] : null
}

String buildStatusHtml() {
    Map current = state.currentDevices instanceof Map ? state.currentDevices : [:]
    Map previous = getPreviouslyDiscovered()
    StringBuilder b = new StringBuilder()
    b << "<div style='font-size:13px;'>"
    b << "<table style='font-size:13px;border-collapse:collapse;'>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>Detected hub IP</td><td>${htmlEncode(state.lastHubIp ?: getHubIpAddress() ?: 'unknown')}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>Current clean records</td><td>${current.size()}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>Previously discovered</td><td>${previous.size()}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>mDNS sections parsed</td><td>${state.mdnsSections?.size() ?: 0}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>Last successful source</td><td style='font-family:monospace;'>${htmlEncode(state.lastWorkingUrl ?: 'none yet')}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:18px;'>Last run</td><td>${htmlEncode(state.lastRunAt ?: 'not yet')}</td></tr>"
    b << "</table>"

    b << "<p style='font-size:12px;'><b>Last message:</b> ${htmlEncode(state.lastMessage ?: '')}</p>"

    if (state.lastError) {
        b << "<p style='font-size:12px;color:#8a1f11;'><b>Last error:</b> ${htmlEncode(state.lastError ?: '')}</p>"
    }

    b << '</div>'
    return b.toString()
}

String buildDeviceTableHtml() {
    Map current = state.currentDevices instanceof Map ? state.currentDevices : [:]
    Map previous = getPreviouslyDiscovered()

    StringBuilder b = new StringBuilder()

    if (!current && !previous) {
        return 'No Chromecast mDNS records discovered yet.'
    }

    if (current) {
        b << "<p><b>Clean resolved Chromecast devices: ${current.size()}</b></p>"
        b << buildTable(current, false)
    } else {
        b << '<p><b>Clean resolved Chromecast devices: 0</b></p>'
    }

    if (previous) {
        b << "<p style='margin-top:14px;color:#888888;'><b>Previously discovered - retained for ${clampInt(safeInt(previousRetentionDays ?: 7), 1, 365)} day(s)</b></p>"
        b << buildTable(previous, true)
    }

    if (showRawSections == true) {
        b << '<p><b>mDNS service sections:</b></p>'
        b << "<pre style='font-size:11px;white-space:pre-wrap;'>${htmlEncode(prettyValue(state.mdnsSections ?: []))}</pre>"
    }

    return b.toString()
}

String buildTable(Map devices, Boolean faded) {
    String colour = faded ? 'color:#888888;' : ''
    StringBuilder b = new StringBuilder()

    b << "<table style='font-size:13px;border-collapse:collapse;table-layout:auto;width:auto;white-space:nowrap;${colour}'>"
    b << "<tr style='${colour}'><th align='left'>Name</th><th align='left'>IP</th><th align='left'>Port</th><th align='left'>Model</th><th align='left'>Type</th><th align='left'>Source</th><th align='left'>Last seen</th></tr>"

    devices.sort { a, c -> compareIpAddress(a.value.ip?.toString(), c.value.ip?.toString()) ?: (a.value.name <=> c.value.name) }.each { key, item ->
        b << '<tr>'
        b << "<td style='padding-right:18px;'>${htmlEncode(item.name ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(item.ip ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(item.port ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(item.model ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(item.type ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(item.source ?: '')}</td>"
        b << "<td style='padding-right:18px;'>${htmlEncode(displayTimestamp(item.lastSeen ?: item.lastUpdated ?: item.lastActiveAt ?: item.discoveredAt ?: ''))}</td>"
        b << '</tr>'
    }

    b << '</table>'
    return b.toString()
}

String buildSourceHtml() {
    String hubIp = getHubIpAddress()
    List urls = getMdnsEndpointCandidates()
    StringBuilder b = new StringBuilder()

    b << "<table style='font-size:13px;border-collapse:collapse;width:100%;'>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Detected hub IP</td><td style='font-family:monospace;'>${htmlEncode(hubIp ?: 'unknown')}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Preferred endpoint order</td><td style='font-family:monospace;'>${htmlEncode(urls.join(' | '))}</td></tr>"
    b << "<tr><td style='font-weight:bold;padding-right:10px;'>Last working source</td><td style='font-family:monospace;'>${htmlEncode(state.lastWorkingUrl ?: 'none yet')}</td></tr>"
    b << '</table>'

    return b.toString()
}

String cleanupName(String s) {
    if (!s) return ''
    String v = htmlDecode(s)
    v = v.replaceAll(/\s+/, ' ').trim()
    v = v.replaceAll(/\s*@\s*.*?\.local\.?$/, '').trim()
    return v
}

String extractHostFromName(String s) {
    if (!s) return ''
    def m = s =~ /@\s*([^\s]+\.local\.?)/
    if (m.find()) return cleanupHost(m.group(1))
    return ''
}

String cleanupHost(String s) {
    if (!s) return ''
    return s.replaceAll(/\s+/, '').replaceAll(/\.$/, '')
}

String cleanupService(String s) {
    if (!s) return ''
    return s.trim().replaceAll(/\.$/, '')
}

String cleanupIp(String s) {
    if (!s) return ''
    def m = s =~ /(\d{1,3}(?:\.\d{1,3}){3})/
    if (m.find()) return m.group(1)
    return ''
}

String inferModelFromText(String name, String host) {
    String n = "${name ?: ''} ${host ?: ''}".toLowerCase()
    if (n.contains('speakers') || n.contains('group')) return 'Google Cast Group'
    if (n.contains('nest hub max')) return 'Google Nest Hub Max'
    if (n.contains('display') || n.contains('fuchsia')) return 'Google Nest Hub'
    if (n.contains('google tv') || n.contains('chromecast') || n.contains('tv')) return 'Google TV / Streamer'
    if (n.contains('nest audio')) return 'Nest Audio'
    if (n.contains('speaker') || n.contains('home mini')) return 'Google Home / Nest Speaker'
    if (n.contains('home')) return 'Google Home'
    return 'Google Cast Device'
}

String inferDeviceType(Map d) {
    String name = d.name?.toString()?.toLowerCase() ?: ''
    String host = d.host?.toString()?.toLowerCase() ?: ''
    String model = d.model?.toString()?.toLowerCase() ?: ''
    String port = d.port?.toString() ?: ''

    if (port == '32026' || model.contains('cast group') || name.contains('speakers')) return 'Cast Group'
    if (name.contains('tv') || model.contains('tv') || model.contains('streamer') || model.contains('chromecast')) return 'Google TV / Streamer'
    if (name.contains('display') || host.contains('fuchsia') || model.contains('hub')) return 'Smart Display'
    if (name.contains('speaker') || model.contains('speaker') || model.contains('home') || model.contains('audio')) return 'Speaker'
    return 'Google Cast'
}

Map firstMap(Object... values) {
    for (Object v in values) {
        if (v instanceof Map) return v as Map
    }
    return [:]
}

String stringFirst(Object... values) {
    for (Object v in values) {
        if (v != null) {
            String s = v.toString()
            if (s) return s
        }
    }
    return ''
}

String stripHtml(String s) {
    if (!s) return ''
    return htmlDecode(s.replaceAll(/(?is)<script.*?<\/script>/, ' ').replaceAll(/(?is)<style.*?<\/style>/, ' ').replaceAll(/<br\s*\/?>/, ' ').replaceAll(/<[^>]+>/, ' '))
}

String htmlEncode(Object value) {
    return value?.toString()?.replace('&', '&amp;')?.replace('<', '&lt;')?.replace('>', '&gt;')?.replace('"', '&quot;') ?: ''
}

String htmlDecode(String value) {
    if (!value) return ''
    return value
        .replace('&nbsp;', ' ')
        .replace('&amp;', '&')
        .replace('&lt;', '<')
        .replace('&gt;', '>')
        .replace('&quot;', '"')
        .replace('&#39;', "'")
}

String sanitizeKey(Object value) {
    String s = value?.toString()?.toLowerCase() ?: 'unknown'
    s = s.replaceAll(/[^a-z0-9]+/, '-')
    s = s.replaceAll(/^-+/, '').replaceAll(/-+$/, '')
    if (!s) s = 'unknown'
    return s.length() > 100 ? s.substring(0, 100) : s
}

Boolean isValidSimpleIp(String ip) {
    if (!ip) return false
    def parts = ip.split(/\./)
    if (parts.size() != 4) return false
    for (String p in parts) {
        if (!(p ==~ /\d{1,3}/)) return false
        Integer n = safeInt(p)
        if (n < 0 || n > 255) return false
    }
    return true
}

Integer compareIpAddress(String leftIp, String rightIp) {
    List left = ipParts(leftIp)
    List right = ipParts(rightIp)
    for (Integer i = 0; i < 4; i++) {
        Integer r = left[i] <=> right[i]
        if (r != 0) return r
    }
    return 0
}

List ipParts(String ip) {
    if (!isValidSimpleIp(ip)) return [999, 999, 999, 999]
    return ip.split(/\./).collect { safeInt(it) }
}

Integer safeInt(Object value) {
    if (value == null) return 0
    try { return value as Integer } catch (Exception ignored) { return 0 }
}

Integer safeNullableInt(Object value) {
    if (value == null) return null
    try { return value as Integer } catch (Exception ignored) { return null }
}

Long safeLong(Object value) {
    if (value == null) return 0L
    try { return value as Long } catch (Exception ignored) { return 0L }
}

Integer clampInt(Integer value, Integer min, Integer max) {
    return Math.max(min, Math.min(max, value ?: 0))
}

Long nowMs() {
    return new Date().time
}

String formatNow() {
    return new Date().format('yyyy-MM-dd HH:mm:ss', location.timeZone)
}

String displayTimestamp(Object value) {
    String s = value?.toString() ?: ''
    s = s.trim().replaceAll(/\s+[A-Z]{2,5}$/, '')
    if (s ==~ /\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}/) return s.substring(0, 16)
    return s
}

String prettyValue(Object value) {
    if (value == null) return ''
    if (value instanceof Map) {
        StringBuilder b = new StringBuilder()
        (value as Map).each { k, v -> b << "${k}: ${v}\n" }
        return b.toString()
    }
    if (value instanceof List) {
        StringBuilder b = new StringBuilder()
        (value as List).eachWithIndex { item, i -> b << "${i + 1}. ${item}\n" }
        return b.toString()
    }
    return value.toString()
}

String trimForStorage(String s, Integer maxLen) {
    if (!s) return ''
    if (s.length() <= maxLen) return s
    return s.substring(0, maxLen) + "\n\n... trimmed at ${maxLen} characters ..."
}
