package ir.ghostshare

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_IMPORT_BYTES = 2_000_000

private fun readLimited(stream: InputStream, limit: Int): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun failureMessage(context: Context, reason: ConfigJson.Failure): String = context.getString(
    when (reason) {
        ConfigJson.Failure.NOT_JSON -> R.string.err_import_not_json
        ConfigJson.Failure.WRONG_STRUCTURE -> R.string.err_import_structure
        ConfigJson.Failure.UNSUPPORTED_VERSION -> R.string.err_import_version
    }
)

@Composable
fun SettingsHomeScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onExit: () -> Unit,
    onNavigate: (SettingsScreen) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<ConfigJson.ImportData?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    try {
                        val bytes = ConfigJson.export(config).toByteArray(Charsets.UTF_8)
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                        if (stream == null) {
                            false
                        } else {
                            stream.use { it.write(bytes) }
                            true
                        }
                    } catch (_: Throwable) {
                        false
                    }
                }
                snackbar.showSnackbar(
                    context.getString(if (saved) R.string.msg_export_done else R.string.msg_export_failed)
                )
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        val stream = context.contentResolver.openInputStream(uri)
                        if (stream == null) {
                            null
                        } else {
                            stream.use { readLimited(it, MAX_IMPORT_BYTES) }?.toString(Charsets.UTF_8)
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }
                if (text == null) {
                    snackbar.showSnackbar(context.getString(R.string.err_import_read))
                } else {
                    when (val result = ConfigJson.parse(text)) {
                        is ConfigJson.ParseResult.Error ->
                            snackbar.showSnackbar(failureMessage(context, result.reason))
                        is ConfigJson.ParseResult.Success -> pendingImport = result.data
                    }
                }
            }
        }
    }

    val pending = pendingImport
    if (pending != null) {
        val applyImport = { overwrite: Boolean ->
            update { current ->
                if (overwrite) ConfigJson.overwrite(current, pending) else ConfigJson.merge(current, pending)
            }
            pendingImport = null
            scope.launch {
                var message = context.getString(R.string.msg_import_done, pending.total)
                if (pending.skipped > 0) {
                    message += "\n" + context.getString(R.string.msg_import_skipped, pending.skipped)
                }
                snackbar.showSnackbar(message)
            }
        }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.title_import_dialog)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.msg_import_dialog),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.msg_import_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { applyImport(true) }) {
                    Text(stringResource(R.string.btn_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { applyImport(false) }) {
                    Text(stringResource(R.string.btn_no))
                }
            }
        )
    }

    val parameterCount = config.effectiveParameters().size
    val exceptionCount = config.exceptionDomains.size

    ScreenScaffold(
        title = stringResource(R.string.title_advanced_settings),
        onBack = onExit,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            SectionTitle(stringResource(R.string.title_section_per_app))

            SwitchCard(
                icon = R.drawable.ic_apps,
                title = stringResource(R.string.title_per_app_enable),
                description = stringResource(R.string.desc_per_app_enable),
                checked = config.perAppEnabled,
                onCheckedChange = { on -> update { it.copy(perAppEnabled = on) } },
                footer = {
                    ValueRow(
                        title = stringResource(R.string.label_choose_apps),
                        value = countLabel(config.perAppPackages.size),
                        onClick = { onNavigate(SettingsScreen.PICK_PER_APP) }
                    )
                }
            )
            Spacer(Modifier.height(12.dp))
            SwitchCard(
                icon = R.drawable.ic_block,
                title = stringResource(R.string.title_bypass_mode),
                description = stringResource(R.string.desc_bypass_mode),
                checked = config.bypassEnabled,
                onCheckedChange = { on -> update { it.copy(bypassEnabled = on) } },
                footer = {
                    ValueRow(
                        title = stringResource(R.string.label_choose_apps),
                        value = countLabel(config.bypassPackages.size),
                        onClick = { onNavigate(SettingsScreen.PICK_BYPASS) }
                    )
                }
            )

            SectionTitle(stringResource(R.string.title_section_links))
            SwitchCard(
                icon = R.drawable.ic_link,
                title = stringResource(R.string.title_redirect),
                description = stringResource(R.string.desc_redirect),
                checked = config.redirectUnwrap,
                onCheckedChange = { on -> update { it.copy(redirectUnwrap = on) } }
            )

            SectionTitle(stringResource(R.string.title_section_data))
            ActionCard(
                icon = R.drawable.ic_tune,
                title = stringResource(R.string.title_my_parameters),
                description = stringResource(R.string.desc_my_parameters),
                value = parameterCount.toString(),
                onClick = { onNavigate(SettingsScreen.PARAMETERS) }
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = R.drawable.ic_public,
                title = stringResource(R.string.title_exceptions),
                description = stringResource(R.string.desc_exceptions),
                value = exceptionCount.toString(),
                onClick = { onNavigate(SettingsScreen.EXCEPTIONS) }
            )

            SectionTitle(stringResource(R.string.title_section_backup))
            ActionCard(
                icon = R.drawable.ic_file_download,
                title = stringResource(R.string.title_export_json),
                description = stringResource(R.string.desc_export_json),
                onClick = { exportLauncher.launch("ghostshare-settings.json") }
            )
            Spacer(Modifier.height(12.dp))
            ActionCard(
                icon = R.drawable.ic_file_upload,
                title = stringResource(R.string.title_import_json),
                description = stringResource(R.string.desc_import_json),
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
            )
        }
    }
}

@Composable
private fun countLabel(count: Int): String =
    if (count == 0) stringResource(R.string.label_no_apps_selected) else stringResource(R.string.fmt_apps_selected, count)
