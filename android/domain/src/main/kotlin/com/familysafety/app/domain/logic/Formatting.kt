package com.familysafety.app.domain.logic

object Formatting {
    /** "2 h 05 min", "45 min", "< 1 min". */
    fun duration(ms: Long): String {
        val totalMinutes = ms / 60_000
        if (totalMinutes <= 0) return if (ms > 0) "< 1 min" else "0 min"
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "%d h %02d min".format(h, m) else "$m min"
    }

    /** Normalises user input for pairing codes: upper-case alphanumerics, formatted XXXX-XXXX when complete. */
    fun normalizePairingCode(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }.take(8)

    fun displayPairingCode(normalized: String): String =
        if (normalized.length > 4) normalized.substring(0, 4) + "-" + normalized.substring(4) else normalized

    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    fun isValidPairingCode(normalized: String) = normalized.length == 8 && normalized.all { it in ALPHABET }

    fun isValidEmail(email: String) = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())
    fun isValidPassword(password: String) = password.length in 12..128
}
