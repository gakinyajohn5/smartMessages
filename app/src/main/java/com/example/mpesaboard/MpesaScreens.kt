package com.example.mpesaboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val BANNER_MILLIS = 4_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MpesaApp(vm: WalletViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val banner by vm.banner.collectAsStateWithLifecycle()

    if (state.licenseCode.isEmpty()) {
        ActivationScreen(onActivate = vm::activate)
        return
    }

    // Messages copied from the phone's own messages app (see WalletViewModel.importSms).
    val imported by vm.imported.collectAsStateWithLifecycle()

    // Do we already have permission to read the phone's messages?
    val context = LocalContext.current
    var smsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // The system pop-up that asks "Allow Pesa Board to read your messages?"
    val askSmsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        smsGranted = it
    }
    // Asks once when the screen opens (if not granted yet).
    LaunchedEffect(Unit) {
        if (!smsGranted) askSmsPermission.launch(Manifest.permission.READ_SMS)
    }
    // As soon as permission is granted, copy the messages in.
    LaunchedEffect(smsGranted) {
        if (smsGranted) vm.importSms()
    }

    var showPay by rememberSaveable { mutableStateOf(false) }
    var showCash by rememberSaveable { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    // Oldest at the top, newest at the bottom, grouped by day like a chat thread.
    val dayFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.US) }
    // Combine the app's own messages with the phone's messages, newest first.
    val allMessages = remember(state.messages, imported) {
        (state.messages + imported).sortedByDescending { it.timestamp }
    }
    val grouped = remember(allMessages) {
        allMessages.asReversed().groupBy { dayFmt.format(Date(it.timestamp)) }
    }
    val totalItems = grouped.values.sumOf { it.size + 1 }
    val listState = rememberLazyListState()
    LaunchedEffect(totalItems) {
        if (totalItems > 0) listState.animateScrollToItem(totalItems - 1)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Pay someone") },
                                    onClick = { menuOpen = false; showPay = true })
                                DropdownMenuItem(text = { Text("Redeem top-up code") },
                                    onClick = { menuOpen = false; showCash = true })
                                DropdownMenuItem(text = { Text("Profile") },
                                    onClick = { menuOpen = false; showProfile = true })
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(state.playerName.ifBlank { "PESA BOARD" }, fontWeight = FontWeight.Medium)
                            Text(
                                "Game money, not real M-PESA",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = { Avatar(Modifier.padding(end = 16.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // The "Pay someone" bar and "Remaining balance" card were removed from here:
                // the main screen is now just messages.
                if (!smsGranted) {
                    // Shown only if the user said no to the pop-up; tapping asks again.
                    SmsPermissionBar(onClick = { askSmsPermission.launch(Manifest.permission.READ_SMS) })
                }
                if (allMessages.isEmpty()) {
                    EmptyInbox()
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        grouped.forEach { (day, msgs) ->
                            item(key = "day-$day") { DayHeader(day) }
                            items(msgs, key = { it.id }) { SmsRow(it) }
                        }
                    }
                }
            }
        }

        TransactionBanner(
            message = banner,
            onDismiss = vm::dismissBanner,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // The old "Set your starting balance" and "Reset balance" boxes were removed:
    // the balance is now controlled only by top-up codes from your Supabase database.
    if (showPay) PayDialog(onSubmit = vm::pay, onDismiss = { showPay = false })
    if (showCash) RedeemDialog(onSubmit = vm::redeem, onDismiss = { showCash = false })
    if (showProfile) {
        ProfileDialog(
            initialName = state.playerName,
            onSave = vm::saveProfile,
            onDismiss = { showProfile = false },
        )
    }
}

/** A small bar asking the user to allow reading messages (only shown while permission is missing). */
@Composable
private fun SmsPermissionBar(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            "Tap here to allow reading your messages",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "No messages yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DayHeader(day: String) {
    Text(
        day,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SmsRow(message: SmsMessage) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("h:mm a", Locale.US).format(Date(message.timestamp))
    }
    // Messages you sent go on the right; everything else on the left.
    val align = if (message.sent) Alignment.End else Alignment.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        // Show who a copied message is from (the app's own messages have no sender).
        if (message.sender.isNotBlank() && !message.sent) {
            Text(
                message.sender,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
            // Sent messages get the accent colour, received ones the neutral colour.
            color = if (message.sent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (message.sent) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                message.body,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Avatar(modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier = modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

/** Slides down from the top, shows the SMS, then dismisses itself. Tap to dismiss early. */
@Composable
fun TransactionBanner(message: SmsMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(message) }
    LaunchedEffect(message?.id) {
        if (message != null) {
            last = message
            delay(BANNER_MILLIS)
            onDismiss()
        }
    }
    val shown = message ?: last

    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        if (shown != null) {
            Card(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Avatar(size = 40)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text("PESA BOARD", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("now", style = MaterialTheme.typography.labelSmall)
                        }
                        Text(
                            shown.body,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PayDialog(onSubmit: (PayRequest) -> String?, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(PayKind.BANK) }
    var name by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val nameLabel = when (kind) {
        PayKind.BANK -> "Bank name"
        PayKind.PAYBILL, PayKind.TILL -> "Business name"
        PayKind.POCHI -> "Recipient name"
    }
    val numberLabel = when (kind) {
        PayKind.BANK, PayKind.PAYBILL -> "Paybill number"
        PayKind.TILL -> "Till number"
        PayKind.POCHI -> "Phone number"
    }
    val needsAccount = kind == PayKind.BANK || kind == PayKind.PAYBILL

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay someone") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PayKind.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { k ->
                            FilterChip(
                                selected = kind == k,
                                onClick = { kind = k; error = null },
                                label = { Text(k.label) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30); error = null },
                    label = { Text(nameLabel) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { if (it.all(Char::isDigit) && it.length <= 10) { number = it; error = null } },
                    label = { Text(numberLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (needsAccount) {
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it.take(20); error = null },
                        label = { Text("Account number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (Money.isValidInput(it)) { amount = it; error = null } },
                    label = { Text("Amount (Ksh)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = onSubmit(PayRequest(kind, name, number, account, amount))
                if (error == null) onDismiss()
            }) { Text("Pay") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActivationScreen(onActivate: (String, (String?) -> Unit) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.statusBarsPadding().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Avatar(size = 56)
            Spacer(Modifier.size(20.dp))
            Text("Activate Pesa Board", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Enter the activation code you received when you bought the game. " +
                    "It works on one phone only.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(20.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(14); error = null },
                label = { Text("Activation code") },
                placeholder = { Text("XXXX-XXXX-XXXX") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(12.dp))
            Button(
                enabled = !busy && code.isNotBlank(),
                onClick = { busy = true; error = null; onActivate(code) { e -> busy = false; error = e } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Checking…" else "Activate") }
        }
    }
}

@Composable
private fun RedeemDialog(onSubmit: (String, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Redeem top-up code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the top-up code you got from the game admin. You need an internet connection.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(14); error = null },
                    label = { Text("Top-up code") },
                    placeholder = { Text("XXXX-XXXX-XXXX") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.isNotBlank(),
                onClick = {
                    busy = true
                    onSubmit(code) { e -> busy = false; error = e; if (e == null) onDismiss() }
                },
            ) { Text(if (busy) "Checking…" else "Redeem") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileDialog(
    initialName: String,
    onSave: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(20) },
                label = { Text("Player name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
