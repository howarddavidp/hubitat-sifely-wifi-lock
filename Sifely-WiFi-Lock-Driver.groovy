/*
 *  Sifely WiFi Lock Driver for Hubitat
 *
 *  Version: 1.0.0
 *  Driver name: Sifely WiFi Lock
 *  Author: Dave Howard with ChatGPT/Jarvis assistance
 *
 *  Version 1.0.0 production release:
 *    - Keeps the 4-hour proactive token refresh.
 *    - Optionally forces a fresh login before every lock/unlock command.
 *    - Adds a List Locks command for discovering lock names and IDs.
 *    - Retains token-age tracking and one retry after authentication failure.
 */

import java.security.MessageDigest

metadata {
    definition(
        name: "Sifely WiFi Lock",
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
        input name: "apiBaseUrl",
              type: "text",
              title: "Sifely API Base URL",
              defaultValue: "https://app-smart-server.sifely.com",
              required: true

        input name: "sifelyAccount",
              type: "text",
              title: "Sifely account email",
              required: true

        input name: "sifelyPassword",
              type: "password",
              title: "Sifely password",
              required: true

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

        input name: "tokenRefreshHours",
              type: "number",
              title: "Proactively refresh token after this many hours",
              defaultValue: 4,
              range: "1..24",
              required: true

        input name: "forceAuthBeforeCommands",
              type: "bool",
              title: "Force a fresh Sifely login before every lock/unlock command",
              defaultValue: true

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
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    unschedule()

    sendEvent(name: "driverVersion", value: "1.0.0")
    sendEvent(name: "apiStatus", value: "Sifely WiFi Lock v1.0.0 ready")

    /*
     * Old experimental builds stored accessToken separately.
     * It is not needed for lock actions, so remove it if present.
     */
    state.remove("accessToken")

    updateTokenAgeEvent()
    scheduleNextTokenRefresh()
}

def testLogin() {
    login(true)
}

def forceTokenRefresh() {
    clearStoredTokenOnly()
    sendEvent(name: "tokenStatus", value: "Token cleared for forced refresh")
    login(true)
}

def clearToken() {
    clearStoredTokenOnly()
    sendEvent(name: "tokenStatus", value: "Token cleared")
    sendEvent(name: "tokenAgeHours", value: 0)
    sendEvent(name: "apiStatus", value: "Stored token cleared")
    log.info "Sifely token cleared"
}

def scheduledTokenRefresh() {
    if (logEnable) {
        log.debug "Sifely scheduled token refresh started"
    }

    Boolean ok = login(true)
    sendEvent(name: "tokenStatus", value: ok ? "Token refreshed by schedule" : "Scheduled token refresh failed")
    scheduleNextTokenRefresh()
}

def listLocks() {
    listLocksInternal(false)
}

private void listLocksInternal(Boolean alreadyRetried) {
    if (!ensureToken()) {
        return
    }

    String baseUrl = getBaseUrl()
    def params = [
        uri: "${baseUrl}/v3/lock/list",
        requestContentType: "application/json",
        contentType: "application/json",
        headers: [Authorization: state.sifelyToken],
        query: [pageNo: 1, pageSize: 100],
        body: [:],
        timeout: 30
    ]

    sendEvent(name: "apiStatus", value: "Requesting Sifely lock list...")

    try {
        httpPost(params) { resp ->
            def data = resp.data
            String msg = getApiMessage(data, "")

            if (isAuthFailureText(msg)) {
                handleAuthFailureAndRetry("List Locks", alreadyRetried, { listLocksInternal(true) })
                return
            }

            def locks = extractLockList(data)
            if (resp.status == 200 && locks != null) {
                List<String> entries = []
                locks.each { item ->
                    String name = item.lockAlias ?: item.lockName ?: "Unnamed Lock"
                    String id = item.lockId?.toString() ?: "unknown"
                    String online = normalizeOnline(item.isOnline)
                    entries << "${name} | ID=${id} | ${online}"
                }

                String summary = entries ? entries.join(" ; ") : "No locks returned"
                sendEvent(name: "lockListSummary", value: summary)
                sendEvent(name: "apiStatus", value: "List Locks OK - ${entries.size()} lock(s) found")
                sendEvent(name: "lastError", value: "")

                log.info "Sifely List Locks: ${entries.size()} lock(s) found"
                entries.each { log.info "Sifely Lock: ${it}" }
            } else {
                String failMsg = msg ?: "Unexpected lock list response"
                sendEvent(name: "apiStatus", value: "List Locks failed: ${failMsg}")
                sendEvent(name: "lastError", value: failMsg)
                log.warn "Sifely List Locks failed: ${failMsg}"
            }
        }
    } catch (Exception e) {
        if (isAuthFailureText(e.message)) {
            handleAuthFailureAndRetry("List Locks", alreadyRetried, { listLocksInternal(true) })
        } else {
            handleCloudOrApiError("List Locks", e)
        }
    }
}

def refresh() {
    if (!lockId) {
        sendEvent(name: "apiStatus", value: "Missing Lock ID")
        sendEvent(name: "lastError", value: "Missing Lock ID")
        log.warn "Missing Sifely Lock ID"
        return
    }

    if (!ensureToken()) {
        return
    }

    queryLockState(false)
    refreshLockDetails(false)
}

def lock() {
    if (!allowRemoteLock) {
        sendEvent(name: "apiStatus", value: "Remote lock blocked by preference")
        log.warn "Remote lock blocked by preference"
        return
    }

    sendLockCommand("lock", false)
}

def unlock() {
    if (!allowRemoteUnlock) {
        sendEvent(name: "apiStatus", value: "Remote unlock blocked by preference")
        log.warn "Remote unlock blocked by preference"
        return
    }

    sendLockCommand("unlock", false)
}

private Boolean ensureToken() {
    updateTokenAgeEvent()

    if (state.sifelyToken && !isTokenExpiredByAge()) {
        return true
    }

    if (state.sifelyToken && isTokenExpiredByAge()) {
        sendEvent(name: "tokenStatus", value: "Token older than refresh interval - refreshing")
        log.info "Sifely token older than refresh interval; refreshing"
    }

    return login(true)
}

private Boolean login(Boolean forceLogin = false) {
    if (!sifelyAccount || !sifelyPassword) {
        sendEvent(name: "apiStatus", value: "Missing Sifely email or password")
        sendEvent(name: "lastError", value: "Missing Sifely email or password")
        log.warn "Missing Sifely email or password"
        return false
    }

    String baseUrl = getBaseUrl()

    if (!baseUrl) {
        sendEvent(name: "apiStatus", value: "Missing API base URL")
        sendEvent(name: "lastError", value: "Missing API base URL")
        log.warn "Missing API base URL"
        return false
    }

    if (!forceLogin && state.sifelyToken && !isTokenExpiredByAge()) {
        updateTokenAgeEvent()
        return true
    }

    def params = [
        uri: "${baseUrl}/system/smart/loginByGuest",
        requestContentType: "application/x-www-form-urlencoded",
        contentType: "application/json",
        body: [
            username: sifelyAccount.trim(),
            password: md5Hash(sifelyPassword as String)
        ],
        timeout: 30
    ]

    sendEvent(name: "apiStatus", value: "Logging in...")

    Boolean ok = false

    try {
        httpPost(params) { resp ->
            if (logEnable) {
                log.debug "Sifely login HTTP status: ${resp.status}"
            }

            def data = resp.data

            if (resp.status == 200 && data?.code == 200 && data?.data?.sifelyToken) {
                state.sifelyToken = data.data.sifelyToken
                state.tokenCreatedEpochMs = now()

                String loginTime = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)

                sendEvent(name: "apiStatus", value: "Login OK")
                sendEvent(name: "tokenStatus", value: "Token stored")
                sendEvent(name: "lastLogin", value: loginTime)
                sendEvent(name: "lastError", value: "")
                updateTokenAgeEvent()

                log.info "Sifely login OK"
                ok = true
            } else {
                String msg = getApiMessage(data, "Unexpected login response")
                sendEvent(name: "apiStatus", value: "Login failed: ${msg}")
                sendEvent(name: "lastError", value: msg)
                log.warn "Sifely login failed: ${msg}"
                ok = false
            }
        }
    } catch (Exception e) {
        String msg = e.message ?: "Unknown login error"
        sendEvent(name: "apiStatus", value: "Login error - check logs")
        sendEvent(name: "lastError", value: msg)
        log.error "Sifely login error: ${msg}"
        ok = false
    }

    return ok
}

