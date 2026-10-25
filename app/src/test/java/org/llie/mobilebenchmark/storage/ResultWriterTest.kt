package org.llie.mobilebenchmark.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.llie.mobilebenchmark.model.RgbImage
import org.llie.mobilebenchmark.runner.EnvSnapshot
import org.llie.mobilebenchmark.runner.ImageRunResult
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResultWriterTest {

    private val env = EnvSnapshot(
        batteryTempC = 30.5,
        batteryLevelPct = 88,
        charging = false,
        screenOn = true,
        thermal = "none",
        cpuFreqsMhz = listOf(1800, 1800, 1400),
    )

    private fun payload() = ResultWriter.resultJson(
        modelId = "zero-dce",
        artifactSha256 = "a".repeat(64),
        deviceModel = "Redmi 9T",
        androidRelease = "12",
        androidSdk = 31,
        buildFingerprint = "Redmi/9T:12/test",
        soc = "SD662",
        runtimeName = "onnxruntime",
        runtimeVersion = "1.19.2",
        backend = "cpu",
        precision = "float32",
        warmupCount = 5,
        measuredPasses = 10,
        protocolVersion = 2,
        loadTimeNs = 1000,
        results = listOf(
            ImageRunResult(
                imageId = "1.png",
                modelNs = listOf(1000),
                e2eNs = listOf(1010),
                output = RgbImage.zeros(2, 2),
                success = true,
                failureReason = "",
                prepareNs = listOf(500),
                runNs = listOf(400),
                unpackNs = listOf(100),
                startedAtNs = 42,
                env = env,
            )
        ),
        outputPaths = mapOf("1.png" to "outputs/1.png"),
        pssSamplesMb = listOf(100.0),
        thermalBefore = "none",
        thermalAfter = "none",
        envBefore = env,
        envAfter = env.copy(batteryTempC = 32.0),
    )

    @Test
    fun writesProtocolCountersAndPhaseTimings() {
        val json = payload()

        assertEquals(2, json.getInt("protocol_version"))
        assertEquals(10, json.getInt("measured_passes"))
        assertEquals(5, json.getInt("warmup_count"))

        val image = json.getJSONArray("images").getJSONObject(0)
        assertEquals(400L, image.getJSONArray("run_ns").getLong(0))
        assertEquals(500L, image.getJSONArray("prepare_ns").getLong(0))
        assertEquals(100L, image.getJSONArray("unpack_ns").getLong(0))
        assertEquals(42L, image.getLong("started_at_ns"))
        assertEquals(30.5, image.getDouble("battery_temp_c"), 0.0)
    }

    @Test
    fun writesTheDeviceStateOnBothEndsOfTheRun() {
        val envJson = payload().getJSONObject("environment")

        assertEquals(30.5, envJson.getJSONObject("before").getDouble("battery_temp_c"), 0.0)
        assertEquals(32.0, envJson.getJSONObject("after").getDouble("battery_temp_c"), 0.0)
        assertTrue(envJson.getJSONObject("before").getBoolean("screen_on"))
        assertEquals(3, envJson.getJSONObject("after").getJSONArray("cpu_freqs_mhz").length())
    }

    @Test
    fun omitsOptionalBlocksWhenTheDeviceStateIsUnknown() {
        val json = ResultWriter.resultJson(
            modelId = "zero-dce",
            artifactSha256 = "a".repeat(64),
            deviceModel = "Redmi 9T",
            androidRelease = "12",
            androidSdk = 31,
            buildFingerprint = "Redmi/9T:12/test",
            soc = "SD662",
            runtimeName = "onnxruntime",
            runtimeVersion = "1.19.2",
            backend = "cpu",
            precision = "float32",
            warmupCount = 5,
            measuredPasses = 10,
            protocolVersion = 2,
            loadTimeNs = 1000,
            results = listOf(
                ImageRunResult(
                    imageId = "1.png",
                    modelNs = listOf(1000),
                    e2eNs = listOf(1010),
                    output = RgbImage.zeros(2, 2),
                    success = true,
                    failureReason = "",
                )
            ),
            outputPaths = mapOf("1.png" to "outputs/1.png"),
            pssSamplesMb = listOf(100.0),
            thermalBefore = "none",
            thermalAfter = "none",
        )

        assertTrue(!json.has("environment"))
        val image = json.getJSONArray("images").getJSONObject(0)
        assertTrue(!image.has("run_ns"))
        assertTrue(!image.has("started_at_ns"))
    }
}
