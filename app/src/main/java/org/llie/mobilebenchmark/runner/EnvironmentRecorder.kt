package org.llie.mobilebenchmark.runner

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One reading of the conditions a latency number is only valid under:
 * battery temperature, charge state, screen state, thermal status, and the
 * current frequency of every CPU core. A run whose temperature climbs is not
 * measuring the same machine as its first image.
 */
data class EnvSnapshot(
    val batteryTempC: Double?,
    val batteryLevelPct: Int?,
    val charging: Boolean?,
    val screenOn: Boolean,
    val thermal: String,
    val cpuFreqsMhz: List<Int>,
) {
    /**
     * Schema-shaped object, or null when the device state is incomplete.
     * A partial object would fail the run-result schema, so it is omitted.
     */
    fun toJson(): JSONObject? {
        val temp = batteryTempC ?: return null
        val level = batteryLevelPct ?: return null
        val plugged = charging ?: return null
        return JSONObject()
            .put("battery_temp_c", temp)
            .put("battery_level_pct", level)
            .put("charging", plugged)
            .put("screen_on", screenOn)
            .put("thermal", thermal)
            .put("cpu_freqs_mhz", JSONArray(cpuFreqsMhz))
    }
}

object EnvironmentRecorder {

    /** Keeps the CPU awake for the whole batch; released by the caller. */
    fun holdWakeLock(context: Context): PowerManager.WakeLock {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "llie-bench:measure").apply {
            setReferenceCounted(false)
            acquire(TWO_HOURS_MS)
        }
    }

    fun snapshot(context: Context): EnvSnapshot {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: Int.MIN_VALUE
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        // The sticky battery broadcast can lag by tens of seconds; on most
        // devices the power-supply sysfs node is refreshed continuously, so
        // prefer it and fall back to the broadcast.
        val sysfsTemp = sysfsBatteryTempC()
        return EnvSnapshot(
            batteryTempC = sysfsTemp
                ?: if (rawTemp == Int.MIN_VALUE) null else parseBatteryTempC(rawTemp),
            batteryLevelPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = if (status == -1) {
                null
            } else {
                status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL ||
                    plugged != 0
            },
            screenOn = power?.isInteractive ?: true,
            thermal = thermalStatus(context),
            cpuFreqsMhz = cpuFreqsMhz(),
        )
    }

    /** BatteryManager reports temperature in tenths of a degree Celsius. */
    fun parseBatteryTempC(rawTenths: Int): Double = rawTenths / 10.0

    /** cpufreq exposes kHz; the report uses MHz. */
    fun parseCpuFreqMhz(rawKhz: String): Int? =
        rawKhz.trim().toLongOrNull()?.let { (it / 1000).toInt() }

    /**
     * Fresh battery temperature from power_supply sysfs (tenths of °C).
     * Missing or implausible values return null so the caller falls back to
     * the battery broadcast.
     */
    fun sysfsBatteryTempC(): Double? {
        for (path in BATTERY_TEMP_PATHS) {
            val file = File(path)
            if (!file.isFile) continue
            val raw = runCatching { file.readText().trim() }.getOrNull() ?: continue
            parseTenthsTempC(raw)?.let { return it }
        }
        return null
    }

    /** power_supply reports tenths of a degree; reject implausible readings. */
    fun parseTenthsTempC(raw: String): Double? {
        val tenths = raw.trim().toIntOrNull() ?: return null
        return if (tenths in 0..1_200) tenths / 10.0 else null
    }

    fun cpuFreqsMhz(): List<Int> {
        val cpuDirs = File("/sys/devices/system/cpu").listFiles { file ->
            file.isDirectory && CPU_DIR.matches(file.name)
        } ?: return emptyList()
        return cpuDirs
            .sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
            .mapNotNull { dir ->
                val current = File(dir, "cpufreq/scaling_cur_freq")
                if (!current.isFile) return@mapNotNull null
                runCatching { parseCpuFreqMhz(current.readText()) }.getOrNull()
            }
    }

    fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unavailable"
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "unavailable"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "unknown"
        }
    }

    private val CPU_DIR = Regex("cpu\\d+")
    private val BATTERY_TEMP_PATHS = listOf(
        "/sys/class/power_supply/battery/temp",
        "/sys/class/power_supply/bms/temp",
    )
    private const val TWO_HOURS_MS = 2L * 60L * 60L * 1000L
}
