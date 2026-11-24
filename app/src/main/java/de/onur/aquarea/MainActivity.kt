package de.onur.aquarea

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import de.onur.aquarea.api.HeatPump
import de.onur.aquarea.api.HeatPumpStatus
import de.onur.aquarea.api.ModeCommand
import de.onur.aquarea.api.activity
import java.util.concurrent.Executors

/**
 * Einfache App: Anmeldung, Geräteauswahl und Steuerung.
 * Die eigentliche Anzeige ist das Widget auf dem Startbildschirm.
 */
class MainActivity : Activity() {
    private val repo by lazy { Repository.get(this) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var progress: ProgressBar
    private var pumps: List<HeatPump> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        render()
        if (repo.storage.isLoggedIn) reload()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Ansichten

    private fun render() {
        content.removeAllViews()
        if (!repo.storage.isLoggedIn) renderLogin() else renderDevice()
    }

    private fun renderLogin() {
        content.addView(title("LWP Widget"))
        content.addView(text("Melde dich mit deiner Panasonic-ID an – also denselben Zugangsdaten wie in der Comfort-Cloud-App.", secondary = true))
        val card = card()
        val email = EditText(this).apply {
            hint = "E-Mail"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(repo.storage.email.orEmpty())
        }
        val password = EditText(this).apply {
            hint = "Passwort"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(email)
        card.addView(password)
        card.addView(button("Anmelden") {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) return@button toast("Bitte E-Mail und Passwort eingeben")
            background({ repo.login(e, p) }) { list ->
                pumps = list
                if (list.isEmpty()) toast("Angemeldet, aber keine Wärmepumpe im Konto gefunden")
                render()
                reload()
            }
        })
        content.addView(card)
        content.addView(text(
            "Hinweis: Die App nutzt dieselbe Schnittstelle wie die Comfort-Cloud-App. Zugangsdaten bleiben " +
                "verschlüsselt auf diesem Gerät und gehen nur an Panasonic.",
            secondary = true, small = true,
        ))
    }

    private fun renderDevice() {
        val storage = repo.storage
        val status = storage.status
        content.addView(title(status?.name ?: storage.selectedPump?.name ?: "Wärmepumpe"))

        if (pumps.size > 1) {
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, pumps.map { it.name })
            spinner.setSelection(pumps.indexOfFirst { it.guid == storage.selectedPump?.guid }.coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pumps[pos].guid != storage.selectedPump?.guid) {
                        storage.selectedPump = pumps[pos]
                        storage.status = null
                        reload()
                    }
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
            }
            content.addView(spinner)
        }

        storage.lastError?.let { content.addView(text("⚠ $it")) }
        if (status == null) {
            content.addView(text("Lade Daten …", secondary = true))
            return
        }

        // Live-Werte
        val live = card()
        live.addView(row("Status", status.activity.label))
        live.addView(row("Außentemperatur", status.outdoorTemperature?.let { "$it °C" } ?: "–"))
        live.addView(row("Vorlauftemperatur", status.flowTemperature?.let { "$it °C" } ?: "–"))
        live.addView(row("Modus", status.mode.label))
        if (status.faults.isNotEmpty()) live.addView(row("Fehler", status.faults.joinToString()))
        live.addView(button(if (status.on) "Wärmepumpe ausschalten" else "Wärmepumpe einschalten") {
            control { c, p, _ -> c.setPower(p, !status.on) }
        })
        content.addView(live)

        // Zonen
        for (zone in status.zones) {
            val card = card()
            val cooling = status.mode.isCooling
            val set = if (cooling) zone.coolSet else zone.heatSet
            val min = (if (cooling) zone.coolMin else zone.heatMin) ?: -5
            val max = (if (cooling) zone.coolMax else zone.heatMax) ?: 5
            card.addView(text(zone.name, bold = true))
            card.addView(row("Ist", zone.temperatureNow?.let { "$it °C" } ?: "–"))
            val setLabel = when {
                set == null -> "–"
                zone.isOffset -> (if (set > 0) "+$set" else "$set") + " (Heizkurve)"
                else -> "$set °C"
            }
            card.addView(stepper("Soll", setLabel, enabled = set != null,
                onMinus = { if (set != null && set > min) control { c, p, _ -> c.setZoneTemperature(p, zone.id, set - 1, cooling) } },
                onPlus = { if (set != null && set < max) control { c, p, _ -> c.setZoneTemperature(p, zone.id, set + 1, cooling) } },
            ))
            content.addView(card)
        }

