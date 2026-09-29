package com.studentmemory.copilot

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

object NativeCore {
    init { System.loadLibrary("memory_jni") }
    external fun transact(state: ByteArray, command: ByteArray, config: ByteArray, templates: ByteArray): ByteArray
}

// Single source of the privacy-safe, per-identity namespace used by both local storage and
// reminder/alarm persistence. A signed-out session has its own stable "anonymous" namespace;
// each authenticated user maps to a distinct hash of their Firebase UID. The raw UID is never
// used in filenames, SharedPreferences keys, PendingIntent URIs, notifications or logs.
object AccountScope {
    fun namespace(userId: String?): String {
        if (userId.isNullOrBlank()) return "anon"
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

// Persistence and civil-time conversion are platform services; all decisions stay in C++.
// Local state is stored per identity so switching Firebase accounts on one device never mixes
// users' events. Each user keeps their own file; a signed-out session uses the anonymous store.
// Other users' files are left intact (never deleted) so signing back in restores that state.
class CoreStore(private val context: Context, userId: String? = null) {
    private val file = AtomicFile(File(context.filesDir, storeFileName(userId)))
    fun execute(command: JSONObject): JSONObject = synchronized(lock) {
        command.put("now", LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) / 60)
        val saved = if (file.baseFile.exists()) file.readFully() else "{}".toByteArray()
        val result = JSONObject(String(NativeCore.transact(saved, command.toString().toByteArray(),
            context.assets.open("reminder_weights.json").use { it.readBytes() },
            context.assets.open("reminder_templates.json").use { it.readBytes() }), Charsets.UTF_8))
        check(result.getBoolean("ok")) { result.optString("error", "Unable to save your changes.") }
        val stream = file.startWrite()
        try {
            stream.write(result.getJSONObject("state").toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
        result
    }
    companion object {
        private val lock = Any()
        // Anonymous/pre-login state keeps the original filename (preserves existing local data).
        // An authenticated user's file is keyed by the account namespace hash, so the raw UID is
        // never written to a filename that could surface in logs or device backups.
        fun storeFileName(userId: String?): String {
            if (userId.isNullOrBlank()) return "student-memory.json"
            return "student-memory-${AccountScope.namespace(userId)}.json"
        }
    }
}
