package com.example.mpesaboard

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class TxType { SENT, RECEIVED }

/** One M-Pesa style SMS. [id] is the transaction code, e.g. "QG78KP92XZ". */
data class SmsMessage(
    val id: String,
    val body: String,
    val timestamp: Long,
    val type: TxType,
)

data class WalletState(
    val balanceCents: Long = Money.DEFAULT_START_CENTS,
    val messages: List<SmsMessage> = emptyList(), // newest first
    val isSetUp: Boolean = false,
)

/** All money is stored as Long cents to avoid floating point drift. */
object Money {
    const val DEFAULT_START_CENTS = 50_000_00L

    private fun formatter() = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

    fun format(cents: Long): String = formatter().format(BigDecimal(cents).movePointLeft(2))

    fun parse(text: String): Long? =
        text.trim().toBigDecimalOrNull()
            ?.setScale(2, RoundingMode.HALF_UP)
            ?.movePointRight(2)
            ?.toLong()

    /** Accepts up to 9 digits and 2 decimals while typing. */
    private val inputRegex = Regex("^\\d{0,9}(\\.\\d{0,2})?$")
    fun isValidInput(text: String) = inputRegex.matches(text)
}
