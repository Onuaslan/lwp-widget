package de.onur.aquarea

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import de.onur.aquarea.api.Activity
import de.onur.aquarea.api.ComfortCloudClient
import de.onur.aquarea.api.Operation
import de.onur.aquarea.api.activity
import de.onur.aquarea.api.coolingSupported
import de.onur.aquarea.api.operation
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Das Widget im Look des Comfort-Cloud-Monitors (Ansicht „Tag“) plus Live-Werten. */
class LwpWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) = RefreshJob.schedulePeriodic(context)

    override fun onDisabled(context: Context) = RefreshJob.cancelPeriodic(context)

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.render(context, manager, it) }
        RefreshJob.schedulePeriodic(context)
        RefreshJob.runNow(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        WidgetRenderer.render(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val storage = Storage(context)
        ids.forEach { storage.setDayOffset(it, 0) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        val storage = Storage(context)
        when (intent.action) {
            ACTION_REFRESH -> {
                WidgetRenderer.render(context, manager, id, refreshing = true)
                RefreshJob.runNow(context)
            }
            ACTION_SELECT_OP -> {
                val status = storage.status ?: return
                val target = intent.getStringExtra(EXTRA_OP)?.let { runCatching { Operation.valueOf(it) }.getOrNull() } ?: return
                // Auswahl sofort anzeigen; gesendet wird erst nach 2 Sekunden ohne weiteres Tippen
                storage.pendingOperation = target.name
                storage.dhwPendingUntil = System.currentTimeMillis() + 60_000
                val seq = storage.opSeq + 1
                storage.opSeq = seq
                LwpWidgetProvider.renderAll(context)
                debounced(context, isLatest = { storage.opSeq == seq }) {
                    val current = storage.status ?: status
                    if (target == current.operation) {
                        // ist schon so eingestellt – nichts zu senden
                        storage.dhwPendingUntil = 0
                        storage.pendingOperation = null
                        return@debounced
                    }
                    storage.opSentSeq = seq
                    LwpWidgetProvider.renderAll(context)
                    try {
                        val repo = Repository.get(context)
                        repo.client.setOperation(pump(context, storage), current, target)
                        storage.lastError = null
                        RefreshJob.refreshAfterCommand(context)
                    } catch (e: Exception) {
                        storage.lastError = "Umschalten fehlgeschlagen: " + (e.message ?: e.javaClass.simpleName)
                        if (storage.opSeq == seq) {
                            storage.dhwPendingUntil = 0
                            storage.pendingOperation = null
                        }
                    }
                }
            }
            ACTION_TOGGLE_FORCE -> {
                val status = storage.status ?: return
                val base = if (storage.forcePendingUntil > System.currentTimeMillis()) storage.forcePendingTarget else status.forceHotWater
                val target = !base
                storage.forcePendingTarget = target
                storage.forcePendingUntil = System.currentTimeMillis() + 60_000
                val seq = storage.forceSeq + 1
                storage.forceSeq = seq
                LwpWidgetProvider.renderAll(context)
                debounced(context, isLatest = { storage.forceSeq == seq }) {
                    val current = storage.status ?: status
                    if (target == current.forceHotWater) {
                        storage.forcePendingUntil = 0
                        return@debounced
                    }
                    storage.forceSentSeq = seq
                    LwpWidgetProvider.renderAll(context)
                    try {
                        Repository.get(context).client.setForceHotWater(pump(context, storage), target)
                        storage.lastError = null
                        RefreshJob.refreshAfterCommand(context)
                    } catch (e: Exception) {
                        storage.lastError = "Warmwasser erzwingen fehlgeschlagen: " + (e.message ?: e.javaClass.simpleName)
                        if (storage.forceSeq == seq) storage.forcePendingUntil = 0
                    }
                }
            }
            ACTION_PREV, ACTION_NEXT -> {
                val delta = if (intent.action == ACTION_PREV) 1 else -1
                val offset = (storage.dayOffset(id) + delta).coerceIn(0, MAX_DAYS_BACK)
                storage.setDayOffset(id, offset)
                WidgetRenderer.render(context, manager, id)
                // fehlende Tage nachladen
                if (storage.consumption(LocalDate.now().minusDays(offset.toLong()).toString()) == null) {
                    RefreshJob.runNow(context)
                }
            }
        }
    }

    /**
     * Wartet 2 Sekunden und führt [send] nur aus, wenn seitdem nicht erneut getippt wurde.
     * goAsync() hält den Empfänger so lange am Leben.
     */
    private fun debounced(context: Context, isLatest: () -> Boolean, send: () -> Unit) {
        val result = goAsync()
        Thread {
            try {
                Thread.sleep(DEBOUNCE_MS)
                if (isLatest()) send()
            } catch (_: InterruptedException) {
            } finally {
                LwpWidgetProvider.renderAll(context)
                result.finish()
            }
        }.start()
    }

    private fun pump(context: Context, storage: Storage) =
        storage.selectedPump ?: Repository.get(context).pumps().first().also { storage.selectedPump = it }

    companion object {
        private const val DEBOUNCE_MS = 2000L
        const val ACTION_REFRESH = "de.onur.aquarea.REFRESH"
        const val ACTION_PREV = "de.onur.aquarea.PREV_DAY"
        const val ACTION_NEXT = "de.onur.aquarea.NEXT_DAY"
        const val ACTION_SELECT_OP = "de.onur.aquarea.SELECT_OPERATION"
        const val EXTRA_OP = "operation"
        const val ACTION_TOGGLE_FORCE = "de.onur.aquarea.TOGGLE_FORCE_DHW"
        const val MAX_DAYS_BACK = 13

        fun widgetIds(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, LwpWidgetProvider::class.java))

        /** Alle Widgets mit den gespeicherten Werten neu zeichnen. */
        fun renderAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            widgetIds(context).forEach { WidgetRenderer.render(context, manager, it) }
        }
    }
}

