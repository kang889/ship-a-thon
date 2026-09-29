package com.studentmemory.copilot

/** The six paid capabilities defined in BUILD.md §23. */
enum class ProFeature(val title: String, val description: String, val icon: String) {
    AI_TIMETABLE_EXTRACTION("AI timetable import", "Import your timetable from a screenshot.", "image"),
    AI_INSTRUCTION_EXTRACTION("AI instruction extraction", "Turn lecturer messages into reminders.", "message"),
    ADAPTIVE_REMINDER_TIMING("Adaptive reminders", "Learns when reminders work best for you.", "clock"),
    ADVANCED_FORGET_PROFILE("Forget Profile insights", "Understand what you tend to forget.", "calculator"),
    SEMANTIC_STUDENT_MEMORY("Semantic Student Memory", "Remember and retrieve information naturally.", "memory"),
    RICHER_CONTEXT_INTEGRATIONS("Advanced context", "Smarter suggestions using richer context.", "sparkles")
}