        // Warmwasser
        status.tank?.let { tank ->
            val card = card()
            card.addView(text("Warmwasser", bold = true))
            card.addView(row("Ist", tank.temperatureNow?.let { "$it °C" } ?: "–"))
            val set = tank.heatSet
            card.addView(stepper("Soll", set?.let { "$it °C" } ?: "–", enabled = set != null,
                onMinus = { if (set != null && set > (tank.heatMin ?: 40)) control { c, p, _ -> c.setTankTemperature(p, set - 1) } },
                onPlus = { if (set != null && set < (tank.heatMax ?: 65)) control { c, p, _ -> c.setTankTemperature(p, set + 1) } },
            ))
            card.addView(button(if (tank.on) "Warmwasser ausschalten" else "Warmwasser einschalten") {
                control { c, p, s -> c.setTankPower(p, s, !tank.on) }
            })
            content.addView(card)
        }

        // Modus
        val modes = card()
        modes.addView(text("Modus setzen", bold = true))
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in ModeCommand.entries) {
            modeRow.addView(button(m.label) { control { c, p, s -> c.setMode(p, s, m) } },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        modes.addView(modeRow)
        content.addView(modes)

        content.addView(button("Aktualisieren") { reload() })
        if (!hasWidget()) {
            content.addView(text(
                "Widget hinzufügen: lange auf den Startbildschirm drücken → Widgets → „LWP Widget“.",
                secondary = true,
            ))
            if (Build.VERSION.SDK_INT >= 26) {
                content.addView(button("Widget jetzt hinzufügen") {
                    val mgr = AppWidgetManager.getInstance(this)
                    if (mgr.isRequestPinAppWidgetSupported) {
                        mgr.requestPinAppWidget(ComponentName(this, LwpWidgetProvider::class.java), null, null)
                    } else toast("Dein Startbildschirm unterstützt das nicht – bitte manuell hinzufügen")
                })
            }
        }
        content.addView(button("Abmelden") {
            executor.execute { repo.logout() }
            pumps = emptyList()
            LwpWidgetProvider.renderAll(this)
            render()
        })
    }

    // ------------------------------------------------------------------ Aktionen

    private fun reload() {
        background({
            if (pumps.isEmpty()) pumps = runCatching { repo.pumps() }.getOrDefault(emptyList())
            repo.refresh()
        }) {
            LwpWidgetProvider.renderAll(this)
            render()
        }
    }

    private fun control(action: (de.onur.aquarea.api.ComfortCloudClient, HeatPump, HeatPumpStatus) -> Unit) {
        background({ repo.control(action) }) {
            toast("Befehl gesendet")
            LwpWidgetProvider.renderAll(this)
            render()
        }
    }

    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        progress.visibility = View.VISIBLE
        executor.execute {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.visibility = View.INVISIBLE
                result.onSuccess(done).onFailure { toast(it.message ?: "Fehler: ${it.javaClass.simpleName}") }
            }
        }
    }

    private fun hasWidget() = LwpWidgetProvider.widgetIds(this).isNotEmpty()

    // ------------------------------------------------------------------ kleine UI-Helfer

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(R.color.text_primary))
        setPadding(0, 0, 0, dp(12))
    }

    private fun text(t: String, secondary: Boolean = false, bold: Boolean = false, small: Boolean = false) = TextView(this).apply {
        text = t
        textSize = if (small) 12f else if (bold) 17f else 15f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(getColor(R.color.card))
            cornerRadius = dp(16).toFloat()
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun row(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        addView(TextView(context).apply {
            text = label; textSize = 15f; setTextColor(getColor(R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = value; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(getColor(R.color.text_primary))
        })
    }

    private fun stepper(label: String, value: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = label; textSize = 15f; setTextColor(getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(Button(context).apply { text = "−"; isEnabled = enabled; setOnClickListener { onMinus() } })
            addView(TextView(context).apply {
                text = value; textSize = 17f; setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER; minWidth = dp(72)
                setTextColor(getColor(R.color.text_primary))
            })
            addView(Button(context).apply { text = "+"; isEnabled = enabled; setOnClickListener { onPlus() } })
        }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