private void queryLockState(Boolean alreadyRetried) {
    String baseUrl = getBaseUrl()
    String id = lockId.trim()

    def params = [
        uri: "${baseUrl}/v3/lock/queryOpenState",
        contentType: "application/json",
        headers: [
            Authorization: state.sifelyToken
        ],
        query: [
            lockId: id
        ],
        timeout: 30
    ]

    try {
        httpGet(params) { resp ->
            if (logEnable) {
                log.debug "Sifely queryOpenState HTTP status: ${resp.status}"
            }

            def data = resp.data
            String msg = getApiMessage(data, "")
            def rawState = extractStateValue(data)

            if (isAuthFailureText(msg)) {
                handleAuthFailureAndRetry("Lock state", alreadyRetried, { queryLockState(true) })
                return
            }

            if (resp.status == 200 && rawState != null) {
                String raw = rawState.toString()
                String mapped = mapLockState(raw)

                sendEvent(name: "lockStateRaw", value: raw)
                sendEvent(name: "lock", value: mapped)
                sendEvent(name: "apiStatus", value: "Refresh OK")
                sendEvent(name: "lastRefresh", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
                sendEvent(name: "lastError", value: "")
                updateTokenAgeEvent()

                log.info "Sifely lock ${id} state=${raw}, mapped=${mapped}"
            } else {
                String failMsg = msg ?: "Unexpected state response"
                sendEvent(name: "apiStatus", value: "Lock state failed: ${failMsg}")
                sendEvent(name: "lastError", value: failMsg)
                log.warn "Sifely lock state failed: ${failMsg}"
            }
        }
    } catch (Exception e) {
        if (isAuthFailureText(e.message)) {
            handleAuthFailureAndRetry("Lock state", alreadyRetried, { queryLockState(true) })
        } else {
            handleCloudOrApiError("Lock state", e)
        }
    }
}

private void refreshLockDetails(Boolean alreadyRetried) {
    String baseUrl = getBaseUrl()
    String id = lockId.trim()

    def params = [
        uri: "${baseUrl}/v3/lock/list",
        requestContentType: "application/json",
        contentType: "application/json",
        headers: [
            Authorization: state.sifelyToken
        ],
        query: [
            pageNo: 1,
            pageSize: 50
        ],
        body: [:],
        timeout: 30
    ]

    try {
        httpPost(params) { resp ->
            if (logEnable) {
                log.debug "Sifely lock list HTTP status: ${resp.status}"
            }

            def data = resp.data
            String msg = getApiMessage(data, "")
            def locks = extractLockList(data)

            if (isAuthFailureText(msg)) {
                handleAuthFailureAndRetry("Lock details", alreadyRetried, { refreshLockDetails(true) })
                return
            }

            if (resp.status == 200 && locks != null) {
                def thisLock = locks.find { it.lockId?.toString() == id }

                if (thisLock) {
                    String name = thisLock.lockAlias ?: thisLock.lockName ?: "Sifely Lock"
                    String online = normalizeOnline(thisLock.isOnline)

                    sendEvent(name: "sifelyLockName", value: name)
                    sendEvent(name: "onlineStatus", value: online)

                    if (thisLock.electricQuantity != null) {
                        Integer batt = thisLock.electricQuantity as Integer
                        sendEvent(name: "battery", value: batt, unit: "%")
                    }

                    updateTokenAgeEvent()
                } else {
                    sendEvent(name: "lastError", value: "Lock ID not found in Sifely lock list")
                    log.warn "Sifely Lock ID ${id} not found in lock list"
                }
            } else {
                String failMsg = msg ?: "Unexpected lock list response"
                sendEvent(name: "lastError", value: failMsg)
                log.warn "Sifely lock detail refresh failed: ${failMsg}"
            }
        }
    } catch (Exception e) {
        if (isAuthFailureText(e.message)) {
            handleAuthFailureAndRetry("Lock details", alreadyRetried, { refreshLockDetails(true) })
        } else {
            handleCloudOrApiError("Lock details", e)
        }
    }
}

private void sendLockCommand(String action, Boolean alreadyRetried) {
    if (!lockId) {
        sendEvent(name: "apiStatus", value: "Missing Lock ID")
        sendEvent(name: "lastError", value: "Missing Lock ID")
        log.warn "Missing Lock ID"
        return
    }

    Boolean authOk
    if (forceAuthBeforeCommands && !alreadyRetried) {
        if (logEnable) {
            log.debug "Sifely ${action}: forcing fresh login before command"
        }
        sendEvent(name: "apiStatus", value: "Refreshing authentication before ${action}...")
        authOk = login(true)
    } else {
        authOk = ensureToken()
    }

    if (!authOk) {
        sendEvent(name: "apiStatus", value: "${action} blocked - authentication failed")
        return
    }

    String baseUrl = getBaseUrl()
    String id = lockId.trim()

    def params = [
        uri: "${baseUrl}/v3/lock/${action}",
        requestContentType: "application/json",
        contentType: "application/json",
        headers: [
            Authorization: state.sifelyToken
        ],
        query: [
            lockId: id
        ],
        body: [:],
        timeout: 30
    ]

    sendEvent(name: "apiStatus", value: "Sending ${action} command...")

    try {
        httpPost(params) { resp ->
            if (logEnable) {
                log.debug "Sifely ${action} HTTP status: ${resp.status}"
            }

            def data = resp.data
            String message = getApiMessage(data, "")

            if (isAuthFailureText(message)) {
                handleAuthFailureAndRetry("${action} command", alreadyRetried, { sendLockCommand(action, true) })
                return
            }

            if (resp.status == 200 && isCommandSuccess(data, message)) {
                sendEvent(name: "apiStatus", value: "${action} command accepted - waiting for cloud update")
                sendEvent(name: "lastError", value: "")
                updateTokenAgeEvent()

                log.info "Sifely ${action} command accepted for lock ${id}"

                Integer delaySeconds = refreshAfterCommandSeconds ? refreshAfterCommandSeconds.toInteger() : 20
                runIn(delaySeconds, "refresh")
            } else {
                String msg = message ?: "Unexpected ${action} response"
                sendEvent(name: "apiStatus", value: "${action} failed: ${msg}")
                sendEvent(name: "lastError", value: msg)
                log.warn "Sifely ${action} failed: ${msg}"
            }
        }
    } catch (Exception e) {
        if (isAuthFailureText(e.message)) {
            handleAuthFailureAndRetry("${action} command", alreadyRetried, { sendLockCommand(action, true) })
        } else {
            handleCloudOrApiError("${action} command", e)
        }
    }
}

private Boolean isCommandSuccess(data, String message) {
    String m = message ? message.toString().toLowerCase() : ""

    if (isAuthFailureText(m)) {
        return false
    }

    if (
        m.contains("failed") ||
        m.contains("failure") ||
        m.contains("error") ||
        m.contains("denied") ||
        m.contains("unable")
    ) {
        return false
    }

    if (data?.code != null) {
        String code = data.code.toString()
        if (code == "0" || code == "200") {
            return true
        }
    }

    if (data?.success == true || data?.data?.success == true) {
        return true
    }

    if (m.contains("success") || m == "ok") {
        return true
    }

    /*
     * Sifely command calls may return HTTP 200 with a minimal body.
     * If HTTP 200 has no explicit failure/auth text, treat the command as accepted.
     */
    return true
}

private void handleAuthFailureAndRetry(String label, Boolean alreadyRetried, Closure retryClosure) {
    sendEvent(name: "tokenStatus", value: "Token rejected/cleared")
    sendEvent(name: "apiStatus", value: "${label} auth failed - refreshing token")
    sendEvent(name: "lastError", value: "Authentication failed; token refresh attempted")

    log.warn "${label}: Sifely authentication failed; clearing token"

    clearStoredTokenOnly()

    if (alreadyRetried) {
        sendEvent(name: "apiStatus", value: "${label} auth failed after retry")
        sendEvent(name: "lastError", value: "Authentication failed after retry")
        log.warn "${label}: authentication failed after retry"
        return
    }

    Boolean loginOk = login(true)

    if (loginOk) {
        sendEvent(name: "apiStatus", value: "${label} retrying after token refresh")
        retryClosure.call()
    } else {
        sendEvent(name: "apiStatus", value: "${label} retry blocked - login failed")
        log.warn "${label}: retry blocked because login failed"
    }
}

private void handleCloudOrApiError(String label, Exception e) {
    String msg = e.message ?: "Unknown error"

    if (msg.contains("status code: 400")) {
        sendEvent(name: "apiStatus", value: "${label} not ready - wait, then refresh")
        sendEvent(name: "lastError", value: "Sifely cloud returned 400; likely timing/update delay")
        log.warn "${label}: Sifely cloud returned 400; wait before polling again"
        return
    }

    if (msg.contains("status code: 401") || msg.contains("status code: 403")) {
        handleAuthFailureAndRetry(label, false, { refresh() })
        return
    }

    sendEvent(name: "apiStatus", value: "${label} error - check logs")
    sendEvent(name: "lastError", value: msg)
    log.error "Sifely ${label} error: ${msg}"
}

private void scheduleNextTokenRefresh() {
    Integer refreshHours = tokenRefreshHours ? tokenRefreshHours.toInteger() : 4
    Integer seconds = refreshHours * 60 * 60

    runIn(seconds, "scheduledTokenRefresh", [overwrite: true])

    if (logEnable) {
        log.debug "Next Sifely scheduled token refresh in ${refreshHours} hour(s)"
    }
}

private void clearStoredTokenOnly() {
    state.remove("sifelyToken")
    state.remove("accessToken")
    state.remove("userId")
    state.remove("tokenCreatedEpochMs")
    updateTokenAgeEvent()
}

private Boolean isTokenExpiredByAge() {
    if (!state.tokenCreatedEpochMs) {
        return true
    }

    Long created = state.tokenCreatedEpochMs as Long
    Long ageMs = now() - created
    Integer refreshHours = tokenRefreshHours ? tokenRefreshHours.toInteger() : 4
    Long maxAgeMs = refreshHours * 60L * 60L * 1000L

    return ageMs >= maxAgeMs
}

private void updateTokenAgeEvent() {
    if (!state.tokenCreatedEpochMs) {
        sendEvent(name: "tokenAgeHours", value: 0)
        return
    }

    Long created = state.tokenCreatedEpochMs as Long
    BigDecimal ageHours = ((now() - created) / 3600000.0).setScale(2, BigDecimal.ROUND_HALF_UP)
    sendEvent(name: "tokenAgeHours", value: ageHours)
}

private Boolean isAuthFailureText(String text) {
    if (!text) {
        return false
    }

    String t = text.toLowerCase()

    return (
        t.contains("authentication failed") ||
        t.contains("unable to access system resources") ||
        t.contains("auth failed") ||
        t.contains("unauthorized") ||
        t.contains("forbidden") ||
        t.contains("token expired") ||
        t.contains("invalid token")
    )
}

private String getApiMessage(data, String fallback) {
    if (data?.message != null) {
        return data.message.toString()
    }

    if (data?.msg != null) {
        return data.msg.toString()
    }

    if (data?.error != null) {
        return data.error.toString()
    }

    return fallback
}

private def extractLockList(data) {
    if (data?.list != null) {
        return data.list
    }

    if (data?.data?.list != null) {
        return data.data.list
    }

    if (data?.data instanceof List) {
        return data.data
    }

    return null
}

private def extractStateValue(data) {
    if (data?.state != null) {
        return data.state
    }

    if (data?.data?.state != null) {
        return data.data.state
    }

    if (data?.data != null && !(data.data instanceof Map)) {
        return data.data
    }

    return null
}

private String mapLockState(String raw) {
    if (raw == "0") {
        return "locked"
    }

    if (raw == "1") {
        return "unlocked"
    }

    return "unknown"
}

private String normalizeOnline(value) {
    if (value == null) {
        return "online unknown"
    }

    String v = value.toString()

    if (v == "1" || v.equalsIgnoreCase("true")) {
        return "online"
    }

    if (v == "0" || v.equalsIgnoreCase("false")) {
        return "offline"
    }

    return "online=${v}"
}

private String getBaseUrl() {
    return apiBaseUrl?.trim()?.replaceAll('/+$', '')
}

private String md5Hash(String input) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    byte[] digest = md.digest(input.getBytes("UTF-8"))

    return digest.collect { String.format("%02x", it & 0xff) }.join()
}

