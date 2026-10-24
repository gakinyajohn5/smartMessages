package com.example.mpesaboard

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class WalletViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("mpesa_board", Context.MODE_PRIVATE)

    private val deviceId: String = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        ?: "unknown-device"

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
            licenseCode = old.licenseCode,
            isSetUp = true,
        )
        _banner.value = null
        save()
    }

    fun saveProfile(name: String) {
        _state.value = _state.value.copy(playerName = name.trim())
        save()
    }

    /** Checks the activation code with the server and locks it to this phone. */
    fun activate(code: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Api.rpc("activate_license", JSONObject().put("p_code", code.trim()).put("p_device", deviceId))
                }
            }
            val (status, json) = res.getOrElse { done(OFFLINE); return@launch }
            if (status !in 200..299 || !json.optBoolean("ok")) {
                done(json.optString("error", GENERIC_ERROR)); return@launch
            }
            _state.value = _state.value.copy(licenseCode = json.optString("licenseCode", code.trim()))
            save()
            done(null)
        }
    }

    /** Redeems an admin-issued top-up code. Each code works once, on the activated phone only. */
    fun redeem(codeInput: String, done: (String?) -> Unit) {
        val license = _state.value.licenseCode
        val key = codeInput.uppercase().filter { it.isLetterOrDigit() }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    Api.rpc(
                        "redeem_topup",
                        JSONObject().put("p_license", license).put("p_code", codeInput.trim()).put("p_device", deviceId),
                    )
                }
            }
            val (status, json) = res.getOrElse { done(OFFLINE); return@launch }
            if (status !in 200..299 || !json.optBoolean("ok")) {
                done(json.optString("error", GENERIC_ERROR)); return@launch
            }

            val used = prefs.getStringSet(KEY_REDEEMED, emptySet()).orEmpty()
            // The server says "replay" when this phone redeemed it before. If we never credited it
            // (the earlier response got lost), credit it now; otherwise refuse.
            if (json.optBoolean("replay") && key in used) { done("You've already used this code"); return@launch }

            val cents = json.getLong("amountCents")
            val current = _state.value
            val newBalance = current.balanceCents + cents
            val (id, date, time) = stamp(current.messages)
            val body = "$id Top-up. Ksh${Money.format(cents)} added by Pesa Board on $date at $time. " +
                "Balance Ksh${Money.format(newBalance)}. Game money, not real."
            commit(current, newBalance, SmsMessage(id, body, System.currentTimeMillis()), spent = 0, added = cents)
            prefs.edit().putStringSet(KEY_REDEEMED, used + key).apply()
            done(null)
        }
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
        val tail = "Balance $balance. Game money, not real."
        val dest = when (req.kind) {
            PayKind.TILL -> "$name (Till $number)"
            PayKind.PAYBILL -> "$name (Paybill $number, Acc $account)"
            PayKind.BANK -> "$name (Bank paybill $number, Acc $account)"
            PayKind.POCHI -> "$name (Pochi $number)"
        }
        val body = "$code Paid $amount to $dest on $date at $time. $tail"
        commit(current, newBalance, SmsMessage(code, body, System.currentTimeMillis()),
            spent = cents, added = 0)
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
        if (!prefs.getBoolean(KEY_SETUP, false)) {
            return WalletState(licenseCode = prefs.getString(KEY_LICENSE, "") ?: "")
        }
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
            licenseCode = prefs.getString(KEY_LICENSE, "") ?: "",
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
            .putBoolean(KEY_SETUP, s.isSetUp)
            .putLong(KEY_BALANCE, s.balanceCents)
            .putLong(KEY_START, s.startCents)
            .putLong(KEY_SPENT, s.spentCents)
            .putLong(KEY_ADDED, s.addedCents)
            .putString(KEY_NAME, s.playerName)
            .putString(KEY_LICENSE, s.licenseCode)
            .putString(KEY_MESSAGES, arr.toString())
            .apply()
    }

    private companion object {
        const val KEY_SETUP = "is_set_up"
        const val KEY_BALANCE = "balance_cents"
        const val KEY_START = "start_cents"
        const val KEY_SPENT = "spent_cents"
        const val KEY_ADDED = "added_cents"
        const val KEY_NAME = "player_name"
        const val KEY_LICENSE = "license_code"
        const val KEY_REDEEMED = "redeemed_codes"
        const val OFFLINE = "Couldn't reach the server. Check your internet and try again."
        const val GENERIC_ERROR = "Something went wrong. Try again."
        const val KEY_MESSAGES = "messages"
    }
}
