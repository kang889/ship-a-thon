package com.studentmemory.copilot

import android.Manifest
import android.app.Activity
import com.studentmemory.copilot.ui.PackBackDialog as AlertDialog
import android.content.Intent
import android.graphics.Color
import com.studentmemory.copilot.ui.PackBackTheme
import com.studentmemory.copilot.ui.PackBackComponents
import com.studentmemory.copilot.ui.Tone
import com.studentmemory.copilot.ui.PackBackDialog
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import com.studentmemory.copilot.services.BackendClient
import com.studentmemory.copilot.services.Account
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var store: CoreStore
    private var state = JSONObject()
    private val ink get() = PackBackTheme(this).ink
    private val green get() = PackBackTheme(this).brand
    private var importKind = "timetable"
    private var importEventId = ""
    // Session-only identity token. No private service keys are accepted by this client.
    private var identityToken = ""
    private var showWeek = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CoreStore(this)
        Account.initialize(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    override fun onResume() { super.onResume(); run(JSONObject().put("action", "view")) }
    private fun run(command: JSONObject): Boolean {
        try {
            val result = store.execute(command)
            state = result.getJSONObject("state")
            val view = result.getJSONObject("view")
            Reminders.schedule(this, view.getJSONArray("notifications"))
            render(view)
            return true
        } catch (error: Exception) {
            AlertDialog.Builder(this).setTitle("Couldn't complete that action")
                .setMessage(error.message).setPositiveButton("OK", null).show()
            return false
        }
    }
    private fun text(parent: LinearLayout, value: String, size: Float = 16f, color: Int = ink): TextView {
        return PackBackComponents(this).Label(value, size, size >= 22f, color).apply {
            setPadding(0, PackBackTheme(this@MainActivity).dp(8), 0, PackBackTheme(this@MainActivity).dp(8))
            parent.addView(this)
        }
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(PackBackComponents(this).SecondaryButton(title, action))
    }
    private fun column(parent: LinearLayout): LinearLayout = PackBackComponents(this).Card().also { parent.addView(it) }
    private fun time(minute: Long) = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    private fun render(view: JSONObject) {
        val events = view.getJSONArray("events")
        val returning = !showWeek && events.length() > 0 && events.getJSONObject(0).getString("phase") == "BRING BACK"
        val palette = PackBackTheme(this, returning)
        val ui = PackBackComponents(this, palette)
        val root = ui.Column().apply { setBackgroundColor(palette.background) }
        root.setOnApplyWindowInsetsListener { target, insets ->
            target.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        content = ui.Column().apply { setPadding(palette.dp(20),palette.dp(20),palette.dp(20),palette.dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(ui.BottomNav(null, { manage() }, { edit(null) }, { memory() }))
        setContentView(root)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.card
        window.decorView.systemUiVisibility = if (palette.dark || returning) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        // TODO(PDF p8/19): no display-name binding or timed weather forecast exists. Omit sample identity and forecast time.
        val almost = events.length() > 0 && events.getJSONObject(0).getString("phase") == "NEXT"
        if (!returning) {
            content.addView(ui.Label(if (almost) "Almost time." else "Good morning.",32f,true,weight=700))
            ui.Space(content,8)
            content.addView(ui.Label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · d MMMM")),13f,color=palette.muted))
        }
        if (view.optBoolean("umbrella")) {
            ui.Space(content,12)
            content.addView(ui.Pill("Rain likely" + if(view.optBoolean("weatherMock")) " · demo weather" else "",Tone.AI))
        }
        if (events.length() > 1) button(content, if (showWeek) "Focus on next event" else "Show upcoming week") {
            showWeek = !showWeek; render(view)
        }
        if (events.length() == 0) {
            ui.Space(content,96)
            content.addView(ui.IconTile("bag",Tone.Brand,104).apply { layoutParams = LinearLayout.LayoutParams(palette.dp(104),palette.dp(104)).apply { gravity=android.view.Gravity.CENTER_HORIZONTAL } })
            ui.Space(content,24)
            content.addView(ui.Label("Nothing to bring today.",24f,true,weight=700).apply { gravity=android.view.Gravity.CENTER })
            ui.Space(content,12)
            content.addView(ui.Label("Add a class, then tell us what you need to bring.",16f,color=palette.secondary).apply { gravity=android.view.Gravity.CENTER })
            ui.Space(content,56)
            // TODO(PDF p19): no next-week summary is returned for an empty event list. Do not invent Monday's class.
        }
        for (i in 0 until if (showWeek) events.length() else minOf(events.length(), 1)) {
            val event = events.getJSONObject(i)
            val items = event.getJSONArray("items")
            val back = event.getString("phase") == "BRING BACK"
            val eventStart = LocalDateTime.ofEpochSecond(event.getLong("start") * 60,0,ZoneOffset.UTC)
            val remaining = event.getLong("start") - LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) / 60
            val countdown = if (remaining > 0) "Starts in " + if (remaining >= 60) "${remaining / 60}h ${remaining % 60}m" else "${remaining} min" else null
            if (back && returning) {
                content.addView(ui.Pill(event.getString("title"),Tone.Grey)); ui.Space(content,30)
                content.addView(ui.SectionHeader("Bring back"))
                content.addView(ui.Label("Before\nyou go.",44f,true,weight=800)); ui.Space(content,12)
                content.addView(ui.Label("Make sure everything leaves with you.",16f,color=palette.secondary)); ui.Space(content,28)
            } else {
                content.addView(ui.NextClassHeroCard(event.getString("title"),eventStart.format(DateTimeFormatter.ofPattern("h:mm a")),
                    event.getString("location"),countdown,event.getString("phase") == "NEXT",findEvent(event.getString("id"))?.optString("course")))
                var packed = 0
                for (j in 0 until items.length()) if (items.getJSONObject(j).getString("state") in setOf("PACKED","BROUGHT","IN_USE","NEEDS_TO_RETURN","SAFE")) packed++
                content.addView(ui.SectionHeader("Bring", "$packed of ${items.length()} packed"))
            }
            if (event.getBoolean("overlap")) content.addView(ui.Pill("Overlaps another event",Tone.Warning))
            if (items.length() == 0) content.addView(ui.Label("Add your essentials using Edit event.",14f,color=palette.muted))
            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j)
                val actions = item.getJSONArray("actions")
                val updateItem: (() -> Unit)? = if (actions.length() > 0) ({
                    val labels = Array(actions.length()) { actions.getString(it).replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
                    PackBackDialog.Builder(this).setTitle(item.getString("name")).setMessage(item.getString("reason")).setItems(labels) { _, index ->
                        run(JSONObject().put("action", "transition").put("occurrence", event.getString("key"))
                            .put("item", item.getString("id")).put("target", actions.getString(index)))
                    }.show()
                }) else null
                val highRisk = item.getString("priority") in setOf("HIGH","VERY HIGH")
                val itemState = item.getString("state")
                if (returning && back) content.addView(ui.BringBackCard(item.getString("name"),item.getString("reason"),highRisk,itemState == "SAFE",updateItem))
                else content.addView(ui.ItemRow(item.getString("name"),itemState.replace('_',' ').lowercase().replaceFirstChar { it.uppercase() },
                    item.getString("reason"),highRisk,itemState in setOf("PACKED","BROUGHT","IN_USE","NEEDS_TO_RETURN","SAFE"),updateItem))
            }
            if (returning) {
                var safeCount=0
                for (j in 0 until items.length()) if(items.getJSONObject(j).getString("state") == "SAFE") safeCount++
                ui.Space(content,28); content.addView(ui.SectionHeader("$safeCount of ${items.length()} with you",event.getString("location")))
                content.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                    max=items.length().coerceAtLeast(1); progress=safeCount
                    progressTintList=android.content.res.ColorStateList.valueOf(palette.safe)
                    progressBackgroundTintList=android.content.res.ColorStateList.valueOf(palette.line)
                },LinearLayout.LayoutParams(-1,palette.dp(5)))
                // TODO(PDF p10–14): no bulk-return, forgot-location, snooze, dedicated WAIT or explanation-route callbacks.
                // Existing per-item transition menu remains the sole handler; never synthesize a SAFE transition.
            }
            button(content, "Edit event") { edit(findEvent(event.getString("id"))) }
        }
        val tasks = view.getJSONArray("tasks")
        if (tasks.length() > 0) content.addView(ui.SectionHeader("Do before class"))
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val card = ui.Card(); content.addView(card)
            val taskHeader = ui.Row(); taskHeader.addView(ui.IconTile("document",Tone.Warning))
            taskHeader.addView(ui.Label(task.getString("title"),18f,true,weight=700).apply { setPadding(palette.dp(12),0,0,0) },LinearLayout.LayoutParams(0,-2,1f)); card.addView(taskHeader)
            ui.Space(card,12)
            card.addView(ui.Label("${task.getInt("duration")} minutes · due ${time(task.getLong("deadline"))}",13f,color=palette.muted))
            ui.Space(card,8)
            card.addView(ui.Label(if (task.isNull("slot")) "No free slot before the deadline. Adjust your schedule."
                else "Suggested start: ${time(task.getLong("slot"))}",14f,color=palette.secondary))
            button(card, "Completed") {
                run(JSONObject().put("action", "complete_task").put("event", task.getString("event"))
                    .put("task", task.getString("id")).put("completed", true))
            }
            // TODO(PDF p18): alternative preparation slots, start/pause and rescheduling callbacks do not exist.
        }
        if(events.length()==0) ui.Space(content,100)
        content.addView(ui.SectionHeader("Your tools"))
        // Existing routes/callbacks stay available. No Profile/onboarding route is added by this reskin.
        button(content, "+ Add a class or event") { edit(null) }
        button(content, "Manage timetable") { manage() }
        button(content, "Import timetable or instruction") { chooseImport() }
        button(content, "Student Memory Pro") { Billing.show(this) }
        button(content, "Weather context") { weather() }
        button(content, "Student memory") { memory() }
        if (Account.configured) button(content, "Sign in / create account") { signIn() }
        if (BuildConfig.BACKEND_URL.isNotBlank()) button(content, "Connect backend session") {
            val input = EditText(this).apply { hint = "Firebase identity token (or local development token)" }
            AlertDialog.Builder(this).setTitle("Connect session").setView(input)
                .setPositiveButton("Connect") { _, _ -> identityToken = input.text.toString().trim() }
                .setNegativeButton("Cancel", null).show()
        }
    }
    private fun findEvent(id: String): JSONObject? {
        val events = state.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) if (events.getJSONObject(i).getString("id") == id) return events.getJSONObject(i)
        return null
    }
    private fun chooseImport() {
        // TODO(PDF p17): plus retains edit(null); unsupported event/task categories are not new routes.
        AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Import).setTitle("Import")
            .setItems(arrayOf("Timetable screenshot", "Lecturer instruction")) { _, index ->
                importKind = if (index == 0) "timetable" else "instruction"
                if (index == 0) chooseInput() else {
                    val events = state.optJSONArray("events") ?: JSONArray()
                    if (events.length() == 0) { Toast.makeText(this, "Add a class first", Toast.LENGTH_LONG).show(); return@setItems }
                    AlertDialog.Builder(this).setTitle("Which class?")
                        .setItems(Array(events.length()) { events.getJSONObject(it).getString("title") }) { _, choice ->
                            importEventId = events.getJSONObject(choice).getString("id"); chooseInput()
                        }.show()
                }
            }.show()
    }
    private fun chooseInput() {
        AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Import).setTitle(if (BuildConfig.BACKEND_URL.isBlank()) "Demo extraction · sample results, no AI calls" else "Extract once, review before saving")
            .setItems(arrayOf("Choose screenshot", "Paste text")) { _, index ->
                if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 100)
                else {
                    val input = EditText(this).apply { hint = "Paste the timetable or instruction"; minLines = 4 }
                    AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Import).setTitle("Paste text").setView(input)
                        .setPositiveButton("Preview") { _, _ -> extract(JSONObject().put("text", input.text.toString())) }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }
    @Deprecated("Platform callback retained to avoid an additional UI dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 2_000_000) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Couldn't open image")
            require(bytes.size <= 2_000_000) { "Choose an image smaller than 2 MB" }
            extract(JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("mime_type", contentResolver.getType(uri) ?: "image/png"))
        } catch (error: Exception) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
    }
    private fun extract(input: JSONObject) {
        val kind = importKind
        val eventId = importEventId
        input.put("reference_date", LocalDate.now().toString())
        Toast.makeText(this, "Preparing preview…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val command = JSONObject().put("action", "preview_$kind").put("import_id", UUID.randomUUID().toString())
                    .put("event_id", eventId)
                if (BuildConfig.BACKEND_URL.isBlank()) command.put("input", input.toString())
                else {
                    val result = BackendClient(if (Account.configured) Account.token() else identityToken).request("/ai/$kind/extract", input)
                    command.put("data", result.getJSONObject("data")).put("mock", result.getBoolean("mock"))
                }
                val result = store.execute(command)
                if (kind == "instruction") result.put("source_text", input.optString("text"))
                runOnUiThread { preview(result) }
            } catch (error: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Use manual entry")
                    .setMessage(error.message).setPositiveButton("OK", null).show() }
            }
        }.start()
    }
    private fun preview(result: JSONObject) {
        val proposed = result.getJSONArray("preview")
        val summary = StringBuilder()
        for (i in 0 until proposed.length()) {
            val event = proposed.getJSONObject(i)
            summary.append(event.getString("title")).append("\n").append(time(event.getLong("start")))
                .append("\n").append(event.getString("location")).append("\n")
            val items = event.getJSONArray("items")
            for (j in 0 until items.length()) summary.append("Bring: ").append(items.getJSONObject(j).getString("name")).append("\n")
            val tasks = event.getJSONArray("tasks")
            for (j in 0 until tasks.length()) summary.append("Do: ").append(tasks.getJSONObject(j).getString("title")).append("\n")
            summary.append("\n")
        }
        AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Import).setTitle(if (result.getBoolean("mock")) "DEMO · sample results, not your image" else if(result.has("source_text")) "Lecturer message" else "Review timetable")
            .setCards(summary.toString().split("\n\n").filter { it.isNotBlank() }).setNegativeButton("Discard", null)
            .setNeutralButton("Edit details") { _, _ ->
                AlertDialog.Builder(this).setTitle("Edit before confirming")
                    .setItems(Array(proposed.length()) { proposed.getJSONObject(it).getString("title") }) { _, index ->
                        edit(proposed.getJSONObject(index)) { updated ->
                            proposed.put(index, updated); preview(result)
                        }
                    }.show()
            }
            .setPositiveButton("Confirm and save") { _, _ ->
                if (!run(JSONObject().put("action", "confirm_import").put("confirmed", true).put("events", proposed))) return@setPositiveButton
                val source = if (result.has("source_text")) result.optString("source_text").ifBlank { summary.toString() } else ""
                if (source.isNotBlank()) {
                    AlertDialog.Builder(this).setTitle("Remember this instruction?")
                        .setMessage("Save the confirmed instruction so you can find it later.")
                        .setPositiveButton("Save memory") { _, _ ->
                            memoryRequest("/memory", JSONObject().put("id", UUID.randomUUID().toString()).put("text", source)) {
                                Toast.makeText(this, "Instruction remembered", Toast.LENGTH_SHORT).show()
                            }
                        }.setNegativeButton("Skip", null).show()
                }
            }.show()
    }
    private fun backend(path: String, body: JSONObject? = null, done: (JSONObject) -> Unit) {
        Thread {
            try {
                val token = if (Account.configured) Account.token() else identityToken
                val result = BackendClient(token).request(path, body)
                runOnUiThread { done(result) }
            } catch (error: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Service unavailable")
                    .setMessage(error.message).setPositiveButton("OK", null).show() }
            }
        }.start()
    }
    private fun weather() {
        if (BuildConfig.BACKEND_URL.isBlank()) {
            run(JSONObject().put("action", "weather").put("mock", true))
            Toast.makeText(this, "Demo forecast: 75% rain. Umbrella appears for travel today.", Toast.LENGTH_LONG).show()
            return
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val latitude = EditText(this).apply { hint = "Latitude"; form.addView(this) }
        val longitude = EditText(this).apply { hint = "Longitude"; form.addView(this) }
        AlertDialog.Builder(this).setTitle("Weather location (no background tracking)").setView(form)
            .setPositiveButton("Get forecast") { _, _ ->
                val lat = latitude.text.toString().toDoubleOrNull()
                val lon = longitude.text.toString().toDoubleOrNull()
                if (lat == null || lon == null) { Toast.makeText(this, "Enter valid coordinates", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                backend("/weather?latitude=$lat&longitude=$lon") { result ->
                    if (result.optBoolean("available")) run(JSONObject().put("action", "weather")
                        .put("rain_probability", result.getInt("rain_probability")).put("mock", result.getBoolean("mock")))
                    else Toast.makeText(this, "Forecast unavailable. Your checklist still works.", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun memory() {
        val input = EditText(this).apply { hint = "Write a note, or ask what you need tomorrow"; minLines = 3 }
        // TODO(PDF p21–23): no recent-memory fetch or source/date binding in this existing search flow.
        AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Memory).setTitle("Memory").setView(input)
            .setPositiveButton("Search") { _, _ ->
                memoryRequest("/memory/search", JSONObject().put("query", input.text.toString())) { result ->
                    val memories = result.getJSONArray("memories")
                    AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Memory).setTitle(if (result.optBoolean("mock")) "Demo keyword search" else "Relevant memories")
                        .setItems(Array(memories.length()) { memories.getJSONObject(it).getString("text") }) { _, index ->
                            val selected = memories.getJSONObject(index)
                            AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Memory).setTitle("Memory").setCards(listOf(selected.getString("text"))).setPositiveButton("Close", null)
                                .setNeutralButton("Delete") { _, _ -> deleteMemory(selected.getString("id")) }.show()
                        }.setPositiveButton("Close", null).show()
                }
            }.setNeutralButton("Save note") { _, _ ->
                memoryRequest("/memory", JSONObject().put("id", UUID.randomUUID().toString()).put("text", input.text.toString())) {
                    Toast.makeText(this, "Note saved", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }
    private fun deleteMemory(id: String) {
        if (BuildConfig.BACKEND_URL.isBlank()) {
            run(JSONObject().put("action", "memory_delete").put("id", id)); return
        }
        Thread {
            try {
                BackendClient(if (Account.configured) Account.token() else identityToken).delete("/memory/$id")
                runOnUiThread { Toast.makeText(this, "Memory deleted", Toast.LENGTH_SHORT).show() }
            } catch (error: Exception) { runOnUiThread { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() } }
        }.start()
    }
    private fun memoryRequest(path: String, body: JSONObject, done: (JSONObject) -> Unit) {
        if (BuildConfig.BACKEND_URL.isNotBlank()) { backend(path, body, done); return }
        try {
            val command = JSONObject(body.toString()).put("action", if (path.endsWith("/search")) "memory_search" else "memory_save")
            val result = store.execute(command)
            state = result.getJSONObject("state")
            done(result)
        } catch (error: Exception) { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
    }
    private fun signIn() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val email = EditText(this).apply { hint = "Email"; inputType = 33; form.addView(this) }
        val password = EditText(this).apply { hint = "Password"; inputType = 129; form.addView(this) }
        fun authenticate(create: Boolean) {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            Thread {
                try {
                    Account.signIn(emailText, passwordText, create)
                    runOnUiThread { Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show() }
                } catch (error: Exception) {
                    runOnUiThread { Toast.makeText(this, error.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
        }
        AlertDialog.Builder(this).setTitle("Your student account").setView(form)
            .setPositiveButton("Sign in") { _, _ -> authenticate(false) }
            .setNeutralButton("Create account") { _, _ -> authenticate(true) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun manage() {
        val events = state.optJSONArray("events") ?: JSONArray()
        if (events.length() == 0) { edit(null); return }
        // TODO(PDF p15): the existing timetable chooser has no selected-day callback or free-window data.
        PackBackDialog.Builder(this).presentation(PackBackDialog.Layout.Schedule).setTitle("Your timetable")
            .setItems(Array(events.length()) { events.getJSONObject(it).let { event ->
                event.getString("title") + "\n" + time(event.getLong("start")) + "\n" + event.optString("location")
            } }) { _, index -> edit(events.getJSONObject(index)) }.show()
    }
    private fun edit(original: JSONObject?, onSave: ((JSONObject) -> Unit)? = null) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        fun field(label: String, value: String): EditText {
            text(form, label, 13f)
            return EditText(this).apply { setText(value); contentDescription = label; form.addView(this) }
        }
        val title = field("Event title", original?.optString("title") ?: "")
        val date = original?.let { LocalDateTime.ofEpochSecond(it.getLong("start") * 60, 0, ZoneOffset.UTC) }
        val endDate = original?.let { LocalDateTime.ofEpochSecond(it.getLong("end") * 60, 0, ZoneOffset.UTC) }
        val day = field("Date · YYYY-MM-DD", (date?.toLocalDate() ?: LocalDate.now()).toString())
        val start = field("Start · HH:mm", date?.toLocalTime()?.toString() ?: "14:00")
        val end = field("End · HH:mm", endDate?.toLocalTime()?.toString() ?: "16:00")
        val location = field("Room or location", original?.optString("location") ?: "")
        text(form, "Repeat", 13f)
        val repeat = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Once", "Daily", "Weekly"))
            setSelection(when(original?.optInt("repeatDays")) { 1 -> 1; 7 -> 2; else -> 0 }); form.addView(this)
        }
        val oldItems = original?.optJSONArray("items") ?: JSONArray()
        val items = field("Bring · one item per line (applies to every occurrence)",
            (0 until oldItems.length()).joinToString("\n") { oldItems.getJSONObject(it).getString("name") })
        items.minLines = 2
        val once = CheckBox(this).apply { text = "New items: only for this date"; form.addView(this) }
        val oldTasks = original?.optJSONArray("tasks") ?: JSONArray()
        val prep = field("Add preparation task (one-time, due before this event)", "")
        val duration = field("Preparation minutes", "60")
        // TODO(PDF p16): no per-event reminder-setting callbacks; retain this editor's exact fields and save handler.
        val dialog = PackBackDialog.Builder(this).presentation(PackBackDialog.Layout.Event).setTitle(if (original == null) "Add event" else "Edit event")
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (original != null && onSave == null) dialog.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setTitle("Delete this event and all its occurrences?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    run(JSONObject().put("action", "delete_event").put("id", original.getString("id")))
                }.show()
        }
        val shown = dialog.create()
        shown.setOnShowListener {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val event = original?.let { JSONObject(it.toString()) } ?: JSONObject().put("id", UUID.randomUUID().toString())
                    val startMinute = LocalDateTime.parse("${day.text}T${start.text}").toEpochSecond(ZoneOffset.UTC) / 60
                    event.put("title", title.text.toString().trim()).put("start", startMinute)
                        .put("end", LocalDateTime.parse("${day.text}T${end.text}").toEpochSecond(ZoneOffset.UTC) / 60)
                        .put("location", location.text.toString()).put("repeatDays", intArrayOf(0, 1, 7)[repeat.selectedItemPosition])
                    val itemArray = JSONArray()
                    for (name in items.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
                        var existing: JSONObject? = null
                        for (i in 0 until oldItems.length()) if (oldItems.getJSONObject(i).getString("name") == name) existing = oldItems.getJSONObject(i)
                        itemArray.put(existing ?: JSONObject().put("id", UUID.randomUUID().toString()).put("name", name).put("importance", 0.8)
                            .put("onlyDay", if (once.isChecked) LocalDate.parse(day.text).toEpochDay() else -1))
                    }
                    event.put("items", itemArray)
                    val taskArray = JSONArray(oldTasks.toString())
                    if (prep.text.isNotBlank()) taskArray.put(JSONObject().put("id", UUID.randomUUID().toString())
                        .put("title", prep.text.toString()).put("duration", duration.text.toString().toInt()).put("deadline", startMinute))
                    event.put("tasks", taskArray)
                    if (onSave != null) { onSave(event); shown.dismiss(); return@setOnClickListener }
                    val result = store.execute(JSONObject().put("action", "save_event").put("event", event))
                    state = result.getJSONObject("state")
                    Reminders.schedule(this, result.getJSONObject("view").getJSONArray("notifications"))
                    render(result.getJSONObject("view")); shown.dismiss()
                } catch (error: Exception) { Toast.makeText(this, error.message ?: "Check the entered values", Toast.LENGTH_LONG).show() }
            }
        }
        shown.show()
    }
}
