package ir.ghostshare

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp

private data class RuleEditor(val domain: String?)

@Composable
fun SiteRulesScreen(
    config: GhostConfig,
    update: ((GhostConfig) -> GhostConfig) -> Unit,
    onOpenSources: () -> Unit,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<RuleEditor?>(null) }
    var showRestore by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    val removal = rememberRemovalState()
    val rules = remember(config.siteRules) { config.siteRules.entries.sortedBy { it.key } }
    val allParameters = stringResource(R.string.label_all_parameters)

    editor?.let { current ->
        SiteRuleDialog(
            initialDomain = current.domain,
            initialRule = current.domain?.let { config.siteRules[it] },
            existingDomains = config.siteRules.keys,
            onSave = { domain, rule ->
                update { it.copy(siteRules = it.siteRules + (domain to rule)) }
                editor = null
            },
            onDismiss = { editor = null }
        )
    }

    if (showHelp) {
        SiteRulesHelpDialog(onDismiss = { showHelp = false })
    }

    if (showRestore) {
        AlertDialog(
            onDismissRequest = { showRestore = false },
            title = { Text(stringResource(R.string.title_restore_defaults)) },
            text = { Text(stringResource(R.string.msg_restore_site_rules)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        update { it.restoreDefaultSiteRules() }
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
        title = stringResource(R.string.title_site_rules),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showHelp = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_help),
                    contentDescription = stringResource(R.string.cd_help)
                )
            }
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
                onClick = { editor = RuleEditor(null) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add),
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.btn_add_site_rule)) }
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
                        text = stringResource(R.string.desc_site_rules_screen),
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
                items(rules, key = { it.key }) { (domain, rule) ->
                    val isRemoving = removal.isRemoving(domain)
                    RemovableRow(removing = isRemoving) {
                        ListItem(
                            colors = ghostListItemColors(),
                            headlineContent = { Text(domain, maxLines = 1) },
                            supportingContent = {
                                Column {
                                    if (rule.keep.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.fmt_rule_keep, rule.keep.toRuleText(allParameters)),
                                            maxLines = 2,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (rule.strip.isNotEmpty()) {
                                        Text(
                                            text = stringResource(R.string.fmt_rule_strip, rule.strip.toRuleText(allParameters)),
                                            maxLines = 2,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            },
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
                                        removal.remove(domain) {
                                            update { it.copy(siteRules = it.siteRules - domain) }
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = stringResource(R.string.cd_remove)
                                    )
                                }
                            },
                            modifier = Modifier
                                .ghostListRow()
                                .clickable(enabled = !isRemoving) { editor = RuleEditor(domain) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SiteRulesHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_site_rules_help)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HelpSection(R.string.help_rules_what_title, R.string.help_rules_what_body)
                HelpSection(R.string.help_rules_fields_title, R.string.help_rules_fields_body)
                HelpSection(R.string.help_rules_star_title, R.string.help_rules_star_body)
                HelpSection(R.string.help_rules_priority_title, R.string.help_rules_priority_body)
                HelpSection(R.string.help_rules_example_title, R.string.help_rules_example_body)
                HelpSection(R.string.help_rules_test_title, R.string.help_rules_test_body, last = true)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_got_it))
            }
        }
    )
}

@Composable
private fun HelpSection(@StringRes title: Int, @StringRes body: Int, last: Boolean = false) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(body),
        style = MaterialTheme.typography.bodyMedium
    )
    if (!last) Spacer(Modifier.height(16.dp))
}

@Composable
private fun SiteRuleDialog(
    initialDomain: String?,
    initialRule: SiteRule?,
    existingDomains: Set<String>,
    onSave: (String, SiteRule) -> Unit,
    onDismiss: () -> Unit
) {
    var domainText by remember { mutableStateOf(initialDomain.orEmpty()) }
    var keepText by remember { mutableStateOf(initialRule?.keep?.sorted()?.joinToString(", ").orEmpty()) }
    var stripText by remember { mutableStateOf(initialRule?.strip?.sorted()?.joinToString(", ").orEmpty()) }
    var domainError by remember { mutableStateOf<String?>(null) }
    var paramError by remember { mutableStateOf<String?>(null) }

    val invalidDomain = stringResource(R.string.msg_invalid_domain)
    val invalidParameter = stringResource(R.string.msg_invalid_parameter)
    val existsMessage = stringResource(R.string.msg_already_exists)
    val emptyMessage = stringResource(R.string.msg_rule_empty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initialDomain == null) R.string.title_add_site_rule else R.string.title_edit_site_rule))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = domainText,
                    onValueChange = {
                        domainText = it
                        domainError = null
                    },
                    label = { Text(stringResource(R.string.hint_domain)) },
                    singleLine = true,
                    enabled = initialDomain == null,
                    isError = domainError != null,
                    supportingText = { Text(domainError.orEmpty()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keepText,
                    onValueChange = {
                        keepText = it
                        paramError = null
                    },
                    label = { Text(stringResource(R.string.label_rule_keep)) },
                    isError = paramError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = stripText,
                    onValueChange = {
                        stripText = it
                        paramError = null
                    },
                    label = { Text(stringResource(R.string.label_rule_strip)) },
                    isError = paramError != null,
                    supportingText = { Text(paramError ?: stringResource(R.string.note_rule_params)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val domain = if (initialDomain != null) initialDomain else GhostConfig.normalizeDomain(domainText)
                    val keep = parseNames(keepText)
                    val strip = parseNames(stripText)
                    when {
                        domain == null -> domainError = invalidDomain
                        initialDomain == null && domain in existingDomains -> domainError = existsMessage
                        keep == null || strip == null -> paramError = invalidParameter
                        keep.isEmpty() && strip.isEmpty() -> paramError = emptyMessage
                        else -> onSave(domain, SiteRule(keep, strip))
                    }
                }
            ) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}

private fun Set<String>.toRuleText(allLabel: String): String =
    sorted().joinToString(", ") { if (it == "*") allLabel else it }

private fun parseNames(text: String): Set<String>? {
    val result = LinkedHashSet<String>()
    for (part in text.split(',', ';', '\n', ' ')) {
        val token = part.trim()
        if (token.isEmpty()) continue
        result.add(GhostConfig.normalizeParameter(token) ?: return null)
    }
    return result
}
