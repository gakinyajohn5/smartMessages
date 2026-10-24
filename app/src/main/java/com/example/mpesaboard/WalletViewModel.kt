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

    private val _banner = MutableStateFlow<SmsMessage?>(null)
    val banner: StateFlow<SmsMessage?> = _banner.asStateFlow()

    fun startNewGame(startingCents: Long) {
        val old = _state.value
        _state.value = WalletState(
            balanceCents = startingCents,
            startCents = startingCents,
            playerName = old.playerName,
            bankerPin = old.bankerPin,
            isSetUp = true,
        )
        _banner.value = null
        save()
    }

    fun saveProfile(name: String, pin: String): String? {
        if (pin.isNotEmpty() && pin.length < 4) return "Banker PIN needs at least 4 digits"
        _state.value = _state.value.copy(playerName = name.trim(), bankerPin = pin)
        save()
        return null
    }

    /** Pays a bank, paybill, till or Pochi la Biashara. Returns an error message or null. */
    fun pay(req: PayRequest): String? {
        val name = req.name.trim().uppercase(Locale.ROOT)
        val number = req.number.trim()
        val account = req.account.trim().uppercase(Locale.ROOT)

        if (name.isEmpty()) return "Enter the recipient name"
        when (req.kind) {
            PayKind.POCHI ->
                if (!number.matches(Regex("\\d{10}"))) return "Enter a 10-digit phone number"
            PayKind.TILL ->
                if (!number.matches(Regex("\\d{5,7}"))) return "Enter a 5 to 7 digit till number"
            PayKind.PAYBILL, PayKind.BANK -> {
                if (!number.matches(Regex("\\d{5,7}"))) return "Enter a 5 to 7 digit paybill number"
                if (account.isEmpty()) return "Enter the account number"
            }
        }
        val cents = Money.parse(req.amountText) ?: return "Enter a valid amount"
        if (cents <= 0) return "Amount must be greater than Ksh0.00"

        val current = _state.value
        if (cents > current.balanceCents) {
            return "Not enough balance. You have Ksh${Money.format(current.balanceCents)}"
        }
        val newBalance = current.balanceCents - cents
        val (code, date, time) = stamp(current.messages)
        val amount = "Ksh${Money.format(cents)}"
        val balance = "Ksh${Money.format(newBalance)}"
        val tail = "New M-PESA balance is $balance. Transaction cost, Ksh0.00."

        val body = when (req.kind) {
            PayKind.TILL ->
                "$code Confirmed. $amount paid to $name. on $date at $time. $tail"
            PayKind.PAYBILL, PayKind.BANK ->
                "$code Confirmed. $amount sent to $name for account $account on $date at $time. $tail"
            PayKind.POCHI ->
                "$code Confirmed. $amount sent to $name $number on $date at $time. $tail"
        }
        commit(current, newBalance, SmsMessage(code, body, System.currentTimeMillis()),
            spent = cents, added = 0)
        return null
    }

    /** Banker top-up: needs the PIN saved in the profile. Returns an error message or null. */
    fun addCash(pin: String, amountText: String): String? {
        val current = _state.value
        if (current.bankerPin.isEmpty()) return "Set a banker PIN in Profile first"
        if (pin != current.bankerPin) return "Wrong banker PIN"
        val cents = Money.parse(amountText) ?: return "Enter a valid amount"
        if (cents <= 0) return "Amount must be greater than Ksh0.00"

        val newBalance = current.balanceCents + cents
        val (code, date, time) = stamp(current.messages)
        val body = "$code Confirmed. You have received Ksh${Money.format(cents)} from BANKER " +
            "on $date at $time. New M-PESA balance is Ksh${Money.format(newBalance)}."
        commit(current, newBalance, SmsMessage(code, body, System.currentTimeMillis()),
            spent = 0, added = cents)
        return null
    }

    fun dismissBanner() {
        _banner.value = null
    }

    // ---- helpers & persistence -------------------------------------------------

    private fun commit(current: WalletState, newBalance: Long, msg: SmsMessage, spent: Long, added: Long) {
        _state.value = current.copy(
            balanceCents = newBalance,
            spentCents = current.spentCents + spent,
            addedCents = current.addedCents + added,
            messages = listOf(msg) + current.messages,
            isSetUp = true,
        )
        _banner.value = msg
        save()
    }

    private fun stamp(existing: List<SmsMessage>): Triple<String, String, String> {
        val now = Date()
        return Triple(
            uniqueCode(existing),
            SimpleDateFormat("d/M/yy", Locale.US).format(now),
            SimpleDateFormat("h:mm a", Locale.US).format(now),
        )
    }

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
                SmsMessage(o.getString("id"), o.getString("body"), o.getLong("ts"))
            }
        }.getOrDefault(emptyList())
        val balance = prefs.getLong(KEY_BALANCE, Money.DEFAULT_START_CENTS)
        return WalletState(
            balanceCents = balance,
            startCents = prefs.getLong(KEY_START, balance),
            spentCents = prefs.getLong(KEY_SPENT, 0),
            addedCents = prefs.getLong(KEY_ADDED, 0),
            playerName = prefs.getString(KEY_NAME, "") ?: "",
            bankerPin = prefs.getString(KEY_PIN, "") ?: "",
            messages = messages,
            isSetUp = true,
        )
    }

    private fun save() {
        val s = _state.value
        val arr = JSONArray()
        s.messages.forEach {
            arr.put(JSONObject().put("id", it.id).put("body", it.body).put("ts", it.timestamp))
        }
        prefs.edit()
            .putLong(KEY_BALANCE, s.balanceCents)
            .putLong(KEY_START, s.startCents)
            .putLong(KEY_SPENT, s.spentCents)
            .putLong(KEY_ADDED, s.addedCents)
            .putString(KEY_NAME, s.playerName)
            .putString(KEY_PIN, s.bankerPin)
            .putString(KEY_MESSAGES, arr.toString())
            .apply()
    }

    private companion object {
        const val KEY_BALANCE = "balance_cents"
        const val KEY_START = "start_cents"
        const val KEY_SPENT = "spent_cents"
        const val KEY_ADDED = "added_cents"
        const val KEY_NAME = "player_name"
        const val KEY_PIN = "banker_pin"
        const val KEY_MESSAGES = "messages"
    }
}
