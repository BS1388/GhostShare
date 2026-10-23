package ir.ghostshare

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ExceptionsScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onBack: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    val domains = config.exceptionDomains.sorted()

    if (showAdd) {
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
                text = { Text(stringResource(R.string.title_add_domain)) }
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
                        text = stringResource(R.string.desc_exceptions_websites),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                if (domains.isEmpty()) {
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
                items(domains, key = { it }) { domain ->
                    ListItem(
                        colors = ghostListItemColors(),
                        headlineContent = { Text(domain, maxLines = 1) },
                        leadingContent = {
                            Icon(
                                painter = painterResource(R.drawable.ic_public),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            IconButton(
                                onClick = {
                                    update { it.copy(exceptionDomains = it.exceptionDomains - domain) }
                                }
                            ) {
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
