package de.onur.aquarea.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Inoffizieller Client für Panasonic-Aquarea-Wärmepumpen über die Comfort-Cloud-Schnittstelle
 * (dieselbe, die die Panasonic-Comfort-Cloud-App und die Home-Assistant-Integration nutzen).
 *
 * Alle Methoden blockieren und müssen außerhalb des Main-Threads laufen.
 *
 * @param credentials liefert E-Mail und Passwort für eine automatische Neuanmeldung,
 *   falls der Refresh-Token abgelaufen ist (oder `null`).
 * @param installedAppVersion liefert die Version der installierten Comfort-Cloud-App,
 *   die als `x-app-version` mitgeschickt wird (Panasonic lehnt zu alte Versionen ab).
 */
class ComfortCloudClient(
    private val store: SessionStore,
    private val credentials: () -> Pair<String, String>? = { null },
    private val installedAppVersion: () -> String? = { null },
    private val baseAuth: String = BASE_AUTH,
    private val baseAcc: String = BASE_ACC,
    httpClient: OkHttpClient? = null,
) {
    private val cookies = MemoryCookieJar()
    private val http: OkHttpClient = (httpClient?.newBuilder() ?: OkHttpClient.Builder())
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var appVersion: String? = null

    // ------------------------------------------------------------------ Anmeldung

    /** Meldet sich mit Panasonic-ID (E-Mail + Passwort) an und speichert die Session. */
    @Synchronized
    fun login(email: String, password: String): Session {
        cookies.clear()
        val verifier = randomString(43)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sha256(verifier.toByteArray()))
        val state = randomString(20)

        // 1) authorize
        val authorizeUrl = "$baseAuth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("audience", AUDIENCE)
            .addQueryParameter("protocol", "oauth2")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("auth0Client", AUTH0_CLIENT)
            .addQueryParameter("client_id", APP_CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("state", state)
            .build()
        val authorizeLocation = http.newCall(
            Request.Builder().url(authorizeUrl).header("User-Agent", API_UA).build()
        ).execute().use { r ->
            r.expect(302, "authorize")
            r.header("Location") ?: throw ComfortCloudException("authorize: kein Redirect")
        }

        val code: String = if (authorizeLocation.startsWith(REDIRECT_URI)) {
            queryParam(authorizeLocation, "code")
        } else {
            loginWithPassword(authorizeLocation, email, password)
        }

        // 2) Code gegen Token tauschen
        val tokenBody = buildJsonObject {
            put("scope", "openid")
            put("client_id", APP_CLIENT_ID)
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", REDIRECT_URI)
            put("code_verifier", verifier)
        }
        var session = requestToken(tokenBody, previous = null)

        // 3) ACC-Client-ID holen
        session = session.copy(clientId = retrieveClientId(session))
        store.save(session)
        return session
    }

    private fun loginWithPassword(authorizeLocation: String, email: String, password: String): String {
        val loginPageUrl = resolve(baseAuth, authorizeLocation)
        val state = loginPageUrl.queryParameter("state")
            ?: throw ComfortCloudException("authorize: kein state im Redirect")

        val csrf = http.newCall(Request.Builder().url(loginPageUrl).build()).execute().use { r ->
            r.expect(200, "login page")
            r.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                Cookie.parse(loginPageUrl, c)?.takeIf { it.name == "_csrf" }?.value
            } ?: cookies.find("_csrf")
            ?: throw ComfortCloudException("login page: kein _csrf-Cookie")
        }

        val loginBody = buildJsonObject {
            put("client_id", APP_CLIENT_ID)
            put("redirect_uri", REDIRECT_URI)
            put("tenant", "pdpauthglb-a1")
            put("response_type", "code")
            put("scope", SCOPE)
            put("audience", AUDIENCE)
            put("_csrf", csrf)
            put("state", state)
            put("_intstate", "deprecated")
            put("username", email)
            put("password", password)
            put("lang", "en")
            put("connection", "PanasonicID-Authentication")
        }
        val html = http.newCall(
            Request.Builder().url("$baseAuth/usernamepassword/login")
                .header("Auth0-Client", AUTH0_CLIENT)
                .header("User-Agent", API_UA)
                .post(loginBody.toString().jsonBody())
                .build()
        ).execute().use { r ->
            if (r.code == 400 || r.code == 401 || r.code == 403) {
                throw NeedsLoginException("Anmeldung abgelehnt – E-Mail oder Passwort falsch? (HTTP ${r.code})")
            }
            r.expect(200, "login")
            r.body?.string().orEmpty()
        }

        val form = FormBody.Builder().apply {
            Jsoup.parse(html).select("input[type=hidden]").forEach { add(it.attr("name"), it.attr("value")) }
        }.build()
        val callbackLocation = http.newCall(
            Request.Builder().url("$baseAuth/login/callback")
                .header("User-Agent", BROWSER_UA)
                .post(form)
                .build()
        ).execute().use { r ->
            r.expect(302, "login callback")
            r.header("Location") ?: throw ComfortCloudException("login callback: kein Redirect")
        }

        return http.newCall(Request.Builder().url(resolve(baseAuth, callbackLocation)).build()).execute().use { r ->
            r.expect(302, "login redirect")
            queryParam(r.header("Location") ?: throw ComfortCloudException("login redirect: kein Redirect"), "code")
        }
    }

    private fun requestToken(body: JsonObject, previous: Session?): Session {
        val receivedAt = System.currentTimeMillis() / 1000
        return http.newCall(
            Request.Builder().url("$baseAuth/oauth/token")
                .header("Auth0-Client", AUTH0_CLIENT)
                .header("User-Agent", API_UA)
                .post(body.toString().jsonBody())
                .build()
        ).execute().use { r ->
            if (r.code in 400..403) throw NeedsLoginException("Token abgelehnt (HTTP ${r.code})")
            r.expect(200, "token")
            val json = Json.parseToJsonElement(r.body!!.string()).jsonObject
            Session(
                accessToken = json.str("access_token")!!,
                refreshToken = json.str("refresh_token") ?: previous?.refreshToken
                ?: throw ComfortCloudException("token: kein refresh_token"),
                expiresAt = receivedAt + (json.int("expires_in") ?: 3600),
                scope = json.str("scope") ?: previous?.scope ?: SCOPE,
                clientId = previous?.clientId,
            )
        }
    }

    private fun refresh(session: Session): Session {
        val body = buildJsonObject {
            put("scope", session.scope)
            put("client_id", APP_CLIENT_ID)
            put("refresh_token", session.refreshToken)
            put("grant_type", "refresh_token")
        }
        val refreshed = requestToken(body, previous = session)
        store.save(refreshed)
        return refreshed
    }

    private fun retrieveClientId(session: Session): String {
        repeat(2) { attempt ->
            http.newCall(
                Request.Builder().url("$baseAcc/auth/v2/login")
                    .headers(accHeaders(session, includeClientId = false))
                    .post("""{"language":0}""".jsonBody())
                    .build()
            ).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (attempt == 0 && isOutdatedVersion(r.code, body)) {
                    appVersion = fetchStoreVersion() ?: appVersion
                    return@repeat
                }
                r.expect(200, "acc login", body)
                return Json.parseToJsonElement(body).jsonObject.str("clientId")
                    ?: throw ComfortCloudException("acc login: keine clientId")
            }
        }
        throw ComfortCloudException("acc login: App-Version wird abgelehnt")
    }

    /** Gültige Session holen: ggf. Token erneuern oder mit gespeicherten Zugangsdaten neu anmelden. */
    @Synchronized
    fun session(forceRefresh: Boolean = false): Session {
        val current = store.load()
        if (current != null && !forceRefresh && current.expiresAt - 60 > System.currentTimeMillis() / 1000) {
            return current
        }
        if (current != null) {
            try {
                return refresh(current)
            } catch (_: NeedsLoginException) {
                // Refresh-Token abgelaufen – unten neu anmelden
            }
        }
        val (email, password) = credentials()
            ?: throw NeedsLoginException("Bitte in der App anmelden")
        return login(email, password)
    }

    fun logout() {
        store.save(null)
        cookies.clear()
    }

    // ------------------------------------------------------------------ Geräte

    /** Alle Wärmepumpen (Aquarea, deviceType 2) des Kontos. */
    fun heatPumps(): List<HeatPump> {
        val json = accRequest("GET", "$baseAcc/device/group", null).jsonObject
        return json["groupList"]?.jsonArray.orEmpty().flatMap { g ->
            val group = g.jsonObject
            (group["deviceList"]?.jsonArray?.takeIf { it.isNotEmpty() } ?: group["deviceIdList"]?.jsonArray).orEmpty()
        }.mapNotNull { d ->
            val dev = d as? JsonObject ?: return@mapNotNull null
            if (dev.str("deviceType") != "2") return@mapNotNull null
            val guid = dev.str("deviceGuid") ?: return@mapNotNull null
            HeatPump(guid, dev.str("deviceName") ?: "Wärmepumpe")
        }
    }

    fun status(pump: HeatPump): HeatPumpStatus {
        val json = try {
            transfer("/remote/v1/api/devices?gwid=${pump.guid}&deviceDirect=1", "GET", null)
        } catch (e: NeedsLoginException) {
            throw e
        } catch (_: ComfortCloudException) {
            // Live-Abfrage fehlgeschlagen (Gerät schläft o.ä.) – zwischengespeicherte Werte nehmen
            transfer("/remote/v1/api/devices?gwid=${pump.guid}&deviceDirect=0", "GET", null)
        }
        return parseStatus(pump, json.jsonObject)
    }

    /**
     * Stündlicher Stromverbrauch eines Tages (Heizen, Kühlen, Warmwasser) –
     * dieselben Werte wie im Comfort-Cloud-Monitor in der Ansicht „Tag“.
     */
    fun dayConsumption(
        pump: HeatPump,
        date: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): DayConsumption {
        val offset = zone.rules.getOffset(date.atStartOfDay()).totalSeconds
        val tz = "%s%02d:%02d".format(if (offset < 0) "-" else "+", abs(offset) / 3600, (abs(offset) % 3600) / 60)
        val json = transfer("/remote/v1/api/consumption", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("dataMode", 0) // 0 = Tag (stündlich), 1 = Monat, 2 = Jahr
            put("date", date.format(DateTimeFormatter.BASIC_ISO_DATE))
            put("osTimezone", tz)
        })
        return parseDayConsumption(date, json.jsonObject)
    }

    internal fun parseDayConsumption(date: LocalDate, response: JsonObject): DayConsumption {
        val list = (response["historyDataList"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val hours = list.mapIndexedNotNull { index, item ->
            // dataTime ist z.B. "2026112414" (JJJJMMTTHH) oder nur die Stunde
            val digits = item.str("dataTime")?.filter { it.isDigit() }.orEmpty()
            val hour = when {
                digits.length >= 10 -> digits.substring(8, 10).toIntOrNull()
                digits.length in 1..2 -> digits.toIntOrNull()
                else -> null
            } ?: index
            if (hour !in 0..23) return@mapIndexedNotNull null
            HourConsumption(
                hour = hour,
                heat = item.dbl("heatConsumption"),
                cool = item.dbl("coolConsumption"),
                hotWater = item.dbl("tankConsumption"),
            )
        }
        val byHour = hours.associateBy { it.hour }
        return DayConsumption(date.toString(), (0..23).map { byHour[it] ?: HourConsumption(it) })
    }

    /** Ganze Wärmepumpe ein- oder ausschalten. */
    fun setPower(pump: HeatPump, on: Boolean) {
        val body = buildJsonObject {
            put("status", buildJsonArray {
                add(buildJsonObject {
                    put("deviceGuid", pump.guid)
                    put("operationStatus", if (on) 1 else 0)
                })
            })
        }
        accRequest(
            "POST", "$baseAcc/remote/v1/api/devices/${pump.guid}", body,
            extraHeaders = mapOf(
                "Cache-Control" to "max-age=0",
                "Accept" to "application/json",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to BROWSER_UA,
                "Referer" to "$baseAcc/remote/a2wStatusDisplay",
            ),
        )
    }

    /** Solltemperatur einer Zone setzen (Heizen oder Kühlen, je nach aktuellem Modus). */
    fun setZoneTemperature(pump: HeatPump, zoneId: Int, value: Int, cooling: Boolean) {
        transfer("/remote/v1/api/devices", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("zoneStatus", buildJsonArray {
                add(buildJsonObject {
                    put("zoneId", zoneId)
                    put(if (cooling) "coolSet" else "heatSet", value)
                })
            })
        })
    }

    /** Warmwasser-Solltemperatur setzen. */
    fun setTankTemperature(pump: HeatPump, value: Int) {
        transfer("/remote/v1/api/devices", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("tankStatus", buildJsonObject { put("heatSet", value) })
        })
    }

    /** Warmwasser ein-/ausschalten; die Zonen behalten ihren Zustand. */
    fun setTankPower(pump: HeatPump, current: HeatPumpStatus, on: Boolean) {
        transfer("/remote/v1/api/devices", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("zoneStatus", buildJsonArray {
                current.zones.forEach { z ->
                    add(buildJsonObject { put("zoneId", z.id); put("operationStatus", if (z.on) 1 else 0) })
                }
            })
            put("tankStatus", buildJsonObject { put("operationStatus", if (on) 1 else 0) })
        })
    }

    /**
     * Heizen (alle Zonen) und Warmwasser gemeinsam ein-/ausschalten – wie die Tasten am Bedienpanel:
     * Heizen + Warmwasser, nur Heizen oder nur Warmwasser.
     */
    fun setHeatingAndHotWater(pump: HeatPump, current: HeatPumpStatus, heating: Boolean, hotWater: Boolean) {
        transfer("/remote/v1/api/devices", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("zoneStatus", buildJsonArray {
                current.zones.forEach { z ->
                    add(buildJsonObject { put("zoneId", z.id); put("operationStatus", if (heating) 1 else 0) })
                }
            })
            put("tankStatus", buildJsonObject { put("operationStatus", if (hotWater) 1 else 0) })
        })
    }

    /** Modus (Heizen/Kühlen/Auto) setzen und dabei alle Zonen einschalten. */
    fun setMode(pump: HeatPump, current: HeatPumpStatus, mode: ModeCommand) {
        transfer("/remote/v1/api/devices", "POST", buildJsonObject {
            put("gwid", pump.guid)
            put("operationMode", mode.apiValue)
            put("operationStatus", 1)
            put("zoneStatus", buildJsonArray {
                current.zones.forEach { z ->
                    add(buildJsonObject {
                        put("zoneId", z.id)
                        put("operationStatus", 1)
                        z.heatSet?.let { put("heatSet", it) }
                        z.coolSet?.let { put("coolSet", it) }
                    })
                }
            })
            put("tankStatus", buildJsonObject { put("operationStatus", if (current.tank?.on == true) 1 else 0) })
        })
    }

    // ------------------------------------------------------------------ HTTP-Helfer

    private fun transfer(apiName: String, method: String, bodyParam: JsonObject?): JsonElement {
        val body = buildJsonObject {
            put("apiName", apiName)
            put("requestMethod", method)
            if (bodyParam != null) put("bodyParam", bodyParam)
        }
        return accRequest("POST", "$baseAcc/remote/v1/app/common/transfer", body)
    }

    private fun accRequest(
        method: String,
        url: String,
        body: JsonObject?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonElement {
        var session = session()
        var lastError: Exception? = null
        repeat(3) {
            val request = Request.Builder().url(url)
                .headers(accHeaders(session, includeClientId = true))
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .method(method, body?.toString()?.jsonBody())
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    isOutdatedVersion(r.code, text) -> {
                        appVersion = fetchStoreVersion() ?: appVersion
                        lastError = ComfortCloudException("App-Version abgelehnt")
                    }
                    r.code == 401 || r.code == 403 -> {
                        session = session(forceRefresh = true)
                        lastError = NeedsLoginException("Nicht angemeldet (HTTP ${r.code})")
                    }
                    !r.isSuccessful -> throw ComfortCloudException("HTTP ${r.code} bei /${url.toHttpUrl().encodedPath.trimStart('/')} (App-Version ${currentAppVersion()}): ${text.take(300)}")
                    else -> {
                        val json = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
                        checkApiErrors(json)
                        return json
                    }
                }
            }
        }
        throw lastError ?: ComfortCloudException("Anfrage fehlgeschlagen")
    }

    private fun checkApiErrors(json: JsonElement) {
        val obj = json as? JsonObject ?: return
        val messages = when (val m = obj["message"]) {
            is JsonArray -> m.toList()
            null -> emptyList()
            else -> listOf(m)
        }
        for (m in messages) {
            when (m) {
                is JsonObject -> {
                    val msg = m.str("errorMessage") ?: continue
                    if ("Token expires" in msg) throw NeedsLoginException(msg)
                    throw ApiException(m.str("errorCode"), msg)
                }
                is JsonPrimitive -> {
                    val msg = m.contentOrNull ?: continue
                    if ("Token expires" in msg) throw NeedsLoginException(msg)
                    throw ApiException(null, msg)
                }
                else -> Unit
            }
        }
    }

    private fun accHeaders(session: Session, includeClientId: Boolean): okhttp3.Headers {
        val now = LocalDateTime.now()
        val timestamp = now.format(TIMESTAMP_FORMAT)
        return okhttp3.Headers.Builder().apply {
            add("Accept", "application/json; charset=utf-8")
            add("Content-Type", "application/json")
            add("User-Agent", "G-RAC")
            add("X-APP-NAME", "Comfort Cloud")
            add("X-APP-TIMESTAMP", timestamp)
            add("X-APP-TYPE", "1")
            add("X-APP-VERSION", currentAppVersion())
            add("X-CFC-API-KEY", apiKey(now, session.accessToken))
            add("X-User-Authorization-V2", "Bearer ${session.accessToken}")
            if (includeClientId && session.clientId != null) add("X-Client-Id", session.clientId)
        }.build()
    }

    private fun currentAppVersion(): String =
        appVersion ?: (installedAppVersion()?.let { normalizeVersion(it) } ?: DEFAULT_APP_VERSION).also { appVersion = it }

    private fun isOutdatedVersion(code: Int, body: String): Boolean =
        code == 401 && runCatching {
            Json.parseToJsonElement(body).jsonObject.int("code") == 4106
        }.getOrDefault(false)

    /** Aktuelle Comfort-Cloud-Version aus dem App Store lesen (Fallback, wenn die App nicht installiert ist). */
    private fun fetchStoreVersion(): String? = runCatching {
        http.newCall(Request.Builder().url(APP_STORE_URL).header("User-Agent", BROWSER_UA).build())
            .execute().use { r ->
                if (!r.isSuccessful) return null
                Regex("""Version\s+(\d+\.\d+\.\d+)""").find(r.body!!.string())?.groupValues?.get(1)
            }
    }.getOrNull()

    // ------------------------------------------------------------------ Parser

    internal fun parseStatus(pump: HeatPump, response: JsonObject): HeatPumpStatus {
        val s = when (val st = response["status"]) {
            is JsonObject -> st
            is JsonArray -> st.firstOrNull()?.jsonObject
            else -> null
        } ?: throw ComfortCloudException("Status: Antwort ohne 'status'")

        val zones = (s["zoneStatus"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { z ->
            val id = z.int("zoneId") ?: return@mapNotNull null
            Zone(
                id = id,
                name = z.str("zoneName")?.takeIf { it.isNotBlank() } ?: "Zone $id",
                on = z.int("operationStatus") == 1,
                temperatureNow = z.temp("temperatureNow"),
                heatSet = z.temp("heatSet"),
                heatMin = z.temp("heatMin"),
                heatMax = z.temp("heatMax"),
                coolSet = z.temp("coolSet"),
                coolMin = z.temp("coolMin"),
                coolMax = z.temp("coolMax"),
            )
        }
        val tank = (s["tankStatus"] as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { t ->
            Tank(
                on = t.int("operationStatus") == 1,
                temperatureNow = t.temp("temperatureNow"),
                heatSet = t.temp("heatSet"),
                heatMin = t.temp("heatMin"),
                heatMax = t.temp("heatMax"),
            )
        }
        val modeRaw = s.int("operationMode")
        val on = s.int("operationStatus")?.let { it == 1 }
            ?: (zones.any { it.on } || tank?.on == true)
        return HeatPumpStatus(
            guid = pump.guid,
            name = pump.name,
            on = on,
            mode = if (modeRaw == 99) Mode.OFF else Mode.fromApi(modeRaw),
            direction = Direction.fromApi(s.int("direction")),
            defrosting = s.int("deiceStatus") == 1,
            outdoorTemperature = s.temp("outdoorNow"),
            flowTemperature = FLOW_KEYS.firstNotNullOfOrNull { s.temp(it) }
                ?: zones.firstOrNull { it.isOffset }?.temperatureNow
                ?: zones.firstOrNull()?.temperatureNow,
            zones = zones,
            tank = tank,
            faults = (s["faultStatus"] as? JsonArray).orEmpty().mapNotNull { f ->
                (f as? JsonObject)?.let { listOfNotNull(it.str("errorCode"), it.str("errorMessage")).joinToString(" ") }
            }.filter { it.isNotBlank() },
        )
    }

    companion object {
        const val BASE_AUTH = "https://authglb.digital.panasonic.com"
        const val BASE_ACC = "https://accsmart.panasonic.com"
        const val APP_CLIENT_ID = "Xmy6xIYIitMxngjB2rHvlm6HSDNnaMJx"
        const val AUTH0_CLIENT =
            "eyJuYW1lIjoiQXV0aDAuQW5kcm9pZCIsImVudiI6eyJhbmRyb2lkIjoiMzAifSwidmVyc2lvbiI6IjIuOS4zIn0="
        const val REDIRECT_URI =
            "panasonic-iot-cfc://authglb.digital.panasonic.com/android/com.panasonic.ACCsmart/callback"
        const val SCOPE = "openid offline_access comfortcloud.control a2w.control"
        const val AUDIENCE = "https://digital.panasonic.com/$APP_CLIENT_ID/api/v1/"
        const val DEFAULT_APP_VERSION = "4.3.0"
        /** Paketname der offiziellen Comfort-Cloud-App, um deren Version auszulesen. */
        const val COMFORT_CLOUD_PACKAGE = "com.panasonic.ACCsmart"
        private const val APP_STORE_URL = "https://apps.apple.com/de/app/panasonic-comfort-cloud/id1348640525"
        private const val API_UA = "okhttp/4.10.0"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Mobile Safari/537.36"
        private const val INVALID_TEMPERATURE = 126
        /** Mögliche Felder für die Vorlauftemperatur, je nach Gerät/Firmware. */
        private val FLOW_KEYS = listOf("waterOutletTemp", "outletTemp", "flowTemp", "waterTemperatureNow")
        private const val API_KEY_SECRET = "521325fb2dd486bf4831b47644317fca"
        private val JSON = "application/json".toMediaType()
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val random = SecureRandom()

        /**
         * `X-CFC-API-KEY`: SHA-256 über App-Name, Schlüssel, Zeitstempel und Token,
         * mit "cfc" nach dem 9. Zeichen. Der Zeitstempel ist die lokale Uhrzeit, als UTC gelesen –
         * genau so macht es die offizielle App.
         */
        fun apiKey(localNow: LocalDateTime, token: String): String {
            val ms = localNow.withNano(0).toEpochSecond(ZoneOffset.UTC) * 1000
            val hex = sha256("Comfort Cloud$API_KEY_SECRET${ms}Bearer $token".toByteArray())
                .joinToString("") { "%02x".format(it) }
            return hex.substring(0, 9) + "cfc" + hex.substring(9)
        }

        /** "4.3.0 (123)" oder "4.3.0-release" → "4.3.0" */
        fun normalizeVersion(v: String): String =
            Regex("""\d+\.\d+\.\d+""").find(v)?.value ?: v

        /**
         * JSON-Body mit exakt `Content-Type: application/json`. Die String-Variante von OkHttp hängt
         * "; charset=utf-8" an, was die Aquarea-Schnittstelle mit Code 4000 ablehnt.
         */
        private fun String.jsonBody() = toByteArray(Charsets.UTF_8).toRequestBody(JSON)

        private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

        private fun randomString(n: Int): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return String(CharArray(n) { chars[random.nextInt(chars.length)] })
        }

        private fun resolve(base: String, location: String): HttpUrl =
            base.toHttpUrl().resolve(location) ?: throw ComfortCloudException("Ungültiger Redirect: $location")

        private fun queryParam(location: String, name: String): String {
            val query = location.substringAfter('?', "")
            return query.split('&').map { it.split('=', limit = 2) }
                .firstOrNull { it[0] == name }?.getOrNull(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                ?: throw ComfortCloudException("Kein '$name' im Redirect")
        }

        private fun Response.expect(code: Int, step: String, bodyText: String? = null) {
            if (this.code != code) {
                val text = bodyText ?: runCatching { body?.string() }.getOrNull().orEmpty()
                throw ComfortCloudException("$step: HTTP ${this.code} statt $code. ${text.take(300)}")
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? =
            (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }
        private fun JsonObject.dbl(key: String): Double =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0
        private fun JsonObject.temp(key: String): Int? = int(key)?.takeIf { it != INVALID_TEMPERATURE && it != -INVALID_TEMPERATURE }
    }
}

private class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { c -> this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path } }
        this.cookies += cookies
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.expiresAt < now }
        return this.cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun find(name: String): String? = cookies.lastOrNull { it.name == name }?.value

    @Synchronized
    fun clear() = cookies.clear()
}
