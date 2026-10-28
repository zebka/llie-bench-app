package org.llie.mobilebenchmark.net

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The recovery logic behind the "Upload missing" button.
 *
 * A batch that is interrupted (killed process, sleeping phone, dropped
 * network) must not silently lose the models it never reached, and a
 * confirmed model must not be re-sent on every launch.
 */
@RunWith(RobolectricTestRunner::class)
class UploadLedgerTest {

    private fun ledger(): UploadLedger {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(UploadLedger.PREFS, Context.MODE_PRIVATE)
            .edit().clear().commit()
        return UploadLedger(
            context.getSharedPreferences(UploadLedger.PREFS, Context.MODE_PRIVATE),
        )
    }

    @Test
    fun `ledger starts empty and remembers confirmations`() {
        val ledger = ledger()
        assertFalse(ledger.isConfirmed("dev", "zero-dce"))
        ledger.confirm("dev", "zero-dce")
        assertTrue(ledger.isConfirmed("dev", "zero-dce"))
        assertEquals(1, ledger.confirmedKeys().size)
    }

    @Test
    fun `a different device label is a different run`() {
        val ledger = ledger()
        ledger.confirm("dev-a", "zero-dce")
        assertFalse(ledger.isConfirmed("dev-b", "zero-dce"))
    }

    @Test
    fun `forget removes a confirmation`() {
        val ledger = ledger()
        ledger.confirm("dev", "zero-dce")
        ledger.forget("dev", "zero-dce")
        assertFalse(ledger.isConfirmed("dev", "zero-dce"))
    }

    @Test
    fun `missing lists valid runs that were never confirmed`() {
        val ledger = ledger()
        ledger.confirm("dev", "zero-dce")
        val missing = UploadRecovery.missing(
            ledger = ledger,
            device = "dev",
            stagedModels = listOf("zero-dce", "sci-medium", "pairlie"),
            // sci-medium ran but was never uploaded; pairlie never ran.
            hasValidRun = { it == "zero-dce" || it == "sci-medium" },
        )
        assertEquals(listOf("sci-medium"), missing)
    }

    @Test
    fun `missing is empty when every valid run is confirmed`() {
        val ledger = ledger()
        listOf("zero-dce", "sci-medium").forEach { ledger.confirm("dev", it) }
        val missing = UploadRecovery.missing(
            ledger = ledger,
            device = "dev",
            stagedModels = listOf("zero-dce", "sci-medium", "pairlie"),
            hasValidRun = { it != "pairlie" },
        )
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `an interrupted batch is fully recoverable`() {
        // The situation that lost three models on a real device: five of
        // eight uploads were confirmed, the rest had valid runs on the
        // phone but no confirmation.
        val ledger = ledger()
        listOf("retinexformer-lolv1", "lyt-net", "pairlie", "zero-dce-plus", "mobileie-6ch")
            .forEach { ledger.confirm("S24-FE", it) }
        val missing = UploadRecovery.missing(
            ledger = ledger,
            device = "S24-FE",
            stagedModels = listOf(
                "retinexformer-lolv1", "lyt-net", "pairlie", "zero-dce-plus",
                "zero-dce", "mobileie-6ch", "ruas-lol", "sci-medium",
            ),
            hasValidRun = { true },
        )
        assertEquals(listOf("zero-dce", "ruas-lol", "sci-medium"), missing)
    }
}
