package ir.ghostshare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun ExceptionsScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onBack: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val websites = tab == 0

    val entries = if (websites) config.exceptionDomains.sorted() else config.exceptionParameters.sorted()

    if (showAdd) {
        if (websites) {
            AddEntryDialog(
                title = stringResource(R.string.title_add_domain),
                label = stringResource(R.string.hint_domain),
                note = null,
                invalidMessage = stringResource(R.string.msg_invalid_domain),
                existsMessage = stringResource(R.string.msg_already_exists),
                normalize = { GhostConfig.normalizeDomain(it) },
                exists = { it in config.exceptionDomains },
                onAdd = { domain ->
                    update { it.copy(exceptionDomains = it.exceptionDomains + domain) }
                    showAdd = false
                },
                onDismiss = { showAdd = false }
            )
        } else {
            AddEntryDialog(
                title = stringResource(R.string.title_add_parameter),
                label = stringResource(R.string.hint_parameter_name),
                note = stringResource(R.string.note_parameter_wildcard),
                invalidMessage = stringResource(R.string.msg_invalid_parameter),
                existsMessage = stringResource(R.string.msg_already_exists),
                normalize = { GhostConfig.normalizeParameter(it) },
                exists = { it in config.exceptionParameters },
                onAdd = { name ->
                    update { it.copy(exceptionParameters = it.exceptionParameters + name) }
                    showAdd = false
                },
                onDismiss = { showAdd = false }
            )
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.title_exceptions),
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null
                    )
                },
                text = {
                    Text(stringResource(if (websites) R.string.title_add_domain else R.string.btn_add_parameter))
                }
            )
        }
    ) { inner ->
        Column(modifier = Modifier.padding(inner).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = websites,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.tab_websites)) }
                )
                Tab(
                    selected = !websites,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.tab_parameters)) }
                )
            }
            Text(
                text = stringResource(
                    if (websites) R.string.desc_exceptions_websites else R.string.desc_exceptions_parameters
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            Box(modifier = Modifier.fillMaxSize()) {
                if (entries.isEmpty()) {
                    Text(
                        text = stringResource(R.string.label_empty_list),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(entries, key = { it }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry, maxLines = 1) },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        update {
                                            if (websites) {
                                                it.copy(exceptionDomains = it.exceptionDomains - entry)
                                            } else {
                                                it.copy(exceptionParameters = it.exceptionParameters - entry)
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.cd_remove)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
