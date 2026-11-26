package de.onur.aquarea.api

import kotlinx.serialization.Serializable

/** Tokens der Panasonic-ID (Auth0) plus die ACC-Client-ID der Comfort Cloud. */
@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Unix-Zeit in Sekunden, ab der der Access-Token abgelaufen ist. */
    val expiresAt: Long,
    val scope: String,
    val clientId: String? = null,
)

/** Speichert die Session dauerhaft (in der App: verschlüsselte SharedPreferences). */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override fun load() = session
    override fun save(session: Session?) { this.session = session }
}

@Serializable
data class HeatPump(val guid: String, val name: String)

/** Betriebsmodus, wie ihn die Wärmepumpe meldet (`operationMode`). */
@Serializable
enum class Mode(val apiValue: Int, val label: String) {
    OFF(0, "Aus"),
    HEAT(1, "Heizen"),
    COOL(2, "Kühlen"),
    AUTO_HEAT(3, "Auto (Heizen)"),
    AUTO_COOL(4, "Auto (Kühlen)");

    val isCooling get() = this == COOL || this == AUTO_COOL

    companion object {
        fun fromApi(v: Int?): Mode = entries.firstOrNull { it.apiValue == v } ?: OFF
    }
}

/** Werte, mit denen der Modus geändert wird – sie unterscheiden sich von [Mode]. */
enum class ModeCommand(val apiValue: Int, val label: String) {
    HEAT(2, "Heizen"),
    COOL(3, "Kühlen"),
    AUTO(8, "Auto"),
}

/** Was die Wärmepumpe gerade macht (`direction`). */
@Serializable
enum class Direction(val apiValue: Int, val label: String) {
    IDLE(0, "Bereit"),
    PUMP(1, "Heizt/Kühlt Räume"),
    WATER(2, "Bereitet Warmwasser");

    companion object {
        fun fromApi(v: Int?): Direction = entries.firstOrNull { it.apiValue == v } ?: IDLE
    }
}

@Serializable
data class Zone(
    val id: Int,
    val name: String,
    val on: Boolean,
    val temperatureNow: Int? = null,
    val heatSet: Int? = null,
    val heatMin: Int? = null,
    val heatMax: Int? = null,
    val coolSet: Int? = null,
    val coolMin: Int? = null,
    val coolMax: Int? = null,
) {
    /**
     * Bei heizkurvengeführten Zonen ist der Sollwert eine Verschiebung (z.B. -5 … +5)
     * statt einer echten Temperatur. Daran erkennt man das.
     */
    val isOffset: Boolean get() = (heatMin ?: 0) < 0 || ((heatMax ?: 99) in 1..10)
}

@Serializable
data class Tank(
    val on: Boolean,
    val temperatureNow: Int? = null,
    val heatSet: Int? = null,
    val heatMin: Int? = null,
    val heatMax: Int? = null,
)

@Serializable
data class HeatPumpStatus(
    val guid: String,
    val name: String,
    val on: Boolean,
    val mode: Mode,
    val direction: Direction,
    val defrosting: Boolean,
    val outdoorTemperature: Int? = null,
    /**
     * Vorlauf-/Wassertemperatur. Panasonic liefert sie nicht als eigenes Feld für alle Geräte;
     * wir nehmen ein explizites Vorlauf-Feld, falls vorhanden, sonst die Ist-Temperatur der
     * ersten wassergeführten Zone (bei Heizkurven-Betrieb ist das die Vorlauftemperatur).
     */
    val flowTemperature: Int? = null,
    val zones: List<Zone> = emptyList(),
    val tank: Tank? = null,
    val faults: List<String> = emptyList(),
    /** Zeitpunkt des Abrufs, Unix-Millisekunden. */
    val fetchedAt: Long = System.currentTimeMillis(),
)

/** Was die Wärmepumpe in diesem Moment tut – für die Statusanzeige im Widget. */
enum class Activity(val label: String) {
    OFF("Aus"),
    IDLE("Bereit"),
    HEATING("Heizt"),
    COOLING("Kühlt"),
    HOT_WATER("Warmwasser"),
    DEFROST("Taut ab"),
}

val HeatPumpStatus.activity: Activity
    get() = when {
        defrosting -> Activity.DEFROST
        !on -> Activity.OFF
        direction == Direction.WATER -> Activity.HOT_WATER
        direction == Direction.PUMP && mode.isCooling -> Activity.COOLING
        direction == Direction.PUMP -> Activity.HEATING
        else -> Activity.IDLE
    }

/** Was eingeschaltet ist – wie die Betriebsart-Taste am Bedienpanel. */
enum class Operation(val label: String, val heating: Boolean, val hotWater: Boolean) {
    HEAT_AND_WATER("Heizen + Warmwasser", true, true),
    HEAT_ONLY("Nur Heizen", true, false),
    WATER_ONLY("Nur Warmwasser", false, true),
    NONE("Aus", false, false);

    /** Nächste Stufe beim Tippen: Heizen + WW → nur Heizen → nur WW → Heizen + WW */
    val next: Operation
        get() = when (this) {
            HEAT_AND_WATER -> HEAT_ONLY
            HEAT_ONLY -> WATER_ONLY
            WATER_ONLY, NONE -> HEAT_AND_WATER
        }
}

val HeatPumpStatus.operation: Operation
    get() {
        val heating = zones.any { it.on }
        val water = tank?.on == true
        return when {
            heating && water -> Operation.HEAT_AND_WATER
            heating -> Operation.HEAT_ONLY
            water -> Operation.WATER_ONLY
            else -> Operation.NONE
        }
    }

/** Verbrauch einer Stunde in kWh. */
@Serializable
data class HourConsumption(
    val hour: Int,
    val heat: Double = 0.0,
    val cool: Double = 0.0,
    val hotWater: Double = 0.0,
) {
    val total get() = heat + cool + hotWater
}

/** Stündlicher Verbrauch eines Tages, wie im Comfort-Cloud-Monitor (Ansicht „Tag“). */
@Serializable
data class DayConsumption(
    /** ISO-Datum, z.B. 2026-11-24 */
    val date: String,
    val hours: List<HourConsumption>,
) {
    val total get() = hours.sumOf { it.total }
}

open class ComfortCloudException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Login fehlgeschlagen oder Session abgelaufen – der Nutzer muss sich neu anmelden. */
class NeedsLoginException(message: String, cause: Throwable? = null) : ComfortCloudException(message, cause)

class ApiException(val code: String?, message: String) : ComfortCloudException(message)
