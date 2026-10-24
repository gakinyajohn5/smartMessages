package com.example.mpesaboard

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    var showForm by rememberSaveable { mutableStateOf(false) }
    var showNewGame by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("M-PESA", fontWeight = FontWeight.Bold)
                            Text("Board game wallet", style = MaterialTheme.typography.labelMedium)
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Start new game") },
                                    onClick = { menuOpen = false; showNewGame = true },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
            floatingActionButton = {
                if (state.isSetUp) {
                    ExtendedFloatingActionButton(
                        onClick = { showForm = true },
                        icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        text = { Text("Send / Pay") },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                BalanceCard(state.balanceCents)
                if (state.messages.isEmpty()) {
                    EmptyInbox()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.messages, key = { it.id }) { SmsRow(it) }
                    }
                }
            }
        }

        // Floating notification overlay, drawn above everything else.
        TransactionBanner(
            message = banner,
            onDismiss = vm::dismissBanner,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    if (!state.isSetUp) {
        BalanceDialog(
            title = "Set your starting balance",
            message = "Enter the M-PESA balance each player starts the game with.",
            confirmLabel = "Start",
            onConfirm = vm::startNewGame,
            onDismiss = null,
        )
    }
    if (showNewGame) {
        BalanceDialog(
            title = "Start a new game?",
            message = "This clears all messages and resets your balance.",
            confirmLabel = "Reset",
            onConfirm = { vm.startNewGame(it); showNewGame = false },
            onDismiss = { showNewGame = false },
        )
    }
    if (showForm) {
        TransactionDialog(onSubmit = vm::submit, onDismiss = { showForm = false })
    }
}

@Composable
private fun BalanceCard(balanceCents: Long) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("M-PESA balance", style = MaterialTheme.typography.labelLarge)
            Text(
                "Ksh ${Money.format(balanceCents)}",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "No messages yet.\nTap Send / Pay to make your first transaction.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SmsRow(message: SmsMessage) {
    val time = remember(message.timestamp) {
        SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(message.timestamp))
    }
    Row(verticalAlignment = Alignment.Top) {
        Avatar(Modifier.padding(top = 2.dp))
        Spacer(Modifier.size(8.dp))
        Column {
            Text(
                "MPESA",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
            Surface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(message.body, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        time,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp).align(Alignment.End),
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(modifier: Modifier = Modifier, size: Int = 36) {
    Box(
        modifier = modifier.size(size.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("M", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

/** Slides down from the top, shows the SMS, then dismisses itself. Tap to dismiss early. */
@Composable
fun TransactionBanner(message: SmsMessage?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Keep the last message so the text doesn't vanish while the exit animation runs.
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
                            Text("MPESA", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
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
private fun TransactionDialog(
    onSubmit: (name: String, amount: String, type: TxType) -> String?,
    onDismiss: () -> Unit,
) {
    var type by rememberSaveable { mutableStateOf(TxType.SENT) }
    var name by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == TxType.SENT) "Send money / pay" else "Receive money") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.SENT,
                        onClick = { type = TxType.SENT; error = null },
                        label = { Text("Pay") },
                    )
                    FilterChip(
                        selected = type == TxType.RECEIVED,
                        onClick = { type = TxType.RECEIVED; error = null },
                        label = { Text("Receive") },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30); error = null },
                    label = { Text(if (type == TxType.SENT) "Recipient name" else "Received from") },
                    placeholder = { Text("Bank, Player 2, Rent") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                error = onSubmit(name, amount, type)
                if (error == null) onDismiss()
            }) { Text(if (type == TxType.SENT) "Pay" else "Receive") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BalanceDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (Long) -> Unit,
    onDismiss: (() -> Unit)?,
) {
    var text by rememberSaveable { mutableStateOf("50000") }
    val cents = Money.parse(text)
    val valid = cents != null && cents >= 0

    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (Money.isValidInput(it)) text = it },
                    label = { Text("Starting balance (Ksh)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(cents!!) }) { Text(confirmLabel) }
        },
        dismissButton = if (onDismiss != null) {
            { TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else null,
    )
}
