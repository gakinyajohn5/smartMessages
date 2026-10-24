package com.example.mpesaboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("mpesa_board", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<WalletState> = _state.asStateFlow()

    /** The message currently shown in the top banner, or null when hidden. */
    private val _banner = MutableStateFlow<SmsMessage?>(null)
    val banner: StateFlow<SmsMessage?> = _banner.asStateFlow()

    fun startNewGame(startingCents: Long) {
        _state.value = WalletState(startingCents, emptyList(), isSetUp = true)
        _banner.value = null
        save()
    }

    /**
     * Applies a payment. Returns an error message to show in the form,
     * or null on success.
     */
    fun submit(recipient: String, amountText: String): String? {
        val name = recipient.trim()
        if (name.isEmpty()) return "Enter who you are paying"
        val cents = Money.parse(amountText) ?: return "Enter a valid amount"
        if (cents <= 0) return "Amount must be greater than Ksh0.00"

        val current = _state.value
        if (cents > current.balanceCents) {
            return "Not enough balance. You have Ksh${Money.format(current.balanceCents)}"
        }

        val newBalance = current.balanceCents - cents
        val now = System.currentTimeMillis()
        val code = uniqueCode(current.messages)
        val date = SimpleDateFormat("d/M/yy", Locale.US).format(Date(now))
        val time = SimpleDateFormat("h:mm a", Locale.US).format(Date(now))
        val body = "$code Confirmed. Ksh${Money.format(cents)} paid to $name. on $date at $time. " +
            "New M-PESA balance is Ksh${Money.format(newBalance)}. Transaction cost, Ksh0.00."

        val message = SmsMessage(code, body, now)
        _state.value = current.copy(
            balanceCents = newBalance,
            messages = listOf(message) + current.messages, // stored newest first
            isSetUp = true,
        )
        _banner.value = message
        save()
        return null
    }

    fun dismissBanner() {
        _banner.value = null
    }

    // ---- helpers & persistence -------------------------------------------------

    private fun uniqueCode(existing: List<SmsMessage>): String {
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val alnum = letters + "0123456789"
        while (true) {
            val code = buildString {
                repeat(2) { append(letters.random()) }
                repeat(8) { append(alnum[Random.nextInt(alnum.length)]) }
            }
            if (existing.none { it.id == code }) return code
        }
    }

    private fun load(): WalletState {
        if (!prefs.contains(KEY_BALANCE)) return WalletState()
        val messages = runCatching {
            val arr = JSONArray(prefs.getString(KEY_MESSAGES, "[]"))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SmsMessage(
                    id = o.getString("id"),
                    body = o.getString("body"),
                    timestamp = o.getLong("ts"),
                )
            }
        }.getOrDefault(emptyList())
        return WalletState(prefs.getLong(KEY_BALANCE, Money.DEFAULT_START_CENTS), messages, true)
    }

    private fun save() {
        val s = _state.value
        val arr = JSONArray()
        s.messages.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("body", it.body)
                    .put("ts", it.timestamp)
            )
        }
        prefs.edit()
            .putLong(KEY_BALANCE, s.balanceCents)
            .putString(KEY_MESSAGES, arr.toString())
            .apply()
    }

    private companion object {
        const val KEY_BALANCE = "balance_cents"
        const val KEY_MESSAGES = "messages"
    }
}
