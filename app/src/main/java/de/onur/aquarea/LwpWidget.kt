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
import android.os.PersistableBundle
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
            ACTION_TOGGLE_DHW -> {
                val status = storage.status ?: return
                if (storage.dhwPendingUntil > System.currentTimeMillis()) return // läuft schon
                val target = status.operation.next(status.coolingSupported)
                storage.dhwPendingUntil = System.currentTimeMillis() + 60_000
                storage.pendingOperation = target.name
                LwpWidgetProvider.renderAll(context)
                RefreshJob.setOperation(context, target)
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

    companion object {
        const val ACTION_REFRESH = "de.onur.aquarea.REFRESH"
        const val ACTION_PREV = "de.onur.aquarea.PREV_DAY"
        const val ACTION_NEXT = "de.onur.aquarea.NEXT_DAY"
        const val ACTION_TOGGLE_DHW = "de.onur.aquarea.TOGGLE_DHW"
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
    private const val CHROME_DP = 160
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
                val distinct = sizes.distinctBy { it.width.roundToInt() to it.height.roundToInt() }.take(4)
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
        val wide = heightDp < 230 && widthDp >= heightDp * 1.7f
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_lwp_wide else R.layout.widget_lwp)
        val status = storage.status
        val offset = storage.dayOffset(id)
        val date = LocalDate.now().minusDays(offset.toLong())
        val day = storage.consumption(date.toString())

        views.setTextViewText(R.id.title, status?.name ?: storage.selectedPump?.name ?: "LWP")
        views.setTextViewText(R.id.date, date.format(dateFormat))
        // unten immer das heutige Datum, oben der gewählte Tag
        views.setTextViewText(R.id.total_date, LocalDate.now().format(dateFormat))
        views.setViewVisibility(R.id.next, if (offset > 0) View.VISIBLE else View.INVISIBLE)

        // Live-Werte
        views.setTextViewText(R.id.outdoor, status?.outdoorTemperature?.let { "$it °C" } ?: "–")
        views.setTextViewText(R.id.flow, status?.flowTemperature?.let { "$it °C" } ?: "–")
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

        // Betriebsart-Knopf wie am Bedienpanel: Heizen + WW / nur Heizen / nur WW.
        // Nach dem Tippen zeigt er sofort die neue Stufe mit „…“, bis die Pumpe sie bestätigt.
        if (status == null || (status.tank == null && status.zones.isEmpty())) {
            views.setViewVisibility(R.id.dhw_toggle, View.GONE)
        } else {
            val pending = storage.dhwPendingUntil > System.currentTimeMillis()
            val shown = (if (pending) storage.pendingOperation?.let { runCatching { Operation.valueOf(it) }.getOrNull() } else null)
                ?: status.operation
            views.setViewVisibility(R.id.dhw_toggle, View.VISIBLE)
            views.setImageViewResource(
                R.id.mode_icon,
                when (shown) {
                    Operation.HEAT_AND_WATER -> R.drawable.mode_heat_water
                    Operation.HEAT -> R.drawable.mode_heat
                    Operation.COOL -> R.drawable.mode_cool
                    Operation.COOL_AND_WATER -> R.drawable.mode_cool_water
                    Operation.WATER -> R.drawable.mode_water
                    Operation.NONE -> R.drawable.mode_heat_water
                }
            )
            views.setViewVisibility(R.id.mode_icon, if (shown == Operation.NONE) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.mode_pending, if (pending || shown == Operation.NONE) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.mode_pending, if (pending) "…" else "Aus")
            views.setOnClickPendingIntent(R.id.dhw_toggle, broadcast(context, id, LwpWidgetProvider.ACTION_TOGGLE_DHW))
        }

        // Legende nur, wenn das Diagramm trotzdem genug Höhe behält (sonst z.B. im Querformat ausblenden)
        val showLegend = if (wide) heightDp - 18 - 26 >= 90 else heightDp - CHROME_DP - LEGEND_DP >= MIN_CHART_DP
        views.setViewVisibility(R.id.legend, if (showLegend) View.VISIBLE else View.GONE)

        // Diagramm in genau der verfügbaren Größe zeichnen, damit nichts gestaucht wird
        val density = context.resources.displayMetrics.density
        val chartWidthDp = max(120, if (wide) widthDp - 26 - 132 - 10 else widthDp - 28)
        val chartHeightDp = if (wide) {
            max(60, heightDp - 18 - 4 - (if (showLegend) 22 else 0))
        } else {
            max(64, heightDp - CHROME_DP - (if (showLegend) LEGEND_DP else 0))
        }
        // Bitmaps in RemoteViews sind in der Größe begrenzt – Auflösung notfalls reduzieren
        val scale = min(density, 1000f / chartWidthDp)
        val bitmap = ChartRenderer.render(
            day,
            (chartWidthDp * scale).roundToInt(),
            (chartHeightDp * scale).roundToInt(),
            scale,
            currentHour = if (offset == 0) LocalTime.now().hour else null,
        )
        views.setImageViewBitmap(R.id.chart, bitmap)
        views.setTextViewText(R.id.total, day?.let { String.format(Locale.US, "%.2f", it.total) } ?: "–")

        // Fußzeile
        val footer = when {
            refreshing -> "Aktualisiere …"
            storage.lastError != null -> "⚠ ${storage.lastError}"
            storage.lastRefresh > 0 ->
                "Aktualisiert " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(storage.lastRefresh))
            else -> "Tippen zum Aktualisieren"
        }
        views.setTextViewText(R.id.updated, footer)

        // Klicks
        views.setOnClickPendingIntent(R.id.refresh, broadcast(context, id, LwpWidgetProvider.ACTION_REFRESH))
        views.setOnClickPendingIntent(R.id.footer, broadcast(context, id, LwpWidgetProvider.ACTION_REFRESH))
        views.setOnClickPendingIntent(R.id.prev, broadcast(context, id, LwpWidgetProvider.ACTION_PREV))
        views.setOnClickPendingIntent(R.id.next, broadcast(context, id, LwpWidgetProvider.ACTION_NEXT))
        // Tippen aufs Widget öffnet die Comfort-Cloud-App (falls installiert), der Name öffnet diese App
        val ownApp = Intent(context, MainActivity::class.java)
        val comfortCloud = context.packageManager
            .getLaunchIntentForPackage(ComfortCloudClient.COMFORT_CLOUD_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        views.setOnClickPendingIntent(
            R.id.root,
            PendingIntent.getActivity(context, 0, comfortCloud ?: ownApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        views.setOnClickPendingIntent(
            R.id.title,
            PendingIntent.getActivity(context, 1, ownApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return views
    }

    private fun broadcast(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, LwpWidgetProvider::class.java)
            .setAction(action)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val requestCode = id * 10 + when (action) {
            LwpWidgetProvider.ACTION_REFRESH -> 1
            LwpWidgetProvider.ACTION_PREV -> 2
            LwpWidgetProvider.ACTION_TOGGLE_DHW -> 4
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
    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            val storage = Storage(this)
            val repo = Repository.get(this)
            val op = params.extras.getString(EXTRA_OPERATION)?.let { runCatching { Operation.valueOf(it) }.getOrNull() }
            if (op != null) {
                // Betriebsart setzen, danach frischen Status holen (control() lädt neu)
                try {
                    repo.control { c, p, s -> c.setOperation(p, s, op) }
                } catch (e: Exception) {
                    storage.lastError = "Umschalten fehlgeschlagen: " + (e.message ?: e.javaClass.simpleName)
                }
                storage.dhwPendingUntil = 0
                storage.pendingOperation = null
            }
            val offsets = LwpWidgetProvider.widgetIds(this).map { storage.dayOffset(it) }.toSet() + 0
            repo.refresh(offsets)
            LwpWidgetProvider.renderAll(this)
            jobFinished(params, false)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val PERIODIC_ID = 1001
        private const val ONE_SHOT_ID = 1002
        private const val DHW_ID = 1003
        private const val EXTRA_OPERATION = "operation"

        /** Betriebsart im Hintergrund setzen. */
        fun setOperation(context: Context, operation: Operation) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(DHW_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .setExtras(PersistableBundle().apply { putString(EXTRA_OPERATION, operation.name) })
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
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ONE_SHOT_ID, ComponentName(context, RefreshJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setOverrideDeadline(0)
                    .build()
            )
        }
    }
}
