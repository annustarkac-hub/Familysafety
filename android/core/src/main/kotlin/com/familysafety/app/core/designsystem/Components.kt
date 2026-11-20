package com.familysafety.app.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Max content width so layouts stay readable on tablets / landscape. */
val ContentMaxWidth = 640.dp

@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) { content() }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String = "Loading…") {
    Column(
        modifier.fillMaxSize().padding(24.dp).semantics { contentDescription = message },
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(
    title: String, message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Outlined.Inbox,
    actionLabel: String? = null, onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/** Confirmation dialog for sensitive actions (revocation, stopping sharing, disconnecting). */
@Composable
fun ConfirmDialog(
    title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(vertical = 8.dp).semantics { heading() })
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Horizontal bar showing a value relative to a maximum, with an accessible description. */
@Composable
fun UsageBar(label: String, valueText: String, fraction: Float, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label, $valueText" }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(4.dp))
        val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), animationSpec = tween(600), label = "usageBar")
        LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PasswordField(
    value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    isError: Boolean = false, supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done, onImeAction: () -> Unit = {},
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "Hide password" else "Show password")
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
