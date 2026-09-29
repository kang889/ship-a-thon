package com.studentmemory.copilot

import android.Manifest
import android.app.Activity
import com.studentmemory.copilot.ui.PackBackDialog as AlertDialog
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.location.Geocoder
import java.util.Locale
import android.graphics.Color
import com.studentmemory.copilot.ui.PackBackTheme
import com.studentmemory.copilot.ui.PackBackComponents
import com.studentmemory.copilot.ui.Tone
import com.studentmemory.copilot.ui.PackBackDialog
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.studentmemory.copilot.services.BackendClient
import com.studentmemory.copilot.services.Account
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private val bringSuggestions = listOf("Laptop", "Notebook", "Calculator", "Graphic calculator",
        "Laptop charger", "iPad", "Pencil box", "iPad charger", "Earphones", "Shoes",
        "Student card", "Water bottle", "Phone charger")
    private lateinit var content: LinearLayout
    private lateinit var store: CoreStore
    private var state = JSONObject()
    private var currentView = JSONObject()
    private val ink get() = PackBackTheme(this).ink
    private val green get() = PackBackTheme(this).brand
    private var importKind = "timetable"
    private var importEventId = ""
    private var showWeek = false
    // Guards against racing/duplicate cloud hydrations (sign-in + resume firing together).
    private val hydrating = AtomicBoolean(false)
    // Identity the current CoreStore is scoped to: null = anonymous/signed-out, otherwise a UID.
    private var storeUserId: String? = null
    private var showProfile = false
    private var pendingProFeature: ProFeature? = null
    private val profilePrefs by lazy { getSharedPreferences("student_profile", Context.MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Account.initialize(this)
        // Select the local store for whoever is currently authenticated (anonymous before sign-in).
        storeUserId = Account.userId()
        store = CoreStore(this, storeUserId)
        Billing.configure(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    override fun onResume() {
        super.onResume()
        // Re-point the store at the current account before rendering, in case it changed.
        syncStoreToAccount()
        run(JSONObject().put("action", "view"))
        val lat = profilePrefs.getString("latitude", "")?.toDoubleOrNull()
        val lon = profilePrefs.getString("longitude", "")?.toDoubleOrNull()
        if (BuildConfig.BACKEND_URL.isNotBlank() && currentView.optBoolean("weatherHasEventToday") &&
            !currentView.optBoolean("weatherChecked") && lat != null && lon != null &&
            System.currentTimeMillis() - profilePrefs.getLong("last_weather_attempt", 0L) > 30 * 60 * 1000L) {
            loadRealWeather(lat, lon)
        }
        // Pro state comes from RevenueCat CustomerInfo. When signed out or Pro is inactive,
        // turn off the adaptive-timing Pro feature; otherwise refresh the UI. (from main)
        if (Account.userId() == null) {
            Billing.clearSession()
            if (state.optBoolean("adaptiveTimingEnabled", false))
                run(JSONObject().put("action", "adaptive_timing").put("enabled", false))
        } else Billing.checkPro(this) { active -> runOnUiThread {
            if (active == false && state.optBoolean("adaptiveTimingEnabled", false))
                run(JSONObject().put("action", "adaptive_timing").put("enabled", false))
            else render(currentView)
        } }
        // Pull cloud events for the signed-in user (no-op when unauthenticated/offline). (from deploy)
        hydrateFromCloud()
    }
    // Rebuild the CoreStore when the authenticated identity changes so each user reads and writes
    // only their own local file. Other users' files are never touched, so switching back restores
    // that account's own state. Returns true when the store was re-pointed. (from deploy)
    private fun syncStoreToAccount(): Boolean {
        val current = Account.userId()
        if (current == storeUserId) return false
        storeUserId = current
        store = CoreStore(this, current)
        return true
    }
    private fun run(command: JSONObject): Boolean {
        try {
            val result = store.execute(command)
            state = result.getJSONObject("state")
            val view = result.getJSONObject("view")
            currentView = view
            Reminders.schedule(this, view.getJSONArray("notifications"), storeUserId)
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
    private fun backendToken(): String {
      return if (BuildConfig.DEBUG && BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2")) {
        "local-development-only"
      } else if (Account.configured) {
        Account.token()
      } else {
        ""
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
    // Pull this Firebase user's cloud events and hydrate them into the local C++ state.
    // Additive and non-destructive: the C++ cloud_hydrate command dedupes by event id and
    // preserves any existing local event. Local state is only touched after a successful,
    // validated fetch, so network/auth/parse failures never disturb offline functionality.
    private fun hydrateFromCloud() {
        // Ensure the local store belongs to the currently authenticated account before any
        // cloud events are merged, so one user's cloud data can never land in another's store.
        if (syncStoreToAccount()) run(JSONObject().put("action", "view"))
        // Auth safety: only fetch when a real Firebase user is signed in with an obtainable token.
        if (BuildConfig.BACKEND_URL.isBlank() || !Account.configured || Account.userId() == null) return
        // In-flight guard: skip if a hydration is already running (sign-in + resume can overlap).
        if (!hydrating.compareAndSet(false, true)) return
        Thread {
            try {
                val token = Account.token() // throws if the token cannot be obtained → skip safely
                // GET /api/v1/events returns a top-level JSON array of this user's events.
                val events = BackendClient(token).requestArray("/events")
                // The merge, state update, scheduling and render run on the UI thread. Because
                // runOnUiThread is asynchronous, the in-flight guard is released INSIDE that posted
                // block (both success and failure) — never here — so it stays held until the UI
                // hydration has actually completed.
                runOnUiThread {
                    try {
                        // One C++ command performs the merge; C++ owns all state logic.
                        val hydrated = store.execute(
                            JSONObject().put("action", "cloud_hydrate").put("events", events)
                        )
                        state = hydrated.getJSONObject("state")
                        currentView = hydrated.getJSONObject("view")
                        // Reschedule exactly once for the updated state; ids are stable so repeated
                        // hydration does not create duplicate alarms/notifications.
                        Reminders.schedule(this, currentView.getJSONArray("notifications"), storeUserId)
                        render(currentView)
                    } catch (error: Exception) {
                        // A hydrate/merge failure must not disturb the existing local state.
                        // Diagnostic: surface and log the actual error (temporary).
                        android.util.Log.e("CloudSync", "Cloud hydrate/merge failed", error)
                        Toast.makeText(this, "Cloud sync failed: ${friendly(error, "Unknown cloud sync error")}", Toast.LENGTH_LONG).show()
                    } finally {
                        // Released only after the posted UI hydration has finished.
                        hydrating.set(false)
                    }
                }
            } catch (error: Exception) {
                // Network / auth / fetch failure: nothing was posted to the UI thread, so release
                // the guard here and keep all local events unchanged (offline-first).
                hydrating.set(false)
                // Diagnostic: surface and log the actual error (temporary).
                android.util.Log.e("CloudSync", "Cloud fetch failed", error)
                runOnUiThread {
                    Toast.makeText(this, "Cloud sync failed: ${friendly(error, "Unknown cloud sync error")}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
    private fun time(minute: Long) = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    // Never surface a null or blank failure message to the user; fall back to a readable line.
    private fun friendly(error: Throwable, fallback: String): String =
        error.message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    private fun render(view: JSONObject) {
        val events = view.getJSONArray("events")
        val returning = !showProfile && !showWeek && events.length() > 0 && events.getJSONObject(0).getString("phase") == "BRING BACK"
        val palette = PackBackTheme(this, returning)
        val ui = PackBackComponents(this, palette)
        val root = ui.Column().apply { setBackgroundColor(palette.background) }
        root.setOnApplyWindowInsetsListener { target, insets ->
            target.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        content = ui.Column().apply { setPadding(palette.dp(20),palette.dp(20),palette.dp(20),palette.dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; addView(content) }, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(ui.BottomNav({ showProfile = false; render(currentView) },
            { showProfile = false; manage() }, { edit(null) },
            { openProFeature(ProFeature.SEMANTIC_STUDENT_MEMORY) },
            { showProfile = true; render(currentView) }, if (showProfile) 4 else 0))
        setContentView(root)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.card
        window.decorView.systemUiVisibility = if (palette.dark || returning) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        if (showProfile) { renderProfile(content); return }
        // TODO(PDF p8/19): no display-name binding or timed weather forecast exists. Omit sample identity and forecast time.
        val almost = events.length() > 0 && events.getJSONObject(0).getString("phase") == "NEXT"
        if (!returning) {
            content.addView(ui.Label(if (almost) "Almost time." else "Good morning.",32f,true,weight=700))
            ui.Space(content,8)
            content.addView(ui.Label(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE · d MMMM")),13f,color=palette.muted))
        }
        val todayBring = view.optJSONArray("todayBring") ?: JSONArray()
        content.addView(ui.SectionHeader("Bring today", "${todayBring.length()} items"))
        if (todayBring.length() == 0) content.addView(ui.Label("Nothing to pack for today's events.",14f,color=palette.muted))
        for (i in 0 until todayBring.length()) {
            val item = todayBring.getJSONObject(i)
            val packed = item.getString("state") in setOf("PACKED","BROUGHT","IN_USE","NEEDS_TO_RETURN","SAFE")
            val weatherItem = item.optBoolean("weather")
            // The whole school day is one outing: a daily item is deduplicated across every class
            // that needs it. Explain "why" with the class count when more than one class requires it,
            // otherwise name the single class. Weather umbrella keeps its own day-level explanation.
            val count = item.optInt("count", 1)
            val reason = if (weatherItem) {
                "Rain likely before your last event" +
                    (if (view.optBoolean("weatherMock")) " · demo weather" else "")
            } else if (item.optBoolean("dayItem") && count > 1) {
                "Needed for $count classes"
            } else "${item.getString("eventTitle")} · ${time(item.getLong("eventStart"))}"
            val onTap: (() -> Unit)? = if (weatherItem) ({
                run(JSONObject().put("action", "umbrella_packed").put("packed", !packed))
            }) else {
                val actions = item.optJSONArray("actions") ?: JSONArray()
                if (actions.length() == 0) null else ({
                    val labels = Array(actions.length()) { actions.getString(it).replace('_',' ').lowercase().replaceFirstChar { ch -> ch.uppercase() } }
                    PackBackDialog.Builder(this).setTitle(item.getString("name")).setMessage(reason)
                        .setItems(labels) { _, index ->
                            // Marking the daily item transitions every same-day occurrence of that
                            // physical item together; C++ (transition_day) owns the propagation.
                            if (item.optBoolean("dayItem")) {
                                run(JSONObject().put("action", "transition_day").put("day", item.getLong("day"))
                                    .put("name", item.getString("name")).put("target", actions.getString(index)))
                            } else {
                                run(JSONObject().put("action", "transition").put("occurrence", item.getString("occurrence"))
                                    .put("item", item.getString("id")).put("target", actions.getString(index)))
                            }
                        }.show()
                })
            }
            content.addView(ui.ItemRow(item.getString("name"), if (packed) "Packed" else "To pack", reason,
                item.optString("priority") in setOf("HIGH","VERY HIGH"), packed, onTap))
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
            }
            if (event.getBoolean("overlap")) content.addView(ui.Pill("Overlaps another event",Tone.Warning))
            if (returning && back && items.length() == 0) content.addView(ui.Label("No items to bring back.",14f,color=palette.muted))
            if (returning && back) {
                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    val actions = item.getJSONArray("actions")
                    // Class-exit: when C++ marks the item resolvable (NEEDED/PACKED/BROUGHT/IN_USE/
                    // NEEDS_TO_RETURN), offer the two meaningful outcomes — "Got it" (SAFE) or
                    // "Forgot it" (FORGOTTEN) — resolved for this occurrence via the C++ return_item
                    // command, no walking the lifecycle by hand. Works even from NEEDED, so a user
                    // who never updated PackBack can still resolve it. Otherwise fall back to the
                    // ordinary per-occurrence transition menu. All rules stay in C++.
                    val updateItem: (() -> Unit)? = if (item.optBoolean("canReturn")) ({
                        PackBackDialog.Builder(this).setTitle(item.getString("name")).setMessage(item.getString("reason"))
                            .setItems(arrayOf("Got it", "Forgot it")) { _, index ->
                                run(JSONObject().put("action", "return_item").put("occurrence", event.getString("key"))
                                    .put("item", item.getString("id")).put("target", if (index == 0) "SAFE" else "FORGOTTEN"))
                            }.show()
                    }) else if (actions.length() > 0) ({
                        val labels = Array(actions.length()) { actions.getString(it).replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() } }
                        PackBackDialog.Builder(this).setTitle(item.getString("name")).setMessage(item.getString("reason")).setItems(labels) { _, index ->
                            run(JSONObject().put("action", "transition").put("occurrence", event.getString("key"))
                                .put("item", item.getString("id")).put("target", actions.getString(index)))
                        }.show()
                    }) else null
                    val highRisk = item.getString("priority") in setOf("HIGH","VERY HIGH")
                    val itemState = item.getString("state")
                    content.addView(ui.BringBackCard(item.getString("name"),item.getString("reason"),highRisk,itemState == "SAFE",updateItem))
                }
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
            button(content, "Edit event") { edit(findEvent(event.getString("id")), occurrenceStart = event.getLong("start")) }
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
        // These contextual shortcuts lead to the same Profile subscription routing.
        button(content, "+ Add a class or event") { edit(null) }
        button(content, "Manage timetable") { manage() }
        button(content, "Import timetable or instruction") { chooseImport() }
        button(content, "Student Memory Pro") { showProfile = true; render(currentView) }
        button(content, "Weather context") { weather() }
        button(content, "Student memory") { openProFeature(ProFeature.SEMANTIC_STUDENT_MEMORY) }
        if (Account.configured && Account.userId() == null) button(content, "Sign in / create account") { signIn() }
    }
    private fun openProFeature(feature: ProFeature) {
        pendingProFeature = feature
        if (Account.userId() == null) {
            signIn { pendingProFeature?.let(::openProFeature) }
            return
        }
        Billing.checkPro(this) { entitled -> runOnUiThread {
            if (pendingProFeature != feature || Account.userId() == null) return@runOnUiThread
            when (entitled) {
                true -> {
                    pendingProFeature = null
                    openUnlockedFeature(feature)
                    render(currentView)
                }
                false -> Billing.show(this, onUnlocked = {
                    val requested = pendingProFeature
                    if (requested != null && Account.userId() != null) {
                        pendingProFeature = null
                        openUnlockedFeature(requested)
                    }
                }, onChanged = { render(currentView) })
                null -> AlertDialog.Builder(this).setTitle("Could not verify Pro")
                    .setMessage("Check your connection and RevenueCat configuration, then retry.")
                    .setPositiveButton("Retry") { _, _ -> openProFeature(feature) }
                    .setNegativeButton("Cancel", null).show()
            }
        } }
    }

    private fun openUnlockedFeature(feature: ProFeature) {
        when (feature) {
            ProFeature.AI_TIMETABLE_EXTRACTION, ProFeature.AI_INSTRUCTION_EXTRACTION -> beginImport(feature)
            ProFeature.SEMANTIC_STUDENT_MEMORY -> memory()
            ProFeature.ADAPTIVE_REMINDER_TIMING -> adaptiveReminderSettings()
            ProFeature.ADVANCED_FORGET_PROFILE -> forgetInsights()
            ProFeature.RICHER_CONTEXT_INTEGRATIONS -> AlertDialog.Builder(this)
                .setTitle("Advanced context")
                .setMessage("Richer context integrations are being built. Basic weather and rain-to-umbrella reminders are available to everyone now.")
                .setPositiveButton("Weather context") { _, _ -> weather() }
                .setNegativeButton("Close", null).show()
        }
    }

    private fun adaptiveReminderSettings() {
        val enabled = state.optBoolean("adaptiveTimingEnabled", false)
        AlertDialog.Builder(this).setTitle("Adaptive reminders")
            .setMessage("When enabled, reminders use timing learned from your responses to previous class reminders. Until enough responses are recorded, the usual fixed reminder time applies.\n\nCurrently: ${if (enabled) "On" else "Off"}")
            .setPositiveButton(if (enabled) "Turn off" else "Turn on") { _, _ ->
                // Core keeps collecting basic response/forget history for Free users.
                run(JSONObject().put("action", "adaptive_timing").put("enabled", !enabled))
            }.setNegativeButton("Close", null).show()
    }

    private fun forgetInsights() {
        val profile = state.optJSONObject("profile") ?: JSONObject()
        val events = state.optJSONArray("events") ?: JSONArray()
        val results = mutableListOf<Pair<String, Int>>()
        var total = 0
        for (key in profile.keys()) {
            val count = profile.optJSONObject(key)?.optInt("forgotten", 0) ?: 0
            total += count
            if (count == 0) continue
            val ids = try { JSONArray(key) } catch (_: Exception) { null }
            var label = "Item"
            if (ids != null && ids.length() == 2) {
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    if (event.optString("id") != ids.optString(0)) continue
                    val items = event.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) if (items.getJSONObject(j).optString("id") == ids.optString(1))
                        label = "${items.getJSONObject(j).optString("name")} · ${event.optString("title")}"
                }
            }
            results.add(label to count)
        }
        val details = results.sortedByDescending { it.second }.take(8)
            .joinToString("\n") { "${it.first}: ${it.second} time(s)" }
        AlertDialog.Builder(this).setTitle("Forget Profile insights")
            .setMessage(if (total == 0) "No forgotten items recorded yet. Basic forget tracking stays active for everyone."
                else "Forgotten $total time(s) in total.\n\nMost often forgotten:\n$details")
            .setPositiveButton("Close", null).show()
    }

    private fun renderProfile(parent: LinearLayout) {
        val theme = PackBackTheme(this)
        val ui = PackBackComponents(this, theme)
        parent.addView(ui.Label("Profile", 32f, true, weight = 700))
        val userId = Account.userId()
        parent.addView(ui.SectionHeader("Account / User information"))
        val account = ui.Card()
        account.addView(ui.Label(Account.email() ?: "Sign in / Create account", 17f, weight = 700))
        account.addView(ui.Label(if (userId == null) "Sign in to access Student Memory Pro." else "Your student account", 13f, color = theme.secondary))
        if (userId == null) button(account, "Sign in / Create account") { signIn() }
        parent.addView(account)

        parent.addView(ui.SectionHeader("Student profile"))
        profileRow(parent, "pin", "Country / Region and University",
            listOf(profilePrefs.getString("country", ""), profilePrefs.getString("university", ""))
                .filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { "Set your study location" }) { editStudentProfile() }
        profileRow(parent, "sun", "Weather location",
            if (profilePrefs.contains("latitude"))
                "${profilePrefs.getString("latitude", "")}, ${profilePrefs.getString("longitude", "")}" else "Set a location for your forecast") {
            fetchRealWeather()
        }

        parent.addView(ui.SectionHeader("Preferences"))
        profileRow(parent, "bag", "Default bring items", "Preselect common items for new events") { editDefaultBring() }
        profileRow(parent, "clock", "Basic reminder settings", "Manage notification permission") {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        profileRow(parent, "umbrella", "Weather context", "Forecast and umbrella reminders · Free") { weather() }

        parent.addView(ui.SectionHeader("Student Memory Pro"))
        val summary = ui.Card().apply { background = theme.shape(theme.brandSoft) }
        summary.addView(ui.Label("Student Memory Pro", 23f, true, weight = 700))
        summary.addView(ui.Label("Free remembers your day. Pro learns how you forget.", 14f, color = theme.secondary))
        val status = when { userId == null -> "Sign in to unlock Pro"; Billing.isPro -> "Pro active"; else -> "Upgrade to Pro" }
        summary.addView(ui.Label(status, 15f, weight = 800, color = theme.brandText))
        button(summary, status) {
            if (userId == null) signIn { showProfilePaywall() }
            else showProfilePaywall()
        }
        parent.addView(summary)
        parent.addView(ui.SectionHeader("Pro features"))
        for (feature in ProFeature.values()) profileFeatureRow(parent, feature)

        parent.addView(ui.SectionHeader("Account / subscription"))
        profileRow(parent, "calendar", "Manage subscription", "View your subscription in Google Play") {
            if (Account.userId() == null) signIn { manageSubscription() } else manageSubscription()
        }
        profileRow(parent, "back", "Restore purchases", "Refresh your Pro entitlement") {
            if (Account.userId() == null) signIn { restoreSubscription() } else restoreSubscription()
        }
        if (userId != null) profileRow(parent, "profile", "Sign out", "Sign out of this account") {
            Account.signOut(); Billing.clearSession(); pendingProFeature = null
            if (state.optBoolean("adaptiveTimingEnabled", false))
                run(JSONObject().put("action", "adaptive_timing").put("enabled", false))
            else render(currentView)
        }
    }

    private fun profileRow(parent: LinearLayout, icon: String, title: String, subtitle: String, action: () -> Unit) {
        val ui = PackBackComponents(this)
        val theme = PackBackTheme(this)
        val card = ui.Card(16)
        val row = ui.Row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(ui.IconTile(icon, Tone.Brand))
        val labels = ui.Column().apply { setPadding(theme.dp(12), 0, theme.dp(8), 0) }
        labels.addView(ui.Label(title, 15f, weight = 800))
        labels.addView(ui.Label(subtitle, 12f, color = theme.secondary))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.Icon("chevron", theme.muted, 18))
        card.addView(row)
        card.setOnClickListener { action() }
        parent.addView(card)
    }

    private fun profileFeatureRow(parent: LinearLayout, feature: ProFeature) {
        val ui = PackBackComponents(this)
        val theme = PackBackTheme(this)
        val card = ui.Card(16)
        val row = ui.Row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(ui.IconTile(feature.icon, Tone.AI))
        val labels = ui.Column().apply { setPadding(theme.dp(12), 0, theme.dp(6), 0) }
        labels.addView(ui.Label(feature.title, 15f, weight = 800))
        labels.addView(ui.Label(feature.description, 12f, color = theme.secondary))
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.Pill(if (Billing.isPro && Account.userId() != null) "ACTIVE" else "PRO", Tone.AI))
        row.addView(ui.Icon("chevron", theme.muted, 16))
        card.addView(row)
        card.setOnClickListener { openProFeature(feature) }
        parent.addView(card)
    }

    private fun showProfilePaywall() {
        Billing.checkPro(this) { active -> runOnUiThread {
            when (active) {
                true -> { render(currentView); Toast.makeText(this, "Pro active", Toast.LENGTH_SHORT).show() }
                false -> Billing.show(this, onUnlocked = { render(currentView) }, onChanged = { render(currentView) })
                null -> AlertDialog.Builder(this).setTitle("Subscription unavailable")
                    .setMessage("Unable to check your subscription. Please try again.")
                    .setPositiveButton("Retry") { _, _ -> showProfilePaywall() }.setNegativeButton("Close", null).show()
            }
        } }
    }

    private fun restoreSubscription() {
        Billing.restore(this, onUnlocked = {
            render(currentView)
            Toast.makeText(this, "Pro restored", Toast.LENGTH_SHORT).show()
        }, onChanged = { render(currentView) })
    }

    private fun manageSubscription() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions")))
    }

    private fun editStudentProfile() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        val country = EditText(this).apply {
            hint = "Country / Region"; setText(profilePrefs.getString("country", "")); form.addView(this)
        }
        val university = EditText(this).apply {
            hint = "University"; setText(profilePrefs.getString("university", "")); form.addView(this)
        }
        AlertDialog.Builder(this).setTitle("Student profile").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val region = country.text.toString().trim()
                val school = university.text.toString().trim()
                profilePrefs.edit().putString("country", region).putString("university", school)
                    .remove("latitude").remove("longitude").apply()
                render(currentView)
                if (school.isNotBlank() && region.isNotBlank()) {
                    Thread {
                        try {
                            @Suppress("DEPRECATION")
                            val places = Geocoder(this, Locale.ENGLISH).getFromLocationName("$school, $region", 1)
                            val place = places?.firstOrNull()
                            runOnUiThread {
                                if (place != null && profilePrefs.getString("country", "") == region &&
                                    profilePrefs.getString("university", "") == school) {
                                    profilePrefs.edit().putString("latitude", place.latitude.toString())
                                        .putString("longitude", place.longitude.toString()).apply()
                                    render(currentView)
                                    loadRealWeather(place.latitude, place.longitude)
                                } else if (place == null) Toast.makeText(this,
                                    "Location not found. Enter coordinates under Weather location.", Toast.LENGTH_LONG).show()
                            }
                        } catch (_: Exception) { runOnUiThread { Toast.makeText(this,
                            "Location lookup unavailable. Enter coordinates under Weather location.", Toast.LENGTH_LONG).show() } }
                    }.start()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editDefaultBring() {
        val selected = (profilePrefs.getStringSet("default_bring", emptySet()) ?: emptySet()).toMutableSet()
        val theme = PackBackTheme(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 12, 28, 12) }
        for (pair in bringSuggestions.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in pair) row.addView(CheckBox(this).apply {
                text = item; buttonDrawable = null; gravity = android.view.Gravity.CENTER
                isChecked = item in selected
                fun restyle() { background = theme.ripple(if (isChecked) theme.brandSoft else theme.card, 14,
                    if (isChecked) theme.brand else theme.line) }
                restyle()
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(item) else selected.remove(item)
                    restyle()
                }
            }, LinearLayout.LayoutParams(0, theme.dp(58), 1f).apply {
                rightMargin = theme.dp(8); bottomMargin = theme.dp(8)
            })
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            form.addView(row)
        }
        AlertDialog.Builder(this).setTitle("Default bring items")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Save") { _, _ -> profilePrefs.edit().putStringSet("default_bring", selected).apply() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun findEvent(id: String): JSONObject? {
        val events = state.optJSONArray("events") ?: return null
        for (i in 0 until events.length()) if (events.getJSONObject(i).getString("id") == id) return events.getJSONObject(i)
        return null
    }
    private fun chooseImport() {
        AlertDialog.Builder(this).presentation(PackBackDialog.Layout.Import).setTitle("Import")
            .setItems(arrayOf("Timetable screenshot", "Lecturer instruction")) { _, index ->
                openProFeature(if (index == 0) ProFeature.AI_TIMETABLE_EXTRACTION
                    else ProFeature.AI_INSTRUCTION_EXTRACTION)
            }.show()
    }
    private fun beginImport(feature: ProFeature) {
        importKind = if (feature == ProFeature.AI_TIMETABLE_EXTRACTION) "timetable" else "instruction"
        if (importKind == "timetable") { chooseInput(); return }
        val events = state.optJSONArray("events") ?: JSONArray()
        if (events.length() == 0) {
            Toast.makeText(this, "Add a class first", Toast.LENGTH_LONG).show(); return
        }
        AlertDialog.Builder(this).setTitle("Which class?")
            .setItems(Array(events.length()) { events.getJSONObject(it).getString("title") }) { _, index ->
                importEventId = events.getJSONObject(index).getString("id"); chooseInput()
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
        } catch (error: Exception) {
            Toast.makeText(this, friendly(error, "Couldn't read that image. Try another screenshot."), Toast.LENGTH_LONG).show()
        }
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
                    .setMessage(friendly(error, "We couldn't read this automatically. You can still add it by hand."))
                    .setPositiveButton("OK", null).show() }
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
                        edit(proposed.getJSONObject(index), onSave = { updated ->
                            proposed.put(index, updated); preview(result)
                        })
                    }.show()
            }
            .setPositiveButton("Confirm and save") { _, _ ->
                if (!run(JSONObject().put("action", "confirm_import").put("confirmed", true).put("events", proposed))) return@setPositiveButton
                for (i in 0 until proposed.length()) {
                    val event = proposed.getJSONObject(i)
                    val eventItems = event.optJSONArray("items") ?: JSONArray()
                    saveAddOnMemories(event, (0 until eventItems.length()).map { eventItems.getJSONObject(it).getString("name") })
                }
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
                    .setMessage(friendly(error, "We couldn't reach the server. Your offline data still works."))
                    .setPositiveButton("OK", null).show() }
            }
        }.start()
    }
    private fun weather() {
        AlertDialog.Builder(this).setTitle("Weather context")
            .setItems(arrayOf("Test demo forecast", "Get real forecast", "Clear weather")) { _, choice ->
                when (choice) {
                    0 -> demoWeather()
                    1 -> if (BuildConfig.BACKEND_URL.isBlank()) {
                        Toast.makeText(this, "Configure a backend URL to fetch real weather", Toast.LENGTH_LONG).show()
                    } else fetchRealWeather()
                    2 -> run(JSONObject().put("action", "weather_clear"))
                }
            }.show()
    }

    private fun weatherSummary(): String {
        val view = currentView
        val last = view.optLong("weatherLastEventStart")
        val event = if (view.optBoolean("weatherHasEventToday"))
            "${view.optString("weatherLastEventTitle")} · ${time(last)}" else "None"
        val window = if (view.optLong("weatherWindowEnd") > view.optLong("weatherWindowStart"))
            "${time(view.getLong("weatherWindowStart"))} → ${time(view.getLong("weatherWindowEnd"))}"
            else "No upcoming last event"
        val probability = view.optInt("weatherRainProbability", -1)
        val highest = if (probability >= 0) "$probability%" else "Unavailable"
        return "Last event today: $event\nCheck window: $window\nForecast covers window: ${if (view.optBoolean("weatherCovered")) "Yes" else "No"}\nHighest rain probability: $highest\nBring umbrella: ${if (view.optBoolean("umbrella")) "YES" else "NO"}"
    }

    private fun demoWeather() {
        // Fill the entire remaining day with dry hourly values, then override one hour.
        // The resulting forecast uses exactly the same C++ rule as a server forecast.
        val now = LocalDateTime.now()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        form.addView(TextView(this).apply { text = weatherSummary() })
        val hour = EditText(this).apply {
            hint = "Forecast hour · HH:00 (today)"
            setText(now.withMinute(0).format(DateTimeFormatter.ofPattern("HH:mm")))
            form.addView(this)
        }
        val chance = EditText(this).apply { hint = "Rain probability · 0–100"; setText("75"); inputType = 2; form.addView(this) }
        AlertDialog.Builder(this).setTitle("Demo weather context").setView(form)
            .setPositiveButton("Apply weather") { _, _ ->
                try {
                    val selected = LocalDateTime.of(now.toLocalDate(), java.time.LocalTime.parse(hour.text.toString().trim()))
                    require(selected.minute == 0) { "Use an exact hour, such as 16:00." }
                    val probability = chance.text.toString().trim().toInt()
                    require(probability in 0..100) { "Probability must be 0–100%." }
                    val points = JSONArray()
                    var cursor = now.withMinute(0).withSecond(0).withNano(0)
                    while (cursor.toLocalDate() == now.toLocalDate()) {
                        val minute = cursor.toEpochSecond(ZoneOffset.UTC) / 60
                        points.put(JSONObject().put("time", minute)
                            .put("rain_probability", if (cursor == selected) probability else 0))
                        cursor = cursor.plusHours(1)
                    }
                    if (run(JSONObject().put("action", "weather").put("mock", true).put("hourly", points))) {
                        AlertDialog.Builder(this).setTitle("Weather decision")
                            .setMessage(weatherSummary()).setPositiveButton("OK", null).show()
                    }
                } catch (error: Exception) {
                    AlertDialog.Builder(this).setTitle("Invalid demo forecast")
                        .setMessage(error.message).setPositiveButton("OK", null).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun fetchRealWeather() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 16, 28, 16)
        }

        val latitude = EditText(this).apply {
            hint = "Latitude"
            setText(profilePrefs.getString("latitude", ""))
            form.addView(this)
        }

        val longitude = EditText(this).apply {
            hint = "Longitude"
            setText(profilePrefs.getString("longitude", ""))
            form.addView(this)
        }

        AlertDialog.Builder(this)
            .setTitle("Weather location (no background tracking)")
            .setView(form)
            .setPositiveButton("Get forecast") { _, _ ->
                val lat =
                    latitude.text.toString().toDoubleOrNull()

                val lon =
                    longitude.text.toString().toDoubleOrNull()

                if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                    Toast.makeText(
                        this,
                        "Enter valid coordinates",
                        Toast.LENGTH_LONG
                    ).show()

                    return@setPositiveButton
                }

                loadRealWeather(lat, lon)
            }
            .setNegativeButton(
                "Cancel",
                null
            )
            .show()
    }
    private fun loadRealWeather(lat: Double, lon: Double) {
        profilePrefs.edit().putString("latitude", lat.toString()).putString("longitude", lon.toString()).apply()
        if (BuildConfig.BACKEND_URL.isBlank()) {
            Toast.makeText(this, "Configure a backend URL to fetch real weather", Toast.LENGTH_LONG).show()
            return
        }
        profilePrefs.edit().putLong("last_weather_attempt", System.currentTimeMillis()).apply()
        // A failed refresh must not leave an earlier forecast active.
        run(JSONObject().put("action", "weather_clear"))
        backend(
            "/weather?latitude=$lat&longitude=$lon"
        ) { result ->

            if (!result.optBoolean("available")) {
                run(JSONObject().put("action", "weather_clear"))
                Toast.makeText(
                    this,
                    "Forecast unavailable. Your checklist still works.",
                    Toast.LENGTH_LONG
                ).show()

                return@backend
            }

            val hourly =
                result.optJSONArray("hourly")
                    ?: JSONArray()

            val hourlyForCore =
                JSONArray()

            for (i in 0 until hourly.length()) {
                val point =
                    hourly.getJSONObject(i)

                val minute =
                    LocalDateTime.ofInstant(
                        Instant.parse(point.getString("time")),
                        ZoneId.systemDefault()
                    ).toEpochSecond(ZoneOffset.UTC) / 60

                hourlyForCore.put(
                    JSONObject()
                        .put(
                            "time",
                            minute
                        )
                        .put(
                            "rain_probability",
                            point.getInt(
                                "rain_probability"
                            )
                        )
                )
            }

            val updated = run(
                JSONObject()
                    .put(
                        "action",
                        "weather"
                    )
                    .put(
                        "hourly",
                        hourlyForCore
                    )
                    .put(
                        "mock",
                        result.getBoolean(
                            "mock"
                        )
                    )
            )

            if (updated) {
                Toast.makeText(
                    this,
                    "Forecast updated · ${hourly.length()} hourly points loaded",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
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
                BackendClient(backendToken()).delete("/memory/$id")
                runOnUiThread { Toast.makeText(this, "Memory deleted", Toast.LENGTH_SHORT).show() }
            } catch (error: Exception) { runOnUiThread { Toast.makeText(this, friendly(error, "Couldn't delete that memory. Please try again."), Toast.LENGTH_LONG).show() } }
        }.start()
    }
    private fun memoryRequest(path: String, body: JSONObject, done: (JSONObject) -> Unit) {
        if (BuildConfig.BACKEND_URL.isNotBlank()) { backend(path, body, done); return }
        try {
            val command = JSONObject(body.toString()).put("action", if (path.endsWith("/search")) "memory_search" else "memory_save")
            val result = store.execute(command)
            state = result.getJSONObject("state")
            done(result)
        } catch (error: Exception) { Toast.makeText(this, friendly(error, "Couldn't complete that. Please try again."), Toast.LENGTH_LONG).show() }
    }
    private fun saveAddOnMemories(event: JSONObject, selected: List<String>) {
        val items = event.optJSONArray("items") ?: return
        val memories = mutableListOf<JSONObject>()
        for (name in selected) {
            if (bringSuggestions.any { it.equals(name, ignoreCase = true) }) continue
            val item = (0 until items.length()).map { items.getJSONObject(it) }
                .firstOrNull { it.getString("name").equals(name, ignoreCase = true) } ?: continue
            val stableId = "bring_" + UUID.nameUUIDFromBytes(
                "${event.getString("id")}:${item.getString("id")}".toByteArray(Charsets.UTF_8)
            ).toString().replace("-", "")
            memories.add(JSONObject().put("id", stableId)
                .put("text", "Bring $name for ${event.getString("title")}")
                .put("event_id", event.getString("id"))
                .put("course", event.optString("course"))
                .put("memory_type", "bring_item")
                .put("source", "event_addon"))
        }
        if (memories.isEmpty()) return
        if (BuildConfig.BACKEND_URL.isBlank()) {
            for (memory in memories) memoryRequest("/memory", memory) {}
        } else Thread {
            try {
                val client = BackendClient(backendToken())
                for (memory in memories) client.request("/memory", memory)
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "Event saved; add-on memory sync pending: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
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
    private fun signIn(afterSuccess: (() -> Unit)? = null) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val email = EditText(this).apply { hint = "Email"; inputType = 33; form.addView(this) }
        val password = EditText(this).apply { hint = "Password"; inputType = 129; form.addView(this) }
        fun authenticate(create: Boolean) {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            Thread {
                try {
                    Account.signIn(emailText, passwordText, create)
                    runOnUiThread {
                        Toast.makeText(this, "Signed in", Toast.LENGTH_SHORT).show()
                        // Reset any prior RevenueCat session for the previous account (from main).
                        Billing.clearSession()
                        // Re-point the store to this account and pull their cloud events; this also
                        // re-renders after switching/hydrating (from deploy).
                        hydrateFromCloud()
                        afterSuccess?.invoke()
                    }
                } catch (error: Exception) {
                    runOnUiThread { Toast.makeText(this, friendly(error, "Sign-in failed. Check your details and connection."), Toast.LENGTH_LONG).show() }
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
    private fun edit(original: JSONObject?, onSave: ((JSONObject) -> Unit)? = null, occurrenceStart: Long? = null) {
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
        val editingDay = occurrenceStart?.div(1440) ?: date?.toLocalDate()?.toEpochDay() ?: LocalDate.now().toEpochDay()
        val activeItems = (0 until oldItems.length()).map { oldItems.getJSONObject(it) }
            .filter { it.optLong("onlyDay", -1) in listOf(-1L, editingDay) }
        text(form, "Common things to bring", 16f)
        val chipTheme = PackBackTheme(this)
        fun styleChip(chip: CheckBox) {
            chip.background = chipTheme.ripple(
                if (chip.isChecked) chipTheme.brandSoft else chipTheme.card, 14,
                if (chip.isChecked) chipTheme.brand else chipTheme.line
            )
        }
        fun chip(name: String, checked: Boolean): CheckBox = CheckBox(this).apply {
            text = name
            buttonDrawable = null
            gravity = android.view.Gravity.CENTER
            setPadding(chipTheme.dp(8), chipTheme.dp(6), chipTheme.dp(8), chipTheme.dp(6))
            isChecked = checked
            styleChip(this)
        }
        fun addChipRows(container: LinearLayout, chips: List<CheckBox>) {
            for (pair in chips.chunked(2)) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (option in pair) row.addView(option,
                    LinearLayout.LayoutParams(0, chipTheme.dp(58), 1f).apply {
                        rightMargin = chipTheme.dp(8)
                        bottomMargin = chipTheme.dp(8)
                    })
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                container.addView(row)
            }
        }
        val commonChecks = bringSuggestions.associateWith { suggestion ->
            chip(suggestion, if (original == null)
                suggestion in (profilePrefs.getStringSet("default_bring", emptySet()) ?: emptySet())
                else activeItems.any { it.getString("name").equals(suggestion, ignoreCase = true) })
        }
        val commonGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; form.addView(this) }
        addChipRows(commonGrid, commonChecks.values.toList())
        val addOns = field("Add-ons · one personal item per line",
            activeItems.filter { item -> bringSuggestions.none { it.equals(item.getString("name"), ignoreCase = true) } }
                .joinToString("\n") { it.getString("name") })
        addOns.minLines = 2
        val onceSelected = activeItems.filter { it.optLong("onlyDay", -1) == editingDay }
            .map { it.getString("name").lowercase() }.toMutableSet()
        val once = CheckBox(this).apply {
            text = "Remind once only"
            isChecked = onceSelected.isNotEmpty()
            form.addView(this)
        }
        val onceChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; form.addView(this) }
        fun selectedNames(): List<String> {
            val names = linkedMapOf<String, String>()
            for ((name, check) in commonChecks) if (check.isChecked) names[name.lowercase()] = name
            for (name in addOns.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() })
                if (!names.containsKey(name.lowercase())) names[name.lowercase()] = name
            return names.values.toList()
        }
        fun refreshOnceChoices() {
            onceChoices.removeAllViews()
            if (!once.isChecked) return
            text(onceChoices, "Choose which selected items apply only to this occurrence", 13f)
            val options = selectedNames().map { name ->
                val key = name.lowercase()
                chip(name, key in onceSelected).apply {
                    setOnCheckedChangeListener { _, checked ->
                        styleChip(this)
                        if (checked) onceSelected.add(key) else onceSelected.remove(key)
                    }
                }
            }
            addChipRows(onceChoices, options)
        }
        once.setOnCheckedChangeListener { _, _ -> refreshOnceChoices() }
        for (check in commonChecks.values) check.setOnCheckedChangeListener { _, _ ->
            styleChip(check)
            refreshOnceChoices()
        }
        addOns.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshOnceChoices() }
            override fun afterTextChanged(s: Editable?) {}
        })
        refreshOnceChoices()
        val oldTasks = original?.optJSONArray("tasks") ?: JSONArray()
        val prep = field("Add preparation task (one-time, due before this event)", "")
        val duration = field("Preparation minutes", "60")
        val dialog = PackBackDialog.Builder(this).presentation(PackBackDialog.Layout.Event).setTitle(if (original == null) "Add event" else "Edit event")
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
                    val oneTimeDay = if (occurrenceStart != null && date != null &&
                        LocalDate.parse(day.text.toString()) == date.toLocalDate()) editingDay
                        else LocalDate.parse(day.text.toString()).toEpochDay()
                    val selected = selectedNames()
                    require(selected.size <= 50) { "Select no more than 50 items for one event." }
                    require(selected.all { it.length <= 80 }) { "Keep item names within 80 characters." }
                    for (name in selected) {
                        var existing: JSONObject? = null
                        for (item in activeItems) if (item.getString("name").equals(name, ignoreCase = true)) existing = item
                        val copy = existing?.let { JSONObject(it.toString()) }
                            ?: JSONObject().put("id", UUID.randomUUID().toString()).put("importance", 0.8)
                        copy.put("name", name).put("onlyDay", if (once.isChecked && name.lowercase() in onceSelected) oneTimeDay else -1)
                        itemArray.put(copy)
                    }
                    // Editing one occurrence must not erase items scoped to other dates.
                    for (i in 0 until oldItems.length()) {
                        val item = oldItems.getJSONObject(i)
                        if (item.optLong("onlyDay", -1) >= 0 && item.optLong("onlyDay") != editingDay)
                            itemArray.put(item)
                    }
                    event.put("items", itemArray)
                    val taskArray = JSONArray(oldTasks.toString())
                    if (prep.text.isNotBlank()) taskArray.put(JSONObject().put("id", UUID.randomUUID().toString())
                        .put("title", prep.text.toString()).put("duration", duration.text.toString().toInt()).put("deadline", startMinute))
                    event.put("tasks", taskArray)
                    if (onSave != null) { onSave(event); shown.dismiss(); return@setOnClickListener }
                    val result = store.execute(JSONObject().put("action", "save_event").put("event", event))
                    state = result.getJSONObject("state")
                    currentView = result.getJSONObject("view")
                    Reminders.schedule(this, currentView.getJSONArray("notifications"), storeUserId)
                    syncEvents()
                    saveAddOnMemories(event, selected)
                    render(currentView); shown.dismiss()
                } catch (error: Exception) { Toast.makeText(this, error.message ?: "Check the entered values", Toast.LENGTH_LONG).show() }
            }
        }
        shown.show()
    }
}
