package com.martinsmayhem.mymoney.mobile

import kotlinx.datetime.LocalDate
import kotlin.math.abs

/** Money and dates as the computer shows them. */
object Format {

    /** "$1,234.56", or "-$1,234.56". */
    fun money(cents: Long): String {
        val a = abs(cents)
        val dollars = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val c = (a % 100).toString().padStart(2, '0')
        return (if (cents < 0) "-" else "") + "$" + dollars + "." + c
    }

    /** Typed money ("12", "12.5", "$1,234.56") as cents, or null if it isn't an amount. Always positive. */
    fun parseMoney(text: String): Long? {
        val t = text.trim().removePrefix("$").replace(",", "").removePrefix("-")
        if (t.isEmpty() || !Regex("\\d*(\\.\\d{0,2})?").matches(t) || t == ".") return null
        val parts = t.split('.')
        val dollars = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val cents = if (parts.size > 1) parts[1].padEnd(2, '0').toLong() else 0L
        return dollars * 100 + cents
    }

    /** A date in the computer's format: "MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd", "MM-dd-yyyy" or "dd.MM.yyyy". */
    fun date(d: LocalDate, pattern: String): String =
        pattern.replace("yyyy", d.year.toString().padStart(4, '0'))
            .replace("MM", d.month.ordinal.plus(1).toString().padStart(2, '0'))
            .replace("dd", d.day.toString().padStart(2, '0'))

    fun status(code: String): String = when (code) {
        "R" -> "Reconciled"
        "C" -> "Cleared"
        "I" -> "Reconciling"
        "P" -> "Pending"
        "D" -> "Paid"
        "S" -> "Scheduled"
        else -> "None"
    }

    /** The statuses the phone may set (not Reconciled or Reconciling: those are the computer's). */
    val phoneStatuses = listOf("U", "C", "P", "D", "S")
}
