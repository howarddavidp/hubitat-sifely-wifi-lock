/*
 *  Sifely WiFi Lock Driver for Hubitat (Open API)
 *
 *  Version: 2.0.1
 *  Driver name: Sifely WiFi Lock v2
 *  Author: Dave Howard with ChatGPT/Jarvis assistance
 *
 *  Version 2.0.0:
 *    - Uses the Sifely Open API (https://cus-openapi.sifely.com) with an API key
 *      ("Authorization: sk-..." header, no Bearer), replacing the legacy
 *      app-smart-server.sifely.com email/password login, which since 2026-09-29
 *      rejects all lock calls with "authentication failed".
 *    - Same capabilities, attributes and command names as v1.0.0 so rules keep working.
 *    - Legacy preferences (apiBaseUrl, sifelyAccount, sifelyPassword, tokenRefreshHours,
 *      forceAuthBeforeCommands) are kept but ignored.
 *    - 2.0.1: lastError set to "none" on success (empty values were not recorded).
 *    - Endpoints: GET /v3/lock/queryOpenState?lockId=, POST /v3/lock/list?pageNo=&pageSize=,
 *      POST /v3/lock/lock?lockId=, POST /v3/lock/unlock?lockId=
 */

metadata {
    definition(
        name: "Sifely WiFi Lock v2",
        namespace: "dave",
        author: "Dave Howard / ChatGPT"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Initialize"
        capability "Refresh"
        capability "Battery"
        capability "Lock"

        attribute "apiStatus", "string"
        attribute "driverVersion", "string"
        attribute "tokenStatus", "string"
        attribute "tokenAgeHours", "number"
        attribute "lockStateRaw", "string"
        attribute "onlineStatus", "string"
        attribute "reachable", "string"
        attribute "sifelyLockName", "string"
        attribute "lastRefresh", "string"
        attribute "lastLogin", "string"
        attribute "lastError", "string"
        attribute "lockListSummary", "string"

        command "testLogin"
        command "forceTokenRefresh"
        command "clearToken"
        command "listLocks"
    }

    preferences {
        input name: "apiKey",
              type: "password",
              title: "Sifely Open API key (sk-...) from connect.sifely.com",
              required: true

        input name: "openApiBaseUrl",
              type: "text",
              title: "Sifely Open API Base URL",
              defaultValue: "https://cus-openapi.sifely.com",
              required: false

        input name: "lockId",
              type: "text",
              title: "Sifely Lock ID",
              required: true

        input name: "allowRemoteLock",
              type: "bool",
              title: "Allow Hubitat to lock this Sifely lock",
              defaultValue: true

        input name: "allowRemoteUnlock",
              type: "bool",
              title: "Allow Hubitat to unlock this Sifely lock",
              defaultValue: false

        input name: "refreshAfterCommandSeconds",
              type: "number",
              title: "Seconds to wait before refresh after lock/unlock",
              defaultValue: 20,
              range: "10..60",
              required: true

        input name: "logEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: false

        /* Legacy v1 settings - kept so existing values are harmless; not used by v2 */
        input name: "apiBaseUrl", type: "text", title: "(Legacy, unused) old API base URL", required: false
        input name: "sifelyAccount", type: "text", title: "(Legacy, unused) Sifely account email", required: false
        input name: "sifelyPassword", type: "password", title: "(Legacy, unused) Sifely password", required: false
        input name: "tokenRefreshHours", type: "number", title: "(Legacy, unused) token refresh hours", required: false
        input name: "forceAuthBeforeCommands", type: "bool", title: "(Legacy, unused) force login before commands", required: false
    }
}

private String driverVer() { return "2.0.2" }

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    unschedule()
    state.remove("sifelyToken")
    state.remove("accessToken")
    state.remove("userId")
    state.remove("tokenCreatedEpochMs")
    sendEvent(name: "driverVersion", value: driverVer())
    sendEvent(name: "tokenAgeHours", value: 0)
    sendEvent(name: "tokenStatus", value: apiKey ? "API key set" : "API key missing")
    sendEvent(name: "apiStatus", value: "Sifely WiFi Lock v${driverVer()} ready (Open API)")
}

/* ---------- compatibility commands ---------- */

def testLogin() {
    // With the Open API there is no login; validate the key with a lock list call.
    Boolean ok = listLocksInternal(true)
    if (ok) {
        sendEvent(name: "lastLogin", value: nowText())
        sendEvent(name: "tokenStatus", value: "API key valid")
    }
}

def forceTokenRefresh() {
    sendEvent(name: "tokenStatus", value: "Not needed - Open API key does not expire")
    testLogin()
}

