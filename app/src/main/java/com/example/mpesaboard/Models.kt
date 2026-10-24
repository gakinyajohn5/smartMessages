package com.example.mpesaboard

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** One M-Pesa style SMS. [id] is the transaction code, e.g. "QG78KP92XZ". */
enum class PayKind(val label: String) {
    BANK("Bank"),
    PAYBILL("Paybill"),
    TILL("Till number"),
    POCHI("Pochi la Biashara"),
}

/**
 * One message shown in the thread.
 * - [sender] is the phone number / name it came from. Empty for messages made inside this app.
 * - [sent] is true when YOU sent it (so it can be drawn on the right side).
 * The two extra fields have defaults, so the older code that creates messages still works.
 */
data class SmsMessage(
    val id: String,
    val body: String,
    val timestamp: Long,
    val sender: String = "",
    val sent: Boolean = false,
)

data class PayRequest(
    val kind: PayKind,
    val name: String,
    val number: String,
    val account: String,
    val amountText: String,
)

data class WalletState(
    val balanceCents: Long = Money.DEFAULT_START_CENTS,
    val startCents: Long = Money.DEFAULT_START_CENTS,
    val spentCents: Long = 0,
    val addedCents: Long = 0,
    val playerName: String = "",
    val licenseCode: String = "",
    val messages: List<SmsMessage> = emptyList(), // stored newest first
    val isSetUp: Boolean = false,
)

/** All money is stored as Long cents to avoid floating point drift. */
object Money {
    // Players start with Ksh 0. Money only arrives by redeeming a top-up code that YOU create in Supabase,
    // so the amount is always decided in your database, never typed into the app.
    const val DEFAULT_START_CENTS = 0L

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
