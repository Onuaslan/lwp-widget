package de.onur.aquarea

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import de.onur.aquarea.api.DayConsumption
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Zeichnet das Stunden-Balkendiagramm im Stil des Comfort-Cloud-Monitors (Ansicht „Tag“). */
object ChartRenderer {
    const val COLOR_HOT_WATER = 0xFFC4706A.toInt()
    const val COLOR_HEAT = 0xFFE8AD7E.toInt()
    const val COLOR_COOL = 0xFF8EA8EA.toInt()
    private const val COLOR_GRID = 0xFFE7E7E9.toInt()
    private const val COLOR_AXIS_TEXT = 0xFF6B6B70.toInt()
    private const val COLOR_SMALL_TEXT = 0xFF8A8A90.toInt()
    private const val COLOR_EMPTY = 0xFFF1F1F3.toInt()

    /**
     * @param density Bildschirmdichte (dp → px)
     * @param currentHour bei „heute“ die aktuelle Stunde; spätere Stunden bleiben leer
     */
    fun render(day: DayConsumption?, widthPx: Int, heightPx: Int, density: Float, currentHour: Int? = null): Bitmap {
        val w = max(widthPx, 100)
        val h = max(heightPx, 60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun dp(v: Float) = v * density

        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_SMALL_TEXT; textSize = dp(9f) }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_AXIS_TEXT; textSize = dp(11f); textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val grid = Paint().apply { color = COLOR_GRID; strokeWidth = max(1f, dp(0.8f)) }

        // Ränder: links Platz für Achsenwerte, oben für "kWh", unten für Stunden
        val left = dp(26f)
        val right = w - dp(6f)
        val top = dp(16f)
        val bottom = h - dp(20f)

        c.drawText("kWh", left, top - dp(5f), small.apply { textSize = dp(10f) })
        small.textSize = dp(10f)

        val hours = day?.hours.orEmpty()
        val maxValue = hours.maxOfOrNull { it.total } ?: 0.0
        val (yMax, step) = scale(maxValue)

        // Gitterlinien + Achsenwerte
        var v = 0.0
        while (v <= yMax + 1e-9) {
            val y = (bottom - (v / yMax) * (bottom - top)).toFloat()
            c.drawLine(left, y, right, y, grid)
            val label = when {
                step < 0.5 -> String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
                step < 1 -> String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
                else -> v.toInt().toString()
            }
            small.textAlign = Paint.Align.RIGHT
            c.drawText(label, left - dp(5f), y + dp(3.5f), small)
            small.textAlign = Paint.Align.LEFT
            v += step
        }

        // Balken
        val slot = (right - left) / 24f
        val barW = min(slot * 0.68f, dp(14f))
        val radius = barW / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (hour in 0..23) {
            val cx = left + slot * hour + slot / 2f
            val item = hours.getOrNull(hour)
            val total = item?.total ?: 0.0
            if (total <= 0.0) continue
            val totalTop = (bottom - (total / yMax) * (bottom - top)).toFloat()
            val bar = RectF(cx - barW / 2, totalTop, cx + barW / 2, bottom)

            // Balken auf seine Form zuschneiden (oben abgerundet), dann Segmente stapeln:
            // unten Warmwasser, darüber Heizen, oben Kühlen – wie in der Panasonic-App.
            val clip = Path().apply {
                addRoundRect(bar, floatArrayOf(radius, radius, radius, radius, dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f)), Path.Direction.CW)
            }
            c.save()
            c.clipPath(clip)
            var y = bottom
            val segments = listOf(item!!.hotWater to COLOR_HOT_WATER, item.heat to COLOR_HEAT, item.cool to COLOR_COOL)
            for ((value, color) in segments) {
                if (value <= 0) continue
                val segH = (value / yMax * (bottom - top)).toFloat()
                paint.color = color
                c.drawRect(bar.left, y - segH, bar.right, y, paint)
                // feine weiße Trennlinie zwischen Segmenten
                y -= segH
                if (y > totalTop + 1) {
                    paint.color = 0xFFFFFFFF.toInt()
                    c.drawRect(bar.left, y - dp(0.6f), bar.right, y + dp(0.6f), paint)
                }
            }
            c.restore()
        }
        if (currentHour != null && hours.all { it.total <= 0.0 }) {
            c.drawText("Noch keine Verbrauchsdaten", (left + right) / 2, (top + bottom) / 2, axis)
        }

        // Stunden-Beschriftung 0, 2, … 22
        for (hour in 0..22 step 2) {
            val cx = left + slot * hour + slot / 2f
            c.drawText(hour.toString(), cx, h - dp(5f), axis)
        }
        return bmp
    }

    /** Achsenmaximum und Schrittweite: 2–4 „schöne“ Schritte, damit die Balken die Höhe gut nutzen. */
    internal fun scale(maxValue: Double): Pair<Double, Double> {
        if (maxValue <= 0.0) return 2.0 to 1.0
        for (step in listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)) {
            val steps = ceil(maxValue * 1.05 / step)
            if (steps <= 4) return (maxOf(steps, 2.0) * step) to step
        }
        return ceil(maxValue / 100) * 100 to 100.0
    }
}
