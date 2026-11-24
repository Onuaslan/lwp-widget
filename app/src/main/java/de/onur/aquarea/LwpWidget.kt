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
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import de.onur.aquarea.api.Activity
import de.onur.aquarea.api.activity
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

    fun render(context: Context, manager: AppWidgetManager, id: Int, refreshing: Boolean = false) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val storage = Storage(context)
        val views = RemoteViews(context.packageName, R.layout.widget_lwp)
        val status = storage.status
        val offset = storage.dayOffset(id)
        val date = LocalDate.now().minusDays(offset.toLong())
        val day = storage.consumption(date.toString())

        views.setTextViewText(R.id.title, status?.name ?: storage.selectedPump?.name ?: "LWP")
        views.setTextViewText(R.id.date, date.format(dateFormat))
        views.setTextViewText(R.id.total_date, date.format(dateFormat))
        views.setViewVisibility(R.id.next, if (offset > 0) View.VISIBLE else View.INVISIBLE)

        // Live-Werte
        views.setTextViewText(R.id.outdoor, status?.outdoorTemperature?.let { "$it °C" } ?: "–")
        views.setTextViewText(R.id.flow, status?.flowTemperature?.let { "$it °C" } ?: "–")
        val activity = status?.activity
        views.setTextViewText(R.id.activity, activity?.label ?: "–")
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

        // Diagramm passend zur Widgetgröße zeichnen
        val options = manager.getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).takeIf { it > 0 } ?: 300
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260).takeIf { it > 0 } ?: 260
        val chartWidthDp = widthDp - 28
        val chartHeightDp = max(70, heightDp - 190)
        // Bitmaps in RemoteViews sind in der Größe begrenzt – Auflösung notfalls reduzieren
        val scale = min(density, 1100f / chartWidthDp)
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
        views.setOnClickPendingIntent(R.id.updated, broadcast(context, id, LwpWidgetProvider.ACTION_REFRESH))
        views.setOnClickPendingIntent(R.id.prev, broadcast(context, id, LwpWidgetProvider.ACTION_PREV))
        views.setOnClickPendingIntent(R.id.next, broadcast(context, id, LwpWidgetProvider.ACTION_NEXT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.root, open)

        manager.updateAppWidget(id, views)
    }

    private fun broadcast(context: Context, id: Int, action: String): PendingIntent {
        val intent = Intent(context, LwpWidgetProvider::class.java)
            .setAction(action)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val requestCode = id * 10 + when (action) {
            LwpWidgetProvider.ACTION_REFRESH -> 1
            LwpWidgetProvider.ACTION_PREV -> 2
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
            val offsets = LwpWidgetProvider.widgetIds(this).map { storage.dayOffset(it) }.toSet() + 0
            Repository.get(this).refresh(offsets)
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
