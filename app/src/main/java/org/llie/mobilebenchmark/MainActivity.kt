package org.llie.mobilebenchmark

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import org.llie.mobilebenchmark.net.BundleArchive
import org.llie.mobilebenchmark.net.BundleDownloader
import org.llie.mobilebenchmark.net.DeviceIdentity
import org.llie.mobilebenchmark.net.ReceiverUploader
import org.llie.mobilebenchmark.net.RunUploads
import org.llie.mobilebenchmark.net.UploadLedger
import org.llie.mobilebenchmark.net.UploadWorker
import org.llie.mobilebenchmark.runner.BenchmarkExecutor
import org.llie.mobilebenchmark.runner.EnvironmentRecorder
import org.llie.mobilebenchmark.runner.RunControl
import org.llie.mobilebenchmark.runner.RunMonitor
import org.llie.mobilebenchmark.runner.EnvSnapshot
import org.llie.mobilebenchmark.runner.TestAllRunner
import org.llie.mobilebenchmark.runner.TestAllState
import org.llie.mobilebenchmark.runner.etaMs
import org.llie.mobilebenchmark.runner.formatDurationMs
import org.llie.mobilebenchmark.runner.formatFreqsMhz
import org.llie.mobilebenchmark.runner.progressPercent
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Entry point and run host.
 *
 * - Launcher (no action): lists staged models/runs, offers "Test all".
 * - Action ACTION_RUN: one on-device batch run on a worker thread (host/ADB
 *   driven). Both paths share the same live monitor and upload pipeline.
 * - "Test all" runs every staged model heavy->light with a cool-down wait
 *   between models, and uploads each finished run to the configured receiver.
 * - "Stop" latches RunControl so the running model closes as `success=false`
 *   with failure_reason "stopped by user", then the queue halts.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var phaseLabel: TextView
    private lateinit var modelView: TextView
    private lateinit var tempBadge: TextView
    private lateinit var counterView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var detailView: TextView
    private lateinit var elapsedView: TextView
    private val coreNames = ArrayList<TextView>()
    private val coreMhz = ArrayList<TextView>()
    private val coreFills = ArrayList<View>()
    private val coreSpacers = ArrayList<View>()
    private lateinit var lastPassValue: TextView
    private lateinit var medianValue: TextView
    private lateinit var etaValue: TextView
    private lateinit var pssValue: TextView
    private lateinit var thermalValue: TextView
    private lateinit var testAllButton: Button
    private lateinit var stopButton: Button
    private lateinit var deviceLabelInput: EditText
    private lateinit var uploadStatus: TextView
    private lateinit var modelStatusView: TextView
    private lateinit var artifactsView: TextView
    private lateinit var artifactsCard: LinearLayout

    private lateinit var prefs: SharedPreferences
    private lateinit var sampler: ScheduledExecutorService
    @Volatile private var envCache: EnvSnapshot? = null
    @Volatile private var modelsCacheText: String = ""
    private var artifactsCacheText: String = ""
    private var lastElapsedMs = 0L
    private var lastThermal = "-"

    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (ticking) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen must stay up for the whole batch: an app that leaves the
        // top-app cpuset measures a slower, different machine.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        setContentView(buildLayout())
        artifactsCacheText = describeArtifacts()
        // All disk/battery reads happen on this thread; the UI only renders
        // cached values, so the 1 Hz ticker never touches storage.
        sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({ sampleEnvironment() }, 0, 1, TimeUnit.SECONDS)
        refresh()
        // Resume anything a previous session finished but never managed
        // to upload (interrupted batch, killed process, dropped network).
        uploadMissingRuns("on launch")
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // The monitor is live while the screen is open: it re-attaches to a
        // running queue after a configuration change, and between runs it
        // keeps showing the fresh battery temperature so cool-down is visible.
        startTicking()
        refresh()
    }

    override fun onStop() {
        ticking = false
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        sampler.shutdownNow()
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, WRAP))

        // ---- run card: phase, model, temperature, progress
        val runCard = card()
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        phaseLabel = label("IDLE", 10f, MUTED).apply { letterSpacing = 0.10f }
        tempBadge = TextView(this).apply {
            text = "-- °C"
            textSize = 13f
            setTextColor(GREEN_FG)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill(GREEN_BG)
        }
        headerRow.addView(phaseLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        headerRow.addView(tempBadge)
        modelView = TextView(this).apply {
            text = "LLIE Benchmark"
            textSize = 27f
            setTextColor(TEXT)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
            letterSpacing = -0.01f
        }
        counterView = label("0 / 0 images · 0%", 12f, MUTED)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
        }
        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        detailView = label("", 11f, MUTED)
        elapsedView = mono("", 11f, MUTED)
        detailRow.addView(detailView, LinearLayout.LayoutParams(0, WRAP, 1f))
        detailRow.addView(elapsedView)

        runCard.addView(headerRow)
        runCard.addView(modelView)
        runCard.addView(counterView)
        runCard.addView(progressBar, LinearLayout.LayoutParams(MATCH, dp(9)).apply { topMargin = dp(6) })
        runCard.addView(detailRow)
        column.addView(runCard)

        // ---- actions: test all / stop
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
        testAllButton = Button(this).apply {
            text = "Test all"
            setOnClickListener { startTestAll() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            visibility = View.GONE
            setOnClickListener { confirmStop() }
        }
        actionsRow.addView(testAllButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        actionsRow.addView(
            stopButton,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) },
        )
        column.addView(actionsRow)

        // ---- cores card
        val coresCard = card()
        coresCard.addView(
            label("CPU CORES · MHZ (LIVE)", 10f, MUTED).apply {
                letterSpacing = 0.10f
                setPadding(0, 0, 0, dp(8))
            },
        )
        repeat(2) { gridRowIndex ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (gridRowIndex > 0) setPadding(0, dp(6), 0, 0)
            }
            repeat(4) { columnIndex ->
                val chip = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(7), dp(6), dp(7), dp(6))
                    background = rounded(CARD, BORDER, 8f)
                }
                val name = label("cpu${gridRowIndex * 4 + columnIndex}", 9f, MUTED)
                val mhz = mono("--", 13f, TEXT).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                }
                val track = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = rounded(TRACK, 0, 2f)
                }
                val fill = View(this).apply { background = rounded(ACCENT, 0, 2f) }
                val spacer = View(this)
                track.addView(fill, LinearLayout.LayoutParams(0, MATCH, 0.6f))
                track.addView(spacer, LinearLayout.LayoutParams(0, MATCH, 0.4f))
                chip.addView(name)
                chip.addView(mhz)
                chip.addView(track, LinearLayout.LayoutParams(MATCH, dp(3)).apply { topMargin = dp(3) })
                coreNames += name
                coreMhz += mhz
                coreFills += fill
                coreSpacers += spacer
                val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
                if (columnIndex > 0) lp.marginStart = dp(5)
                row.addView(chip, lp)
            }
            coresCard.addView(row)
        }
        column.addView(coresCard)

        // ---- stats card
        val statsCard = card()
        lastPassValue = mono("-- ms", 13f, TEXT)
        medianValue = mono("-- ms", 13f, TEXT)
        etaValue = mono("--:--", 13f, TEXT)
        pssValue = mono("-- MB", 13f, TEXT)
        thermalValue = mono("-", 13f, TEXT)
        statsCard.addView(kvRow("last pass", lastPassValue, divider = false))
        statsCard.addView(kvRow("median so far", medianValue, divider = true))
        statsCard.addView(kvRow("ETA", etaValue, divider = true))
        statsCard.addView(kvRow("PSS peak", pssValue, divider = true))
        statsCard.addView(kvRow("thermal", thermalValue, divider = true))
        column.addView(statsCard)

        // ---- model list card
        val modelsCard = card()
        modelsCard.addView(label("MODELS", 10f, MUTED).apply { letterSpacing = 0.10f })
        modelStatusView = mono("", 11f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        modelsCard.addView(modelStatusView)
        column.addView(modelsCard)

        // ---- identity card: one clearly visible label field, server baked in
        val settingsCard = card()
        settingsCard.addView(
            label("DEVICE LABEL", 10f, MUTED).apply {
                letterSpacing = 0.10f
                setPadding(0, 0, 0, dp(6))
            },
        )
        deviceLabelInput = EditText(this).apply {
            hint = "e.g. 2a-lab (leave empty for the model name)"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            isSingleLine = true
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, ACCENT, 12f)
        }
        settingsCard.addView(deviceLabelInput)
        settingsCard.addView(
            label("UPLOAD SERVER", 10f, MUTED).apply {
                letterSpacing = 0.10f
                setPadding(0, dp(14), 0, dp(4))
            },
        )
        settingsCard.addView(
            mono(BuildConfig.SERVER_URL, 12f, TEXT).apply {
                setPadding(0, dp(2), 0, dp(10))
            },
        )
        val saveButton = Button(this).apply {
            text = "Save label"
            setOnClickListener { saveSettings() }
        }
        val setupButton = Button(this).apply {
            text = "Setup from server"
            setOnClickListener { setupFromServer() }
        }
        val uploadButton = Button(this).apply {
            text = "Upload missing"
            setOnClickListener { uploadMissingRuns("manual") }
        }
        val settingsButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        settingsButtons.addView(saveButton, LinearLayout.LayoutParams(0, WRAP, 1f))
        settingsButtons.addView(
            setupButton,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) },
        )
        settingsButtons.addView(
            uploadButton,
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) },
        )
        uploadStatus = label("", 11f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        settingsCard.addView(settingsButtons)
        settingsCard.addView(uploadStatus)
        column.addView(settingsCard)

        // ---- idle-only artifact list
        artifactsCard = card()
        artifactsCard.addView(label("STAGED ARTIFACTS", 10f, MUTED).apply { letterSpacing = 0.10f })
        artifactsView = label(describeArtifacts(), 11f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        artifactsCard.addView(artifactsView)
        column.addView(artifactsCard)
        return root
    }

    private fun kvRow(key: String, value: TextView, divider: Boolean): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (divider) {
            row.addView(View(this).apply { setBackgroundColor(BORDER) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        content.addView(label(key, 12f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
        content.addView(value)
        row.addView(content, LinearLayout.LayoutParams(MATCH, WRAP))
        return row
    }

    // ---------------------------------------------------------------- intent

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_RUN) return
        if (intent.getStringExtra(EXTRA_RUN_TOKEN) != BuildConfig.RUN_TOKEN) {
            phaseLabel.text = "run ignored (missing host token)"
            return
        }
        val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
        if (modelId.isNullOrEmpty()) {
            phaseLabel.text = "MISSING MODEL ID"
            return
        }
        val imageSet = intent.getStringExtra(EXTRA_IMAGE_SET) ?: "eval15"
        val backend = intent.getStringExtra(EXTRA_BACKEND)?.takeIf { it.isNotBlank() } ?: "cpu"
        val imageNames = intent.getStringExtra(EXTRA_IMAGE_NAMES)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
        synchronized(runningLock) {
            if (isRunning) {
                phaseLabel.text = "ALREADY RUNNING"
                return
            }
            isRunning = true
        }
        lastElapsedMs = 0L
        startTicking()
        Thread {
            val wakeLock = runCatching {
                EnvironmentRecorder.holdWakeLock(applicationContext)
            }.getOrNull()
            val outcome = try {
                BenchmarkExecutor(applicationContext).run(modelId, imageSet, backend, imageNames)
            } catch (t: Throwable) {
                BenchmarkExecutor.RunOutcome(false, File(filesDir, "$modelId-run.json"), t.toString())
            } finally {
                wakeLock?.release()
            }
            runOnUiThread {
                synchronized(runningLock) { isRunning = false }
                refresh()
                stopTickingWhenFinished()
            }
            refreshModelsAsync()
            maybeUpload(modelId)
        }.start()
    }

    // ---------------------------------------------------------------- actions

    private fun startTestAll() {
        if (TestAllState.running) return
        synchronized(runningLock) {
            if (isRunning) return
        }
        testAllButton.isEnabled = false
        stopButton.visibility = View.VISIBLE
        startTicking()
        Thread {
            val base = getExternalFilesDir(null)
            val models = if (base == null) {
                emptyList()
            } else {
                TestAllRunner.pendingModels(
                    TestAllRunner.discoverModels(File(base, "models")),
                    skipValid = true,
                    resultsRoot = File(base, "results"),
                )
            }
            if (models.isEmpty()) {
                runOnUiThread {
                    uploadStatus.text = "every staged model already has a valid v2 run"
                    testAllButton.isEnabled = true
                    stopButton.visibility = View.GONE
                }
                return@Thread
            }
            TestAllState.begin()
            val wakeLock = runCatching {
                EnvironmentRecorder.holdWakeLock(applicationContext)
            }.getOrNull()
            val outcomes = try {
                TestAllRunner(applicationContext).runAll(models) { model, state ->
                    TestAllState.setState(model, state)
                    // Upload as soon as the model finishes. Uploading the
                    // whole batch at the end meant an interrupted process
                    // silently lost every model it had not reached yet.
                    if (state == "done") maybeUpload(model)
                    runOnUiThread { refresh() }
                }
            } catch (t: Throwable) {
                runOnUiThread { uploadStatus.text = "test all aborted: ${t.message}" }
                null
            } finally {
                wakeLock?.release()
                TestAllState.finish()
                runOnUiThread {
                    testAllButton.isEnabled = true
                    stopButton.visibility = View.GONE
                    refresh()
                    stopTickingWhenFinished()
                }
                refreshModelsAsync()
            }
            // Catch up on anything the per-model uploads did not cover.
            uploadMissingRuns("after test all")
        }.start()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle("Stop the current run?")
            .setMessage("The model closes as failed with reason \"stopped by user\", and the Test-all queue stops.")
            .setPositiveButton("Stop") { _, _ ->
                RunControl.requestStop()
                uploadStatus.text = "stop requested"
            }
            .setNegativeButton("Keep running", null)
            .show()
    }

    private fun saveSettings() {
        prefs.edit()
            .putString(KEY_DEVICE_LABEL, deviceLabelInput.text.toString().trim())
            .apply()
        uploadStatus.text = "label saved"
    }

    /** Downloads the staged models+manifests+images bundle and unpacks it. */
    private fun setupFromServer() {
        if (isRunning || TestAllState.running) return
        prefs.edit()
            .putString(KEY_DEVICE_LABEL, deviceLabelInput.text.toString().trim())
            .apply()
        val base = getExternalFilesDir(null)
        if (base == null) {
            uploadStatus.text = "no external files dir"
            return
        }
        testAllButton.isEnabled = false
        uploadStatus.text = "downloading staging bundle ..."
        Thread {
            val archive = File(cacheDir, "llie-staging.zip")
            val download = BundleDownloader(BuildConfig.SERVER_URL, BuildConfig.SERVER_TOKEN).fetch(archive) { percent ->
                runOnUiThread { uploadStatus.text = "downloading staging bundle ... $percent%" }
            }
            if (!download.ok) {
                runOnUiThread {
                    uploadStatus.text = "bundle download failed: ${download.detail}"
                    testAllButton.isEnabled = true
                }
                return@Thread
            }
            val written = try {
                archive.inputStream().use { BundleArchive.unpack(it, base) }
            } catch (t: Throwable) {
                runOnUiThread {
                    uploadStatus.text = "bundle unpack failed: ${t.message}"
                    testAllButton.isEnabled = true
                }
                return@Thread
            } finally {
                archive.delete()
            }
            runOnUiThread {
                TestAllState.reset()
                uploadStatus.text = "staged $written files from server (${download.bytes / 1024 / 1024} MB)"
                testAllButton.isEnabled = true
                refresh()
            }
            refreshModelsAsync()
        }.start()
    }

    // ---------------------------------------------------------------- uploads

    private fun uploadWorker(): UploadWorker =
        UploadWorker(applicationContext, UploadLedger(prefs))

    /**
     * Uploads one finished run and records the server's acknowledgement.
     *
     * Called per model as it finishes, not once at the end of the batch:
     * a batch that is interrupted then loses at most the model in flight,
     * and the ledger tells the next launch what is still missing.
     */
    private fun maybeUpload(modelId: String) {
        val base = getExternalFilesDir(null) ?: return
        val ok = uploadWorker().upload(deviceLabel(), modelId, File(base, "results"))
        val pending = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toMutableSet()
        if (ok) pending.remove(modelId) else pending.add(modelId)
        prefs.edit().putStringSet(KEY_PENDING, pending).apply()
        runOnUiThread {
            uploadStatus.text = if (ok) {
                "uploaded $modelId"
            } else {
                "upload failed $modelId (will retry)"
            }
        }
    }

    /**
     * Re-sends every staged model that has a valid run on the device but
     * no server confirmation.
     *
     * This is the recovery path: it runs on every launch and on demand
     * from the "Upload missing" button, so a batch interrupted by a
     * killed process, a sleeping phone or a dropped network is completed
     * without re-running the benchmark.
     */
    private fun uploadMissingRuns(trigger: String) {
        val base = getExternalFilesDir(null) ?: return
        val resultsRoot = File(base, "results")
        Thread {
            // The legacy pending list first (models whose upload failed),
            // then the ledger sweep (models that were never attempted).
            // Both mark the ledger on success, so the sweep skips whatever
            // the first pass already delivered.
            val pending = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()
            pending.toList().forEach { maybeUpload(it) }
            val sent = uploadWorker().uploadMissing(
                device = deviceLabel(),
                modelsDir = File(base, "models"),
                resultsRoot = resultsRoot,
                hasValidRun = { TestAllRunner.hasValidRun(resultsRoot, it) },
            )
            runOnUiThread {
                uploadStatus.text = when {
                    sent.isEmpty() -> "nothing to upload ($trigger)"
                    else -> "uploaded ${sent.size} missing run(s): ${sent.joinToString()}"
                }
                refresh()
            }
        }.start()
    }

    private fun deviceLabel(): String = DeviceIdentity.label(
        Build.MODEL,
        Build.DEVICE,
        prefs.getString(KEY_DEVICE_LABEL, "").orEmpty(),
    )

    // ---------------------------------------------------------------- ticker

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.post(ticker)
    }

    /** Re-reads the model list off the UI thread (after a run/setup). */
    private fun refreshModelsAsync() {
        Thread {
            modelsCacheText = describeModels()
            runOnUiThread {
                modelStatusView.text = modelsCacheText
            }
        }.start()
    }

    private fun stopTickingWhenFinished() {
        val finished = RunMonitor.phase == RunMonitor.PHASE_DONE ||
            RunMonitor.phase == RunMonitor.PHASE_FAILED
        if (finished) stopButton.visibility = View.GONE
    }

    /** Runs on the sampler thread: the only place that touches disk/battery. */
    private fun sampleEnvironment() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        envCache = runCatching { EnvironmentRecorder.snapshot(applicationContext) }.getOrNull()
        modelsCacheText = describeModels()
    }

    private fun refresh() {
        val phase = RunMonitor.phase
        val active = phase == RunMonitor.PHASE_LOADING || phase == RunMonitor.PHASE_RUNNING
        modelView.text = RunMonitor.modelId.ifEmpty { "LLIE Benchmark" }
        phaseLabel.text = when (phase) {
            RunMonitor.PHASE_LOADING -> "LOADING MODEL"
            RunMonitor.PHASE_RUNNING -> "RUNNING · ${RunMonitor.backend.uppercase()} · ${RunMonitor.imageSet.uppercase()}"
            RunMonitor.PHASE_DONE -> "DONE"
            RunMonitor.PHASE_FAILED -> "FAILED · ${RunMonitor.failure}"
            else -> if (TestAllState.running) "TEST ALL · WAITING" else "IDLE · WAITING FOR A RUN"
        }
        val percent = progressPercent(
            RunMonitor.imagesDone,
            RunMonitor.imagesTotal,
            RunMonitor.passIndex,
            RunMonitor.passTotal,
        )
        progressBar.progress = percent
        counterView.text = "${RunMonitor.imagesDone} / ${RunMonitor.imagesTotal} images · $percent%"
        detailView.text = if (active) {
            "current: ${RunMonitor.currentImage.ifEmpty { "-" }} · pass ${RunMonitor.passIndex} / ${RunMonitor.passTotal}"
        } else {
            ""
        }
        val snap = envCache
        if (snap != null) {
            lastThermal = snap.thermal
            setTemperature(snap.batteryTempC)
            val freqs = snap.cpuFreqsMhz
            val peak = (freqs.maxOrNull() ?: 1).coerceAtLeast(1)
            freqs.take(coreMhz.size).forEachIndexed { index, freq ->
                coreNames[index].text = "cpu$index"
                coreMhz[index].text = freq.toString()
                setBarFraction(index, freq.toFloat() / peak)
            }
        }
        if (active) {
            lastElapsedMs = SystemClock.elapsedRealtime() - RunMonitor.startMs
        }
        elapsedView.text = formatDurationMs(lastElapsedMs)
        lastPassValue.text = if (RunMonitor.lastPassMs > 0) "${RunMonitor.lastPassMs} ms" else "-- ms"
        medianValue.text = RunMonitor.medianPassMs()?.let { "$it ms" } ?: "-- ms"
        val eta = if (active) {
            etaMs(
                RunMonitor.imagesDone,
                RunMonitor.imagesTotal,
                RunMonitor.passTotal,
                RunMonitor.passIndex,
                lastElapsedMs,
            )
        } else {
            null
        }
        etaValue.text = eta?.let { formatDurationMs(it) } ?: "--:--"
        pssValue.text = if (RunMonitor.pssPeakMb > 0) "${RunMonitor.pssPeakMb} MB" else "-- MB"
        thermalValue.text = lastThermal
        stopButton.visibility = if (isRunning || TestAllState.running) View.VISIBLE else View.GONE
        testAllButton.isEnabled = !(isRunning || TestAllState.running)
        modelStatusView.text = modelsCacheText
        artifactsCard.visibility = if (phase == RunMonitor.PHASE_IDLE && !TestAllState.running) View.VISIBLE else View.GONE
        if (artifactsCard.visibility == View.VISIBLE) artifactsView.text = artifactsCacheText
    }

    /** Sampler-thread only: lists staged models and their run status. */
    private fun describeModels(): String {
        val base = getExternalFilesDir(null) ?: return ""
        val staged = TestAllRunner.discoverModels(File(base, "models"))
        val results = File(base, "results")
        return staged.joinToString("\n") { model ->
            val state = TestAllState.stateOf(model)
                ?: if (TestAllRunner.hasValidRun(results, model)) "valid v2 run" else "pending"
            "$model — $state"
        }
    }

    private fun setTemperature(tempC: Double?) {
        if (tempC == null) {
            tempBadge.text = "-- °C"
            tempBadge.setTextColor(GREEN_FG)
            tempBadge.background = pill(GREEN_BG)
            return
        }
        tempBadge.text = String.format("%.1f °C", tempC)
        when {
            tempC >= 45.0 -> {
                tempBadge.setTextColor(RED_FG)
                tempBadge.background = pill(RED_BG)
            }
            tempC >= 40.0 -> {
                tempBadge.setTextColor(AMBER_FG)
                tempBadge.background = pill(AMBER_BG)
            }
            else -> {
                tempBadge.setTextColor(GREEN_FG)
                tempBadge.background = pill(GREEN_BG)
            }
        }
    }

    private fun setBarFraction(index: Int, fraction: Float) {
        val fill = coreFills[index]
        val spacer = coreSpacers[index]
        val clamped = fraction.coerceIn(0.05f, 1f)
        (fill.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = clamped
            fill.layoutParams = it
        }
        (spacer.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = 1f - clamped
            spacer.layoutParams = it
        }
    }

    private fun describeArtifacts(): String {
        val dir = getExternalFilesDir(null)
        val models = dir?.listFiles { f -> f.isFile && f.extension == "onnx" }.orEmpty()
        return buildString {
            append("models dir: ").append(dir?.absolutePath).append('\n')
            append("artifacts: ").append(models.size).append('\n')
            models.forEach { append(" - ").append(it.name).append('\n') }
        }
    }

    // ---------------------------------------------------------------- styling

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * resources.displayMetrics.density
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun pill(fill: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 999f
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(CARD, BORDER, 14f)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    private fun label(text: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
    }

    private fun mono(text: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    companion object {
        const val ACTION_RUN = "org.llie.bench.RUN"
        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_IMAGE_SET = "image_set"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_IMAGE_NAMES = "image_names"
        const val EXTRA_RUN_TOKEN = "run_token"
        private const val TICK_MS = 1000L
        private const val PREFS = "llie"
        private const val KEY_DEVICE_LABEL = "device_label"
        private const val KEY_PENDING = "pending_uploads"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val runningLock = Any()
        @Volatile private var isRunning = false

        private val BG = Color.parseColor("#F5F6F8")
        private val CARD = Color.parseColor("#FFFFFF")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val TRACK = Color.parseColor("#EDEFF3")
        private val ACCENT = Color.parseColor("#2563EB")
        private val GREEN_BG = Color.parseColor("#DCFCE7")
        private val GREEN_FG = Color.parseColor("#166534")
        private val AMBER_BG = Color.parseColor("#FEF3C7")
        private val AMBER_FG = Color.parseColor("#92400E")
        private val RED_BG = Color.parseColor("#FEE2E2")
        private val RED_FG = Color.parseColor("#991B1B")
    }
}
