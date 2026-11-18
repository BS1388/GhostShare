package ir.ghostshare

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourcesScreen(
    kind: SourceKind,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var sources by remember { mutableStateOf(SourceStore.sources(context, kind)) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ListSource?>(null) }

    val titleRes = when (kind) {
        SourceKind.PARAMETERS -> R.string.title_update_params
        SourceKind.EXCEPTIONS -> R.string.title_update_exceptions
        SourceKind.SITE_RULES -> R.string.title_update_site_rules
        SourceKind.REDIRECTS -> R.string.title_update_redirects
    }
    val descRes = when (kind) {
        SourceKind.PARAMETERS -> R.string.desc_update_params
        SourceKind.EXCEPTIONS -> R.string.desc_update_exceptions
        SourceKind.SITE_RULES -> R.string.desc_update_site_rules
        SourceKind.REDIRECTS -> R.string.desc_update_redirects
    }
    val noteRes = when (kind) {
        SourceKind.PARAMETERS -> R.string.note_source_url_params
        SourceKind.EXCEPTIONS -> R.string.note_source_url_exceptions
        SourceKind.SITE_RULES -> R.string.note_source_url_site_rules
        SourceKind.REDIRECTS -> R.string.note_source_url_redirects
    }

    fun runUpdate(source: ListSource) {
        if (busyId != null) return
        busyId = source.id
        scope.launch {
            val message = try {
                val parsed = withContext(Dispatchers.IO) {
                    SourceUpdater.parse(source, kind, ListFetcher.fetch(source.url))
                }
                var result = UpdateResult(0, 0)
                update { current ->
                    val (next, applied) = SourceUpdater.apply(kind, current, parsed)
                    result = applied
                    next
                }
                SourceStore.markUpdated(context, kind, source.id)
                when {
                    result.found == 0 -> context.getString(R.string.msg_update_empty, source.name)
                    result.added > 0 -> context.getString(R.string.msg_update_added, source.name, result.added)
                    else -> context.getString(R.string.msg_update_none, source.name)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FetchException) {
                when (e.reason) {
                    FetchException.Reason.HTTP -> context.getString(R.string.err_update_http, e.httpCode)
                    FetchException.Reason.TOO_LARGE -> context.getString(R.string.err_update_large)
                    FetchException.Reason.UNRECOGNIZED -> context.getString(R.string.err_update_format)
                    FetchException.Reason.NETWORK -> context.getString(R.string.err_update_network)
                }
            } catch (_: Exception) {
                context.getString(R.string.err_update_network)
            }
            busyId = null
            refresh++
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    if (showAdd) {
        AddSourceDialog(
            note = stringResource(noteRes),
            existing = sources,
            onAdd = { name, url ->
                val added = SourceStore.addCustom(context, kind, name, url)
                showAdd = false
                if (added != null) {
                    sources = SourceStore.sources(context, kind)
                    runUpdate(added)
                }
            },
            onDismiss = { showAdd = false }
        )
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.title_remove_source)) },
            text = { Text(stringResource(R.string.msg_remove_source, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SourceStore.removeCustom(context, kind, target.id)
                        sources = SourceStore.sources(context, kind)
                        removeTarget = null
                    }
                ) {
                    Text(stringResource(R.string.cd_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    ScreenScaffold(
        title = stringResource(titleRes),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.btn_add_source)) }
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
                        text = stringResource(descRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                }
                itemsIndexed(sources, key = { _, source -> source.id }) { index, source ->
                    val busy = busyId == source.id
                    val updated = remember(refresh, source.id) { SourceStore.lastUpdated(context, kind, source.id) }
                    val status = if (updated > 0L) {
                        stringResource(
                            R.string.label_last_updated,
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(updated))
                        )
                    } else {
                        stringResource(R.string.label_never_updated)
                    }
                    ListItem(
                        colors = ghostListItemColors(),
                        headlineContent = { Text(source.name, maxLines = 1) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = source.url.removePrefix("https://"),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(text = status, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        leadingContent = {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_sync),
                                    contentDescription = stringResource(R.string.cd_update),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        trailingContent = {
                            Row {
                                IconButton(
                                    onClick = {
                                        SourceStore.move(context, kind, source.id, -1)
                                        sources = SourceStore.sources(context, kind)
                                    },
                                    enabled = index > 0,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_upward),
                                        contentDescription = stringResource(R.string.cd_move_up)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        SourceStore.move(context, kind, source.id, 1)
                                        sources = SourceStore.sources(context, kind)
                                    },
                                    enabled = index < sources.lastIndex,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_arrow_downward),
                                        contentDescription = stringResource(R.string.cd_move_down)
                                    )
                                }
                                if (!source.builtIn) {
                                    IconButton(
                                        onClick = { removeTarget = source },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_delete),
                                            contentDescription = stringResource(R.string.cd_remove)
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .animateItemPlacement()
                            .ghostListRow()
                            .clickable(enabled = busyId == null) { runUpdate(source) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddSourceDialog(
    note: String,
    existing: List<ListSource>,
    onAdd: (name: String, url: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidMessage = stringResource(R.string.msg_invalid_source_url)
    val existsMessage = stringResource(R.string.msg_already_exists)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_add_source)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hint_source_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.hint_source_url)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: note) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val normalized = SourceCatalog.normalizeUrl(url)
                    if (normalized == null) {
                        error = invalidMessage
                    } else if (existing.any { it.url.equals(normalized, ignoreCase = true) }) {
                        error = existsMessage
                    } else {
                        onAdd(name, url)
                    }
                }
            ) {
                Text(stringResource(R.string.btn_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