def clearToken() {
    state.remove("sifelyToken")
    state.remove("tokenCreatedEpochMs")
    sendEvent(name: "tokenAgeHours", value: 0)
    sendEvent(name: "tokenStatus", value: "No session token used (API key auth)")
    sendEvent(name: "apiStatus", value: "Nothing to clear")
}

def scheduledTokenRefresh() {
    // Left for any v1 schedules still pending; nothing to do.
    unschedule("scheduledTokenRefresh")
}

def listLocks() {
    listLocksInternal(true)
}

/* ---------- main capability commands ---------- */

def refresh() {
    if (!checkConfig()) return
    queryLockState()
    refreshLockDetails()
    listLocksInternal(false)
}

def lock() {
    if (allowRemoteLock == false) {
        sendEvent(name: "apiStatus", value: "Remote lock blocked by preference")
        log.warn "Remote lock blocked by preference"
        return
    }
    sendLockCommand("lock")
}

def unlock() {
    if (!allowRemoteUnlock) {
        sendEvent(name: "apiStatus", value: "Remote unlock blocked by preference")
        log.warn "Remote unlock blocked by preference"
        return
    }
    sendLockCommand("unlock")
}

/* ---------- API calls ---------- */

private Boolean checkConfig() {
    if (!apiKey) {
        setError("Missing Sifely API key")
        return false
    }
    if (!lockId) {
        setError("Missing Lock ID")
        return false
    }
    return true
}

private Map baseParams(String path, Map query) {
    return [
        uri: "${getBaseUrl()}${path}",
        contentType: "application/json",
        headers: [Authorization: apiKey.toString().trim()],
        query: query,
        timeout: 30
    ]
}

private void queryLockState() {
    String id = lockId.toString().trim()
    try {
        httpGet(baseParams("/v3/lock/queryOpenState", [lockId: id])) { resp ->
            def data = resp.data
            if (logEnable) log.debug "Sifely queryOpenState HTTP ${resp.status}: ${data}"
            def rawState = data?.state
            if (resp.status == 200 && rawState != null && !isApiErrorCode(data)) {
                String raw = rawState.toString()
                String mapped = mapLockState(raw)
                sendEvent(name: "lockStateRaw", value: raw)
                sendEvent(name: "lock", value: mapped)
                sendEvent(name: "apiStatus", value: "Refresh OK")
                sendEvent(name: "lastRefresh", value: nowText())
                sendEvent(name: "lastError", value: "none")
                state.lastStateOk = true
                sendEvent(name: "reachable", value: "true")
                log.info "Sifely lock ${id} state=${raw}, mapped=${mapped}"
            } else {
                state.lastStateOk = false
                sendEvent(name: "reachable", value: "false")
                setError("Lock state failed: ${getApiMessage(data, 'Unexpected state response')}")
            }
        }
    } catch (Exception e) {
        state.lastStateOk = false
        sendEvent(name: "reachable", value: "false")
        handleHttpError("Lock state", e)
    }
}

private void refreshLockDetails() {
    String id = lockId.toString().trim()
    try {
        httpPost(baseParams("/v3/lock/list", [pageNo: 1, pageSize: 100]) + [requestContentType: "application/json", body: "{}"]) { resp ->
            def data = resp.data
            def locks = extractLockList(data)
            if (resp.status == 200 && locks != null) {
                def thisLock = locks.find { it.lockId?.toString() == id }
                if (thisLock) {
                    sendEvent(name: "sifelyLockName", value: thisLock.lockAlias ?: thisLock.lockName ?: "Sifely Lock")
                    sendEvent(name: "onlineStatus", value: (thisLock.hasGateway?.toString() == "1" && state.lastStateOk == true && thisLock.isOnline?.toString() in ["0", "false"]) ? "via gateway" : normalizeOnline(thisLock.isOnline, thisLock.hasGateway))
                    if (thisLock.electricQuantity != null) {
                        sendEvent(name: "battery", value: thisLock.electricQuantity as Integer, unit: "%")
                    }
                } else {
                    setError("Lock ID ${id} not found in Sifely lock list")
                }
            } else {
                setError("Lock details failed: ${getApiMessage(data, 'Unexpected lock list response')}")
            }
        }
    } catch (Exception e) {
        handleHttpError("Lock details", e)
    }
}

