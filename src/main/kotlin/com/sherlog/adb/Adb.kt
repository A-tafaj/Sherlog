package com.sherlog.adb

import java.util.prefs.Preferences

/** A device visible to adb. [label] is what the picker shows. */
data class AdbDevice(val serial: String, val model: String?) {
    val label: String get() = if (model.isNullOrBlank()) serial else "$model ($serial)"
}

/**
 * Everything Sherlog needs from adb, behind an interface so the live-capture
 * code can be tested with a fake that returns canned devices and byte streams —
 * no real device or adb binary required.
 */
interface AdbEnvironment {
    /** Devices currently in the `device` state (offline/unauthorized excluded). */
    fun devices(): List<AdbDevice>

    /** Starts `adb logcat -v threadtime` for [serial] (or the only device when null). */
    fun logcat(serial: String?): LogcatSession
}

/**
 * A real adb at [adbPath] (a bare `adb` resolves via PATH). Command execution
 * goes through [runCapture]/[ProcessBuilder]; parsing is split out into pure
 * functions so it can be tested directly.
 */
class RealAdb(private val adbPath: String) : AdbEnvironment {

    /** True if this adb path actually runs (`adb version` exits 0). */
    fun works(): Boolean = runCatching { runCapture(listOf(adbPath, "version")).first == 0 }.getOrDefault(false)

    override fun devices(): List<AdbDevice> {
        val (code, out) = runCapture(listOf(adbPath, "devices", "-l"))
        return if (code == 0) parseDevices(out) else emptyList()
    }

    override fun logcat(serial: String?): LogcatSession {
        val cmd = buildList {
            add(adbPath)
            if (serial != null) { add("-s"); add(serial) }
            add("logcat"); add("-v"); add("threadtime")
        }
        // Fold stderr into the stream so adb errors ("device offline") surface
        // in the view rather than vanishing.
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        return LogcatSession(process.inputStream, process)
    }

    private fun runCapture(cmd: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return process.exitValue() to out
    }

    companion object {
        /**
         * Parses `adb devices -l` output. Each device line looks like:
         * `SERIAL          device product:x model:Pixel_7 device:y transport_id:3`
         * Only devices in the `device` state are returned.
         */
        fun parseDevices(output: String): List<AdbDevice> =
            output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2 || parts[1] != "device") return@mapNotNull null
                    val serial = parts[0]
                    val model = parts.drop(2)
                        .firstOrNull { it.startsWith("model:") }
                        ?.substringAfter("model:")
                        ?.replace('_', ' ')
                    AdbDevice(serial, model)
                }
                .toList()
    }
}

/**
 * Locates a working adb: a user-set override path first, then a bare `adb`
 * from PATH. The override is remembered across runs via [Preferences] so a user
 * whose PATH lacks the SDK sets it once.
 */
object AdbLocator {
    private val prefs = Preferences.userRoot().node("com/sherlog")
    private const val KEY_ADB_PATH = "adbPath"

    var overridePath: String?
        get() = prefs.get(KEY_ADB_PATH, null)
        set(value) {
            if (value.isNullOrBlank()) prefs.remove(KEY_ADB_PATH) else prefs.put(KEY_ADB_PATH, value.trim())
        }

    /** A working adb, or null if none of the candidates run. */
    fun locate(): RealAdb? {
        for (candidate in listOfNotNull(overridePath?.takeIf { it.isNotBlank() }, "adb")) {
            val adb = RealAdb(candidate)
            if (adb.works()) return adb
        }
        return null
    }
}