object WidgetRenderer {
    private val dateFormat = DateTimeFormatter.ofPattern("dd MM yyyy")

    /** Höhe aller Elemente außer dem Diagramm (dp), mit und ohne Legende. */
    private const val CHROME_DP = 170
    private const val LEGEND_DP = 28
    private const val MIN_CHART_DP = 110

    fun render(context: Context, manager: AppWidgetManager, id: Int, refreshing: Boolean = false) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val options = manager.getAppWidgetOptions(id)

        // Ab Android 12 meldet der Launcher die genauen Größen (Hoch- und Querformat).
        // Dann bekommt jede Größe ihr eigenes, exakt passendes Diagramm.
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes: List<SizeF>? = if (Build.VERSION.SDK_INT >= 33) {
                options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            } else {
                @Suppress("DEPRECATION")
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            }
            if (!sizes.isNullOrEmpty()) {
                val distinct = sizes.distinctBy { it.width.roundToInt() to it.height.roundToInt() }.take(16)
                val map = distinct.associateWith { buildViews(context, id, it.width.roundToInt(), it.height.roundToInt(), refreshing) }
                manager.updateAppWidget(id, RemoteViews(map))
                return
            }
        }

        // Ältere Launcher: Hochformat = minWidth × maxHeight, Querformat = maxWidth × minHeight
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        fun opt(key: String, fallback: Int) = options.getInt(key, fallback).takeIf { it > 0 } ?: fallback
        val widthDp = if (landscape) opt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 400) else opt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val heightDp = if (landscape) opt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 200) else opt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
        manager.updateAppWidget(id, buildViews(context, id, widthDp, heightDp, refreshing))
    }

    private fun buildViews(context: Context, id: Int, widthDp: Int, heightDp: Int, refreshing: Boolean): RemoteViews {
        val storage = Storage(context)
        // Flach und breit (z.B. Querformat): Live-Werte links, Diagramm rechts
        // Querformat nur, wenn rechts alle Knöpfe ganz hineinpassen (sonst würde abgeschnitten)
        val buttonsDp = (if (storage.status?.coolingSupported == true) 5 else 3) * 46 + 56
        val wide = heightDp < 230 && widthDp >= heightDp * 1.7f && widthDp - 24 - 150 - 10 >= buttonsDp
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_lwp_wide else R.layout.widget_lwp)
        val status = storage.status
        val offset = storage.dayOffset(id)
        val date = LocalDate.now().minusDays(offset.toLong())
        val day = storage.consumption(date.toString())

        // Unten links: der Tag, den das Diagramm zeigt, mit Blättern
        views.setTextViewText(R.id.total_date, date.format(dateFormat))
        views.setViewVisibility(R.id.next, if (offset > 0) View.VISIBLE else View.INVISIBLE)

        // Live-Werte
        views.setTextViewText(R.id.outdoor, status?.outdoorTemperature?.let { "$it °C" } ?: "–")
        views.setTextViewText(R.id.flow, status?.flowTemperature?.let { "$it °C" } ?: "–")
        views.setTextViewText(R.id.tank_temp, status?.tank?.temperatureNow?.let { "$it °C" } ?: "–")
        val activity = status?.activity
        // Im Widget ist wenig Platz: Warmwasser als „WW“ abkürzen
        views.setTextViewText(R.id.activity, if (activity == Activity.HOT_WATER) "WW" else activity?.label ?: "–")
        views.setImageViewResource(
            R.id.activity_dot,
            when (activity) {
                Activity.HEATING -> R.drawable.dot_heat
                Activity.HOT_WATER -> R.drawable.dot_hot_water
                Activity.DEFROST -> R.drawable.dot_defrost
                Activity.COOLING -> R.drawable.dot_cool
                else -> R.drawable.dot_idle
            }
        )

        // Betriebsart-Knöpfe: der aktive (bzw. gerade gewählte) ist rot umrandet, die anderen blass.
        // Kühl-Knöpfe nur, wenn die Anlage kühlen kann.
        val pending = storage.dhwPendingUntil > System.currentTimeMillis()
        val sending = pending && storage.opSentSeq == storage.opSeq
        val selected = (if (pending) storage.pendingOperation?.let { runCatching { Operation.valueOf(it) }.getOrNull() } else null)
            ?: status?.operation
        val cooling = status?.coolingSupported == true
        for ((op, viewId) in OP_BUTTONS) {
            val visible = status != null && (cooling || !op.cooling)
            views.setViewVisibility(viewId, if (visible) View.VISIBLE else View.GONE)
            if (!visible) continue
            val active = op == selected
            views.setInt(viewId, "setBackgroundResource", if (active) R.drawable.pill_active else R.drawable.pill_off)
            views.setInt(viewId, "setImageAlpha", if (active) 255 else 105)
            views.setOnClickPendingIntent(viewId, selectOp(context, id, op))
        }
        views.setViewVisibility(R.id.mode_pending, if (sending) View.VISIBLE else View.GONE)

        // Warmwasser erzwingen: rot umrandet = aktiv, blass = aus
        if (status?.tank == null) {
            views.setViewVisibility(R.id.force_toggle, View.GONE)
        } else {
            val forcePending = storage.forcePendingUntil > System.currentTimeMillis()
            val active = if (forcePending) storage.forcePendingTarget else status.forceHotWater
            views.setViewVisibility(R.id.force_toggle, View.VISIBLE)
            views.setInt(R.id.force_toggle, "setBackgroundResource", if (active) R.drawable.pill_active else R.drawable.pill_off)
            views.setInt(R.id.force_toggle, "setImageAlpha", if (active) 255 else 105)
            views.setOnClickPendingIntent(R.id.force_toggle, broadcast(context, id, LwpWidgetProvider.ACTION_TOGGLE_FORCE))
        }

        // Legende nur, wenn das Diagramm trotzdem genug Höhe behält (sonst z.B. im Querformat ausblenden)
        val showLegend = if (wide) heightDp - 16 - 44 - 28 >= 80 else heightDp - CHROME_DP - LEGEND_DP >= MIN_CHART_DP
        views.setViewVisibility(R.id.legend, if (showLegend) View.VISIBLE else View.GONE)

        // Diagramm in genau der verfügbaren Größe zeichnen, damit nichts gestaucht wird
        val density = context.resources.displayMetrics.density
        val chartWidthDp = max(120, if (wide) widthDp - 24 - 150 - 10 else widthDp - 24)
        val chartHeightDp = if (wide) {
            max(60, heightDp - 16 - 44 - 4 - (if (showLegend) 26 else 0)) // Knopfzeile (+ Legende)
        } else {
            max(64, heightDp - CHROME_DP - (if (showLegend) LEGEND_DP else 0))
        }
        // Bitmaps in RemoteViews sind in der Größe begrenzt – Auflösung notfalls reduzieren
        val scale = min(density, 1000f / chartWidthDp)
        val chartUri = ChartFiles.uri(
            context,
            day,
            (chartWidthDp * scale).roundToInt(),
            (chartHeightDp * scale).roundToInt(),
            scale,
            currentHour = if (offset == 0) LocalTime.now().hour else null,
        )
        views.setImageViewUri(R.id.chart, chartUri)
        views.setTextViewText(R.id.total, day?.let { String.format(Locale.US, "%.2f", it.total) } ?: "–")

        // Fußzeile
        val footer = when {
            refreshing -> "Aktualisiere …"
            storage.lastError != null -> "⚠ ${storage.lastError}"
            storage.lastRefresh > 0 ->
                "Stand " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(storage.lastRefresh))
            else -> "Tippen zum Aktualisieren"
        }
        views.setTextViewText(R.id.updated, footer)

        // Klicks
        views.setOnClickPendingIntent(R.id.refresh, broadcast(context, id, LwpWidgetProvider.ACTION_REFRESH))
        views.setOnClickPendingIntent(R.id.updated, broadcast(context, id, LwpWidgetProvider.ACTION_REFRESH))
        views.setOnClickPendingIntent(R.id.prev, broadcast(context, id, LwpWidgetProvider.ACTION_PREV))
        views.setOnClickPendingIntent(R.id.next, broadcast(context, id, LwpWidgetProvider.ACTION_NEXT))
        // Tippen aufs Widget öffnet die Comfort-Cloud-App (falls installiert)
        val ownApp = Intent(context, MainActivity::class.java)
        val comfortCloud = context.packageManager
            .getLaunchIntentForPackage(ComfortCloudClient.COMFORT_CLOUD_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(
            R.id.root,
            PendingIntent.getActivity(context, 0, comfortCloud ?: ownApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return views
    }

    private val OP_BUTTONS = listOf(
        Operation.HEAT_AND_WATER to R.id.op_heat_water,
        Operation.HEAT to R.id.op_heat,
        Operation.COOL to R.id.op_cool,
        Operation.COOL_AND_WATER to R.id.op_cool_water,
        Operation.WATER to R.id.op_water,
    )

    private fun selectOp(context: Context, id: Int, op: Operation): PendingIntent {
        val intent = Intent(context, LwpWidgetProvider::class.java)
            .setAction(LwpWidgetProvider.ACTION_SELECT_OP)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(LwpWidgetProvider.EXTRA_OP, op.name)
        return PendingIntent.getBroadcast(
            context, id * 10 + 6 + op.ordinal * 100_000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun broadcast(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, LwpWidgetProvider::class.java)
            .setAction(action)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val requestCode = id * 10 + when (action) {
            LwpWidgetProvider.ACTION_REFRESH -> 1
            LwpWidgetProvider.ACTION_PREV -> 2
            LwpWidgetProvider.ACTION_TOGGLE_FORCE -> 5
            else -> 3
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Lädt die Daten im Hintergrund: alle 15 Minuten (Android-Minimum) und auf Knopfdruck. */
class RefreshJob : JobService() {
    // Ein Thread pro Job: dieselbe Service-Instanz kann mehrere Jobs gleichzeitig ausführen
    private val workers = java.util.concurrent.ConcurrentHashMap<Int, Thread>()

    override fun onStartJob(params: JobParameters): Boolean {
        val thread = Thread {
            val storage = Storage(this)
            val repo = Repository.get(this)
            val offsets = LwpWidgetProvider.widgetIds(this).map { storage.dayOffset(it) }.toSet() + 0
            val ok = repo.refresh(offsets) != null
            // Ein Umschaltbefehl gilt als erledigt, sobald frische Daten da sind (oder nach 60 s)
            if (ok || params.jobId == AFTER_COMMAND_ID) {
                // nur abschließen, was schon gesendet wurde – eine laufende Auswahl bleibt stehen
                if (storage.opSentSeq == storage.opSeq) {
                    storage.dhwPendingUntil = 0
                    storage.pendingOperation = null
                }
                if (storage.forceSentSeq == storage.forceSeq) storage.forcePendingUntil = 0
            }
            LwpWidgetProvider.renderAll(this)
            workers.remove(params.jobId)
            if (!Thread.currentThread().isInterrupted) jobFinished(params, false)
        }
        workers[params.jobId] = thread
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        workers.remove(params.jobId)?.interrupt()
        return true
    }

    companion object {
        private const val PERIODIC_ID = 1001
        private const val ONE_SHOT_ID = 1002
        private const val AFTER_COMMAND_ID = 1004

        /** Status ein paar Sekunden nach einem Befehl neu laden – die Pumpe übernimmt ihn verzögert. */
        fun refreshAfterCommand(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(AFTER_COMMAND_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(4_000)
                    .setOverrideDeadline(15_000)
                    .build()
            )
        }

        fun schedulePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(PERIODIC_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancelPeriodic(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(PERIODIC_ID)
        }

        fun runNow(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Einen laufenden Abruf nicht durch Neuplanen abbrechen
            if (scheduler.getPendingJob(ONE_SHOT_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ONE_SHOT_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .build()
            )
        }
    }
}
