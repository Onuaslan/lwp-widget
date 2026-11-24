package de.onur.aquarea.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.LocalDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComfortCloudClientTest {

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path!!
                return when {
                    path.startsWith("/authorize?") ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/u/login?state=STATE123")
                    path.startsWith("/u/login") ->
                        MockResponse().setResponseCode(200).addHeader("Set-Cookie", "_csrf=CSRF1; Path=/").setBody("<html/>")
                    path == "/usernamepassword/login" -> MockResponse().setBody(
                        """<form><input type="hidden" name="wa" value="wsignin1.0"><input type="hidden" name="wresult" value="R"><input type="hidden" name="wctx" value="C"></form>"""
                    )
                    path == "/login/callback" ->
                        MockResponse().setResponseCode(302).setHeader("Location", "/authorize/resume?state=X")
                    path.startsWith("/authorize/resume") -> MockResponse().setResponseCode(302)
                        .setHeader("Location", "${ComfortCloudClient.REDIRECT_URI}?code=CODE42&state=X")
                    path == "/oauth/token" -> MockResponse().setBody(
                        """{"access_token":"AT","refresh_token":"RT","expires_in":86400,"scope":"openid offline_access"}"""
                    )
                    path == "/auth/v2/login" -> MockResponse().setBody("""{"clientId":"CID"}""")
                    path == "/device/group" -> MockResponse().setBody(
                        """{"groupList":[{"deviceList":[
                            {"deviceGuid":"AC1","deviceName":"Klima","deviceType":"1"},
                            {"deviceGuid":"HP1","deviceName":"Wärmepumpe","deviceType":"2"}]}]}"""
                    )
                    path == "/remote/v1/app/common/transfer" -> MockResponse().setBody(STATUS_JSON)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client(store: SessionStore = InMemorySessionStore()) = ComfortCloudClient(
        store = store,
        credentials = { "a@b.de" to "pw" },
        installedAppVersion = { "4.5.1 (1234)" },
        baseAuth = server.url("/").toString().trimEnd('/'),
        baseAcc = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `api key matches the python reference implementation`() {
        val key = ComfortCloudClient.apiKey(LocalDateTime.of(2026, 11, 24, 15, 30, 0), "abc.def")
        assertEquals("707249abecfc107cbcd27c651fc2c81ff44da933e7ef551ccbb642aee19705007e3", key)
    }

    @Test
    fun `login runs the full auth0 flow and stores the session`() {
        val store = InMemorySessionStore()
        val session = client(store).login("a@b.de", "pw")
        assertEquals("AT", session.accessToken)
        assertEquals("RT", session.refreshToken)
        assertEquals("CID", session.clientId)
        assertEquals(session, store.load())

        val login = requests.first { it.path == "/usernamepassword/login" }
        val body = Json.parseToJsonElement(login.body.readUtf8()).jsonObject
        assertEquals("\"CSRF1\"", body["_csrf"].toString())
        assertEquals("\"STATE123\"", body["state"].toString())

        val callback = requests.first { it.path == "/login/callback" }.body.readUtf8()
        assertTrue("wresult=R" in callback)

        val token = Json.parseToJsonElement(requests.first { it.path == "/oauth/token" }.body.readUtf8()).jsonObject
        assertEquals("\"CODE42\"", token["code"].toString())

        val acc = requests.first { it.path == "/auth/v2/login" }
        assertEquals("4.5.1", acc.getHeader("X-APP-VERSION"))
        assertNull(acc.getHeader("X-Client-Id"))
    }

    @Test
    fun `lists only heat pumps and parses their status`() {
        val c = client()
        val pumps = c.heatPumps() // meldet sich dabei automatisch an
        assertEquals(listOf(HeatPump("HP1", "Wärmepumpe")), pumps)
        assertEquals("CID", requests.last { it.path == "/device/group" }.getHeader("X-Client-Id"))

        val s = c.status(pumps.single())
        assertTrue(s.on)
        assertEquals(Mode.HEAT, s.mode)
        assertEquals(Direction.WATER, s.direction)
        assertEquals(4, s.outdoorTemperature)
        assertEquals(1, s.zones.size)
        assertEquals(21, s.zones[0].temperatureNow)
        assertEquals(22, s.zones[0].heatSet)
        assertFalse(s.zones[0].isOffset)
        assertNull(s.zones[0].coolSet) // 126 = ungültig
        assertEquals(48, s.tank?.temperatureNow)
        assertEquals(50, s.tank?.heatSet)

        val transfer = Json.parseToJsonElement(requests.last { it.path == "/remote/v1/app/common/transfer" }.body.readUtf8())
        assertEquals("\"/remote/v1/api/devices?gwid=HP1&deviceDirect=1\"", transfer.jsonObject["apiName"].toString())
    }

    @Test
    fun `parses hourly consumption and activity`() {
        val c = client()
        val json = Json.parseToJsonElement(
            """{"historyDataList":[
              {"dataTime":"2026112400","heatConsumption":0.8,"coolConsumption":null,"tankConsumption":0},
              {"dataTime":"2026112411","heatConsumption":0.15,"coolConsumption":0,"tankConsumption":1.05},
              {"dataTime":"2026112420","heatConsumption":-255,"coolConsumption":-255,"tankConsumption":-255}]}"""
        ).jsonObject
        val day = c.parseDayConsumption(java.time.LocalDate.of(2026, 11, 24), json)
        assertEquals(24, day.hours.size)
        assertEquals(0.8, day.hours[0].heat)
        assertEquals(1.05, day.hours[11].hotWater)
        assertEquals(0.0, day.hours[20].total)
        assertEquals(2.0, day.total, 1e-9)

        val s = c.parseStatus(HeatPump("HP1", "LWP"), Json.parseToJsonElement(STATUS_JSON).jsonObject)
        assertEquals(Activity.HOT_WATER, s.activity)
        assertEquals(21, s.flowTemperature)
        assertEquals(Activity.DEFROST, s.copy(defrosting = true).activity)
        assertEquals(Activity.HEATING, s.copy(direction = Direction.PUMP).activity)
    }

    @Test
    fun `api errors in the message field are raised`() {
        val c = client()
        val pump = HeatPump("HP1", "x")
        server.dispatcher.let { original ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.path == "/remote/v1/app/common/transfer")
                        MockResponse().setBody("""{"message":[{"errorCode":"1-0012","errorMessage":"Device offline"}]}""")
                    else original.dispatch(request)
            }
        }
        val e = runCatching { c.setTankTemperature(pump, 50) }.exceptionOrNull()
        assertTrue(e is ApiException && e.code == "1-0012", "got $e")
    }

    companion object {
        const val STATUS_JSON = """{"status":{"operationStatus":1,"operationMode":1,"direction":2,"deiceStatus":0,
            "outdoorNow":4,"faultStatus":[],
            "zoneStatus":[{"zoneId":1,"operationStatus":1,"temperatureNow":21,"heatSet":22,"heatMin":10,"heatMax":30,
                           "coolSet":126,"coolMin":126,"coolMax":126}],
            "tankStatus":{"operationStatus":1,"temperatureNow":48,"heatSet":50,"heatMin":40,"heatMax":65}}}"""
    }
}
