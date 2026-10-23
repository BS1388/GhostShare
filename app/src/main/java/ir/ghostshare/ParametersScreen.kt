package ir.ghostshare

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ParametersScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onBack: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }

    val entries = remember(config.addedParameters, config.removedParameters) {
        config.effectiveParameters().sorted()
    }
    val needle = query.trim().lowercase()
    val visible = remember(entries, needle) {
        if (needle.isEmpty()) entries else entries.filter { it.contains(needle) }
    }

    if (showAdd) {
        AddEntryDialog(
            title = stringResource(R.string.title_add_parameter),
            label = stringResource(R.string.hint_parameter_name),
            note = stringResource(R.string.note_parameter_wildcard),
            invalidMessage = stringResource(R.string.msg_invalid_parameter),
            existsMessage = stringResource(R.string.msg_already_exists),
            normalize = { GhostConfig.normalizeParameter(it) },
            exists = { it in config.effectiveParameters() },
            onAdd = { name ->
                update { it.addParameter(name) }
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text(stringResource(R.string.title_restore_defaults)) },
            text = { Text(stringResource(R.string.msg_restore_defaults)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        update { it.restoreDefaultParameters() }
                        showRestore = false
                    }
                ) {
                    Text(stringResource(R.string.btn_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestore = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.title_my_parameters),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showRestore = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_restore),
                    contentDescription = stringResource(R.string.title_restore_defaults)
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.btn_add_parameter)) }
            )
        }
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                item(key = "search", contentType = "search") {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.hint_search_parameters)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_search),
                                contentDescription = null
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            errorIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
                if (visible.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Text(
                            text = stringResource(R.string.label_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        )
                    }
                }
                items(visible, key = { it }, contentType = { "parameter" }) { name ->
                    val isDefault = name in DefaultParameters.SET
                    ListItem(
                        colors = ghostListItemColors(),
                        headlineContent = { Text(name, maxLines = 1) },
                        supportingContent = {
                            Text(stringResource(if (isDefault) R.string.label_default else R.string.label_custom))
                        },
                        trailingContent = {
                            IconButton(onClick = { update { it.removeParameter(name) } }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.cd_remove)
                                )
                            }
                        },
                        modifier = Modifier
                            .animateItemPlacement()
                            .ghostListRow()
                    )
                }
            }
        }
    }
}
