package ir.ghostshare

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RedirectRulesScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onOpenSources: () -> Unit,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    val removal = rememberRemovalState()
    val rules = config.redirectRules.sortedBy { sortKey(it) }

    if (showAdd) {
        AddEntryDialog(
            title = stringResource(R.string.title_add_redirect_rule),
            label = stringResource(R.string.hint_redirect_rule),
            note = stringResource(R.string.note_redirect_rule),
            invalidMessage = stringResource(R.string.msg_invalid_redirect_rule),
            existsMessage = stringResource(R.string.msg_already_exists),
            normalize = { RedirectRules.normalize(it) },
            exists = { it in config.redirectRules },
            onAdd = { rule ->
                update { it.copy(redirectRules = it.redirectRules + rule) }
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text(stringResource(R.string.title_restore_defaults)) },
            text = { Text(stringResource(R.string.msg_restore_redirect_rules)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        update { it.restoreDefaultRedirectRules() }
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
        title = stringResource(R.string.title_redirect_rules),
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenSources) {
                Icon(
                    painter = painterResource(R.drawable.ic_sync),
                    contentDescription = stringResource(R.string.cd_update)
                )
            }
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
                text = { Text(stringResource(R.string.title_add_redirect_rule)) }
            )
        }
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)
            ) {
                item(key = "note") {
                    Text(
                        text = stringResource(R.string.desc_redirect_rules_screen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                if (rules.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.label_empty_list),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                        )
                    }
                }
                items(rules, key = { it }) { rule ->
                    RemovableRow(removing = removal.isRemoving(rule)) {
                        ListItem(
                            colors = ghostListItemColors(),
                            headlineContent = {
                                Text(
                                    text = rule,
                                    maxLines = 4,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            leadingContent = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_link),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        removal.remove(rule) {
                                            update { it.copy(redirectRules = it.redirectRules - rule) }
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.cd_remove)
                                    )
                                }
                            },
                            modifier = Modifier.ghostListRow()
                        )
                    }
                }
            }
        }
    }
}

private fun sortKey(rule: String): String =
    rule.removePrefix("^").removePrefix("https?://").removePrefix("(?:[a-z0-9-]+\\.)*?").removePrefix("(?:[a-z0-9.-]*\\.)?")
