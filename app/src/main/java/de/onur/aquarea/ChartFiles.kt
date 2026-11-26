package de.onur.aquarea

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import de.onur.aquarea.api.DayConsumption
import java.io.File

/**
 * Das Diagramm wird als PNG-Datei abgelegt und dem Startbildschirm nur als Adresse (URI) übergeben.
 * So bleibt jedes Widget-Update winzig und schnell – vorher wurde bei jedem Tippen das ganze Bild
 * (mehrere MB) an den Startbildschirm geschickt, was das Umschalten der Symbole ausgebremst hat.
 * Ein Bild wird nur neu gezeichnet, wenn sich Daten oder Größe ändern.
 */
object ChartFiles {
    fun uri(context: Context, day: DayConsumption?, w: Int, h: Int, scale: Float, currentHour: Int?): Uri {
        val dir = File(context.cacheDir, "charts").apply { mkdirs() }
        val key = listOf("v2", day?.date, day?.hours?.hashCode(), w, h, scale, currentHour).hashCode()
        val name = "chart_${Integer.toHexString(key)}.png"
        val file = File(dir, name)
        if (!file.exists()) {
            val tmp = File(dir, "$name.tmp")
            val bmp = ChartRenderer.render(day, w, h, scale, currentHour)
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            tmp.renameTo(file)
            // alte Bilder aufräumen
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
        }
        return Uri.parse("content://${context.packageName}.charts/$name")
    }
}

/** Liefert die Diagramm-Bilder an den Startbildschirm (nur lesend, nur PNGs aus dem Cache). */
class ChartProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val name = uri.lastPathSegment ?: return null
        if (!name.matches(Regex("chart_[0-9a-f]+\\.png"))) return null
        val file = File(File(context!!.cacheDir, "charts"), name)
        if (!file.exists()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
