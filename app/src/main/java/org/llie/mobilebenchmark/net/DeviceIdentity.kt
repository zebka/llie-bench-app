package org.llie.mobilebenchmark.net

/**
 * Human-chosen device identity for uploads.
 *
 * Two physically identical phones report the same `Build.MODEL-DEVICE`
 * (`A142-Pacman`), so a lab that runs several units of one model cannot tell
 * them apart. A custom label (e.g. `2a-lab-1`) replaces the default and ends
 * up in the server path and in the upload `meta`.
 */
object DeviceIdentity {

    fun label(model: String, device: String, custom: String): String {
        val cleaned = custom.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        return cleaned.ifEmpty { "${model}-${device}" }
    }
}
