package com.studentmemory.copilot

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

object NativeCore {
    init { System.loadLibrary("memory_jni") }
    external fun transact(state: ByteArray, command: ByteArray, config: ByteArray, templates: ByteArray): ByteArray
}

// Persistence and civil-time conversion are platform services; all decisions stay in C++.
class CoreStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "student-memory.json"))
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
    companion object { private val lock = Any() }
}