private Boolean listLocksInternal(Boolean report) {
    if (!apiKey) {
        setError("Missing Sifely API key")
        return false
    }
    Boolean ok = false
    sendEvent(name: "apiStatus", value: "Requesting Sifely lock list...")
    try {
        httpPost(baseParams("/v3/lock/list", [pageNo: 1, pageSize: 100]) + [requestContentType: "application/json", body: "{}"]) { resp ->
            def data = resp.data
            def locks = extractLockList(data)
            if (resp.status == 200 && locks != null) {
                List<String> entries = []
                locks.each { item ->
                    String name = item.lockAlias ?: item.lockName ?: "Unnamed Lock"
                    entries << "${name} | ID=${item.lockId} | ${(item.hasGateway?.toString() == "1" && item.isOnline?.toString() in ["0", "false"]) ? "via gateway" : normalizeOnline(item.isOnline, item.hasGateway)}"
                }
                sendEvent(name: "lockListSummary", value: entries ? entries.join(" ; ") : "No locks returned")
                sendEvent(name: "apiStatus", value: "List Locks OK - ${entries.size()} lock(s) found")
                sendEvent(name: "lastError", value: "none")
                log.info "Sifely List Locks: ${entries.size()} lock(s) found"
                ok = true
            } else {
                setError("List Locks failed: ${getApiMessage(data, 'Unexpected lock list response')}")
            }
        }
    } catch (Exception e) {
        handleHttpError("List Locks", e)
    }
    return ok
}

private void sendLockCommand(String action) {
    if (!checkConfig()) return
    String id = lockId.toString().trim()
    sendEvent(name: "apiStatus", value: "Sending ${action} command...")
    try {
        httpPost(baseParams("/v3/lock/${action}", [lockId: id]) + [requestContentType: "application/json", body: "{}"]) { resp ->
            def data = resp.data
            if (logEnable) log.debug "Sifely ${action} HTTP ${resp.status}: ${data}"
            if (resp.status in [200, 201] && isCommandSuccess(data)) {
                sendEvent(name: "apiStatus", value: "${action} command accepted - waiting for cloud update")
                sendEvent(name: "lastError", value: "none")
                log.info "Sifely ${action} command accepted for lock ${id}"
                Integer delaySeconds = refreshAfterCommandSeconds ? refreshAfterCommandSeconds.toInteger() : 20
                runIn(delaySeconds, "refresh")
            } else {
                setError("${action} failed: ${getApiMessage(data, 'Unexpected ' + action + ' response')}")
            }
        }
    } catch (Exception e) {
        handleHttpError("${action} command", e)
    }
}

/* ---------- helpers ---------- */

private Boolean isCommandSuccess(data) {
    if (data == null) return true
    if (data instanceof Map) {
        if (data.errcode != null) return data.errcode.toString() == "0"
        if (data.code != null) return data.code.toString() in ["0", "200"]
        if (data.success != null) return data.success == true
    }
    return true
}

private Boolean isApiErrorCode(data) {
    if (data instanceof Map && data.code != null) {
        return !(data.code.toString() in ["0", "200"])
    }
    return false
}

private void handleHttpError(String label, Exception e) {
    String msg = e.message ?: "Unknown error"
    Integer status = null
    def body = null
    try {
        status = e.response?.status
        body = e.response?.data
    } catch (ignored) { }
    String apiMsg = getApiMessage(body, "")
    String text
    if (status == 401) {
        text = "${label}: API key rejected (401) ${apiMsg}".trim()
        sendEvent(name: "tokenStatus", value: "API key invalid")
    } else if (status == 402) {
        text = "${label}: Sifely subscription inactive (402)"
    } else if (status == 429) {
        text = "${label}: Sifely rate limit exceeded (429)"
    } else if (status != null) {
        text = "${label}: HTTP ${status} ${apiMsg ?: msg}"
    } else {
        text = "${label} error: ${msg}"
    }
    setError(text)
}

private void setError(String text) {
    sendEvent(name: "apiStatus", value: text)
    sendEvent(name: "lastError", value: text)
    log.warn "Sifely: ${text}"
}

private String getApiMessage(data, String fallback) {
    if (data instanceof Map) {
        if (data.message != null) return data.message.toString()
        if (data.msg != null) return data.msg.toString()
        if (data.errmsg != null) return data.errmsg.toString()
        if (data.error != null) return data.error.toString()
    }
    return fallback
}

private def extractLockList(data) {
    if (data?.list != null) return data.list
    if (data?.data?.list != null) return data.data.list
    if (data?.data instanceof List) return data.data
    return null
}

private String mapLockState(String raw) {
    if (raw == "0") return "locked"
    if (raw == "1") return "unlocked"
    return "unknown"
}

private String normalizeOnline(value, hasGateway = null) {
    String gw = (hasGateway?.toString() == "1") ? " (gateway)" : ""
    if (value == null) return "online unknown${gw}"
    String v = value.toString()
    if (v == "1" || v.equalsIgnoreCase("true")) return "online${gw}"
    if (v == "0" || v.equalsIgnoreCase("false")) return "offline${gw}"
    return "online=${v}${gw}"
}

private String getBaseUrl() {
    String u = openApiBaseUrl?.toString()?.trim()
    if (!u) u = "https://cus-openapi.sifely.com"
    return u.replaceAll('/+$', '')
}

private String nowText() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}
