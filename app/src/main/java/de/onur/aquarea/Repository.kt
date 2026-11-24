package de.onur.aquarea

import android.content.Context
import android.os.Build
import de.onur.aquarea.api.ComfortCloudClient
import de.onur.aquarea.api.HeatPump
import de.onur.aquarea.api.HeatPumpStatus
import de.onur.aquarea.api.NeedsLoginException
import java.time.LocalDate

/** Verbindet API-Client, Speicher und Widget. Alle Methoden blockieren (nicht im Main-Thread aufrufen). */
class Repository private constructor(private val context: Context) {
    val storage = Storage(context)

    val client = ComfortCloudClient(
        store = storage,
        credentials = {
            val e = storage.email
            val p = storage.password
            if (e != null && p != null) e to p else null
        },
        installedAppVersion = ::installedComfortCloudVersion,
    )

    fun login(email: String, password: String): List<HeatPump> {
        client.login(email, password)
        storage.email = email
        storage.password = password
        val pumps = client.heatPumps()
        if (storage.selectedPump == null || pumps.none { it.guid == storage.selectedPump?.guid }) {
            storage.selectedPump = pumps.firstOrNull()
        }
        return pumps
    }

    fun logout() {
        client.logout()
        storage.clearAll()
    }

    fun pumps(): List<HeatPump> = client.heatPumps()

    private fun pump(): HeatPump =
        storage.selectedPump ?: client.heatPumps().firstOrNull()?.also { storage.selectedPump = it }
        ?: throw IllegalStateException("Keine Wärmepumpe im Konto gefunden")

    /**
     * Lädt Live-Status und den Stundenverbrauch für [days] (heute = 0) und legt beides im Cache ab.
     * Fehler werden gespeichert, damit das Widget sie anzeigen kann.
     */
    fun refresh(days: Collection<Int> = listOf(0)): HeatPumpStatus? {
        if (!storage.isLoggedIn) {
            storage.lastError = "Bitte in der App anmelden"
            return null
        }
        return try {
            val pump = pump()
            val status = client.status(pump)
            storage.status = status
            for (offset in days.toSortedSet()) {
                val date = LocalDate.now().minusDays(offset.toLong())
                // vergangene Tage nur einmal laden, heute immer
                if (offset == 0 || storage.consumption(date.toString()) == null) {
                    runCatching { client.dayConsumption(pump, date) }.getOrNull()?.let(storage::saveConsumption)
                }
            }
            storage.lastRefresh = System.currentTimeMillis()
            storage.lastError = null
            status
        } catch (e: NeedsLoginException) {
            storage.lastError = "Anmeldung abgelaufen – bitte App öffnen"
            null
        } catch (e: Exception) {
            storage.lastError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    /** Führt einen Steuerbefehl aus und lädt danach den Status neu. */
    fun control(action: (ComfortCloudClient, HeatPump, HeatPumpStatus) -> Unit): HeatPumpStatus? {
        val pump = pump()
        val current = storage.status ?: client.status(pump)
        action(client, pump, current)
        // Die Wärmepumpe übernimmt Befehle mit etwas Verzögerung
        Thread.sleep(2500)
        return refresh()
    }

    private fun installedComfortCloudVersion(): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(ComfortCloudClient.COMFORT_CLOUD_PACKAGE, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ComfortCloudClient.COMFORT_CLOUD_PACKAGE, 0)
        }
        info.versionName
    }.getOrNull()

    companion object {
        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}
