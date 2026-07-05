/*
 *  Sifely WiFi Lock Driver for Hubitat
 *
 *  Version: 0.1-beta
 *  Author: Dave Howard with ChatGPT/Jarvis assistance
 *
 *  Description:
 *  Unofficial Hubitat cloud driver for Sifely S WiFi smart locks.
 *
 *  Tested with:
 *    - Sifely S Model WiFi
 *    - Sifely S WiFi
 *
 *  Notes:
 *    - This is an unofficial cloud-based integration.
 *    - Built-in WiFi Sifely locks do not require the Sifely gateway.
 *    - BLE-only Sifely locks may require the Sifely WiFi gateway, but that is untested.
 *    - Remote unlock is disabled by default for safety.
 *    - Sifely cloud state updates may require ~10–20 seconds after a lock/unlock event.
 *
 *  Security:
 *    - Do not post logs containing tokens, account details, passwords, hashes, or lock IDs.
 *    - Debug logging does not intentionally print API response bodies.
 *
 *  License:
 *    MIT
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
        attribute "tokenStatus", "string"
        attribute "lockStateRaw", "string"
        attribute "onlineStatus", "string"
        attribute "sifelyLockName", "string"
        attribute "lastRefresh", "string"
        attribute "lastError", "string"

        command "testLogin"
        command "clearToken"
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
              description: "Enter the Sifely lockId for this lock.",
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
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    sendEvent(name: "apiStatus", value: "Sifely WiFi Lock driver ready")
}

def testLogin() {
    login()
}

def clearToken() {
    state.remove("sifelyToken")
    state.remove("accessToken")
    state.remove("userId")

    sendEvent(name: "tokenStatus", value: "Token cleared")
    sendEvent(name: "apiStatus", value: "Stored token cleared")

    log.info "Sifely token cleared"
}

def refresh() {
    if (!lockId) {
        sendEvent(name: "apiStatus", value: "Missing Lock ID")
        log.warn "Missing Sifely Lock ID"
        return
    }

    if (!ensureToken()) {
        return
    }

    queryLockState()
    refreshLockDetails()
}

def lock() {
    if (!allowRemoteLock) {
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

private Boolean ensureToken() {
    if (state.sifelyToken) {
        return true
    }

    return login()
}

private Boolean login() {
    if (!sifelyAccount || !sifelyPassword) {
        sendEvent(name: "apiStatus", value: "Missing Sifely email or password")
        log.warn "Missing Sifely email or password"
        return false
    }

    String baseUrl = getBaseUrl()
    if (!baseUrl) {
        sendEvent(name: "apiStatus", value: "Missing API base URL")
        log.warn "Missing API base URL"
        return false
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
                state.accessToken = data.data.user?.accessToken
                state.userId = data.data.userId?.toString()

                sendEvent(name: "apiStatus", value: "Login OK")
                sendEvent(name: "tokenStatus", value: "Token stored")
                sendEvent(name: "lastError", value: "")

                log.info "Sifely login OK"
                ok = true
            } else {
                String msg = data?.message ?: "Unexpected login response"
                sendEvent(name: "apiStatus", value: "Login failed: ${msg}")
                sendEvent(name: "lastError", value: msg)
                log.warn "Sifely login failed: ${msg}"
                ok = false
            }
        }
    } catch (Exception e) {
        sendEvent(name: "apiStatus", value: "Login error - check logs")
        sendEvent(name: "lastError", value: e.message)
        log.error "Sifely login error: ${e.message}"
        ok = false
    }

    return ok
}

private void queryLockState() {
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
            def rawState = extractStateValue(data)

            if (resp.status == 200 && rawState != null) {
                String raw = rawState.toString()
                String mapped = mapLockState(raw)

                sendEvent(name: "lockStateRaw", value: raw)
                sendEvent(name: "lock", value: mapped)
                sendEvent(name: "apiStatus", value: "Refresh OK")
                sendEvent(name: "lastRefresh", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
                sendEvent(name: "lastError", value: "")

                log.info "Sifely lock ${id} state=${raw}, mapped=${mapped}"
            } else {
                String msg = data?.message ?: "Unexpected state response"
                sendEvent(name: "apiStatus", value: "Lock state failed: ${msg}")
                sendEvent(name: "lastError", value: msg)
                log.warn "Sifely lock state failed: ${msg}"
            }
        }
    } catch (Exception e) {
        handleCloudOrApiError("Lock state", e)
    }
}

private void refreshLockDetails() {
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
            def locks = extractLockList(data)

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
                } else {
                    sendEvent(name: "lastError", value: "Lock ID not found in Sifely lock list")
                    log.warn "Sifely Lock ID ${id} not found in lock list"
                }
            } else {
                String msg = data?.message ?: "Unexpected lock list response"
                sendEvent(name: "lastError", value: msg)
                log.warn "Sifely lock detail refresh failed: ${msg}"
            }
        }
    } catch (Exception e) {
        handleCloudOrApiError("Lock details", e)
    }
}

private void sendLockCommand(String action) {
    if (!lockId) {
        sendEvent(name: "apiStatus", value: "Missing Lock ID")
        log.warn "Missing Lock ID"
        return
    }

    if (!ensureToken()) {
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
            String message = data?.message?.toString() ?: ""

            if (resp.status == 200 && (data?.code == 200 || message.toLowerCase().contains("success"))) {
                sendEvent(name: "apiStatus", value: "${action} command sent - waiting for cloud update")
                sendEvent(name: "lastError", value: "")

                log.info "Sifely ${action} command sent for lock ${id}"

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
        handleCloudOrApiError("${action} command", e)
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
        state.remove("sifelyToken")
        sendEvent(name: "tokenStatus", value: "Token expired/cleared")
        sendEvent(name: "apiStatus", value: "${label} auth error - run refresh again")
        sendEvent(name: "lastError", value: msg)
        log.warn "${label}: auth error, token cleared"
        return
    }

    sendEvent(name: "apiStatus", value: "${label} error - check logs")
    sendEvent(name: "lastError", value: msg)
    log.error "Sifely ${label} error: ${msg}"
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
