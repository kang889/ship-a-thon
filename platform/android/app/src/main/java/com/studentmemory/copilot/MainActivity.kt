package com.studentmemory.copilot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
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
    private val ink = Color.rgb(25, 46, 43)
    private val green = Color.rgb(25, 101, 76)
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
        return TextView(this).apply {
            text = value; textSize = size; setTextColor(color); setPadding(0, 8, 0, 8)
            parent.addView(this)
        }
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title; isAllCaps = false; setTextColor(green); minHeight = 48
            setOnClickListener { action() }
        })
    }
    private fun column(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20)
        setBackgroundColor(Color.WHITE)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 10, 0, 10) })
    }
    private fun backendToken(): String {
      return if (BuildConfig.DEBUG && BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2")) {
        "local-development-only"
      } else if (Account.configured) {
        Account.token()
      } else {
        identityToken
      }
    }
    private fun syncEvents() {
     if (BuildConfig.BACKEND_URL.isBlank()) return

     val events = state.optJSONArray("events") ?: JSONArray()
     val body = JSONObject()
        .put("events", JSONArray(events.toString()))

     Thread {
        try {
            BackendClient(backendToken()).request("/sync", body)
        } catch (error: Exception) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Saved locally; cloud sync pending",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
     }.start()
    }
    private fun time(minute: Long) = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    private fun render(view: JSONObject) {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 44, 28, 28)
            setBackgroundColor(Color.rgb(242, 246, 241))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        text(content, "STUDENT MEMORY", 12f, green)
        text(content, "A little less to remember.", 30f)
        text(content, "Bring it. Do it. Bring it back.", 16f)
        text(content, "Offline edition · your day stays on this device", 12f)
        button(content, "+ Add a class or event") { edit(null) }
        button(content, "Manage timetable") { manage() }
        button(content, "Import timetable or instruction") { chooseImport() }
        button(content, "Student Memory Pro") { Billing.show(this) }
        button(content, "Weather context") { weather() }
        button(content, "Student memory") { memory() }
        if (view.optBoolean("umbrella")) text(column(content),
            "Bring an umbrella · rain is likely" + if (view.optBoolean("weatherMock")) " (demo weather)" else "", 18f)
        if (Account.configured) button(content, "Sign in / create account") { signIn() }
        if (BuildConfig.BACKEND_URL.isNotBlank()) button(content, "Connect backend session") {
            val input = EditText(this).apply { hint = "Firebase identity token (or local development token)" }
            AlertDialog.Builder(this).setTitle("Connect session").setView(input)
                .setPositiveButton("Connect") { _, _ -> identityToken = input.text.toString().trim() }
                .setNegativeButton("Cancel", null).show()
        }
        val events = view.getJSONArray("events")
        if (events.length() > 1) button(content, if (showWeek) "Focus on next event" else "Show upcoming week") {
            showWeek = !showWeek; render(view)
        }
        if (events.length() == 0) {
            text(column(content), "Your day starts here. Add a class, then tell us what you need to bring.", 20f)
        }
        for (i in 0 until if (showWeek) events.length() else minOf(events.length(), 1)) {
            val event = events.getJSONObject(i)
            val card = column(content)
            text(card, event.getString("phase"), 12f, green)
            text(card, event.getString("title"), 23f)
            text(card, time(event.getLong("start")) + "\n" + event.getString("location"), 14f)
            if (event.getBoolean("overlap")) text(card, "Overlaps another event", 14f, Color.rgb(166, 73, 29))
            val items = event.getJSONArray("items")
            if (items.length() == 0) text(card, "Add your essentials using Edit event.", 14f)
            for (j in 0 until items.length()) {
                val item = items.getJSONObject(j)
                text(card, item.getString("name") + " · " + item.getString("state").replace('_', ' '), 18f)
                text(card, item.getString("priority") + " · " + item.getString("reason"), 12f)
                val actions = item.getJSONArray("actions")
                if (actions.length() > 0) button(card, "Update " + item.getString("name")) {
                    val labels = Array(actions.length()) { actions.getString(it).replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
                    AlertDialog.Builder(this).setTitle(item.getString("name")).setItems(labels) { _, index ->
                        run(JSONObject().put("action", "transition").put("occurrence", event.getString("key"))
                            .put("item", item.getString("id")).put("target", actions.getString(index)))
                    }.show()
                }
            }
            button(card, "Edit event") { edit(findEvent(event.getString("id"))) }
        }
        val tasks = view.getJSONArray("tasks")
        if (tasks.length() > 0) text(content, "DO · Make room to prepare", 22f)
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val card = column(content)
            text(card, task.getString("title"), 20f)
            text(card, "${task.getInt("duration")} minutes · due ${time(task.getLong("deadline"))}", 13f)
            text(card, if (task.isNull("slot")) "No free slot before the deadline. Adjust your schedule."
                else "Suggested start: ${time(task.getLong("slot"))}", 14f)
            button(card, "Completed") {
                run(JSONObject().put("action", "complete_task").put("event", task.getString("event"))
                    .put("task", task.getString("id")).put("completed", true))
            }
        }
    }
    private fun findEvent(id: String): JSONObject? {
        val events = state.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) if (events.getJSONObject(i).getString("id") == id) return events.getJSONObject(i)
        return null
    }
    private fun chooseImport() {
        AlertDialog.Builder(this).setTitle("Import")
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
        AlertDialog.Builder(this).setTitle(if (BuildConfig.BACKEND_URL.isBlank()) "Demo extraction · sample results, no AI calls" else "Extract once, review before saving")
            .setItems(arrayOf("Choose screenshot", "Paste text")) { _, index ->
                if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                }, 100)
                else {
                    val input = EditText(this).apply { hint = "Paste the timetable or instruction"; minLines = 4 }
                    AlertDialog.Builder(this).setTitle("Paste text").setView(input)
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
                    val result = BackendClient(backendToken()).request("/ai/$kind/extract", input)
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
        AlertDialog.Builder(this).setTitle(if (result.getBoolean("mock")) "DEMO · sample results, not your image" else "Review extracted details")
            .setMessage(summary.toString()).setNegativeButton("Discard", null)
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
                val token = backendToken()
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
        AlertDialog.Builder(this).setTitle("Student memory").setView(input)
            .setPositiveButton("Search") { _, _ ->
                memoryRequest("/memory/search", JSONObject().put("query", input.text.toString())) { result ->
                    val memories = result.getJSONArray("memories")
                    AlertDialog.Builder(this).setTitle(if (result.optBoolean("mock")) "Demo keyword search" else "Relevant memories")
                        .setItems(Array(memories.length()) { memories.getJSONObject(it).getString("text") }) { _, index ->
                            val selected = memories.getJSONObject(index)
                            AlertDialog.Builder(this).setMessage(selected.getString("text")).setPositiveButton("Close", null)
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
                BackendClient(backendToken()).delete("/memory/$id")
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
    private fun deleteEventFromBackend(id: String) {
     if (BuildConfig.BACKEND_URL.isBlank()) return

     Thread {
        try {
            BackendClient(backendToken()).delete("/events/$id")
        } catch (error: Exception) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Deleted locally; cloud deletion pending",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
     }.start()
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
        AlertDialog.Builder(this).setTitle("Your timetable")
            .setItems(Array(events.length()) { events.getJSONObject(it).getString("title") }) { _, index -> edit(events.getJSONObject(index)) }.show()
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
        val dialog = AlertDialog.Builder(this).setTitle(if (original == null) "Add event" else "Edit event")
            .setView(ScrollView(this).apply { addView(form) }).setPositiveButton("Save", null).setNegativeButton("Cancel", null)
        if (original != null && onSave == null) dialog.setNeutralButton("Delete") { _, _ ->
            AlertDialog.Builder(this).setTitle("Delete this event and all its occurrences?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                   val id = original.getString("id")
                   if (run(JSONObject().put("action", "delete_event").put("id", id))) {
                    deleteEventFromBackend(id)}
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
                    syncEvents()
                    render(result.getJSONObject("view")); shown.dismiss()
                } catch (error: Exception) { Toast.makeText(this, error.message ?: "Check the entered values", Toast.LENGTH_LONG).show() }
            }
        }
        shown.show()
    }
}
