package org.llie.mobilebenchmark.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnvironmentRecorderTest {

    @Test
    fun parsesBatteryTemperatureTenthsOfACelsius() {
        assertEquals(30.5, EnvironmentRecorder.parseBatteryTempC(305), 0.0)
        assertEquals(41.0, EnvironmentRecorder.parseBatteryTempC(410), 0.0)
    }

    @Test
    fun parsesCpuFrequencyFromKhz() {
        assertEquals(1800, EnvironmentRecorder.parseCpuFreqMhz("1800000"))
        assertEquals(1800, EnvironmentRecorder.parseCpuFreqMhz("1800000\n"))
    }

    @Test
    fun rejectsAnUnreadableCpuFrequency() {
        assertNull(EnvironmentRecorder.parseCpuFreqMhz(""))
        assertNull(EnvironmentRecorder.parseCpuFreqMhz("not-a-number"))
    }

    @Test
    fun snapshotJsonCarriesEveryFieldTheSchemaRequires() {
        val json = requireNotNull(
            EnvSnapshot(
                batteryTempC = 30.5,
                batteryLevelPct = 88,
                charging = false,
                screenOn = true,
                thermal = "none",
                cpuFreqsMhz = listOf(1800, 1800, 1400),
            ).toJson()
        )

        assertEquals(30.5, json.getDouble("battery_temp_c"), 0.0)
        assertEquals(88, json.getInt("battery_level_pct"))
        assertEquals(false, json.getBoolean("charging"))
        assertTrue(json.getBoolean("screen_on"))
        assertEquals("none", json.getString("thermal"))
        assertEquals(3, json.getJSONArray("cpu_freqs_mhz").length())
    }

    @Test
    fun snapshotWithoutTemperatureIsNotSerialized() {
        val incomplete = EnvSnapshot(
            batteryTempC = null,
            batteryLevelPct = 88,
            charging = false,
            screenOn = true,
            thermal = "none",
            cpuFreqsMhz = emptyList(),
        )
        assertNull(incomplete.toJson())
    }
}
