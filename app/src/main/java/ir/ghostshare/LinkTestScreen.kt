package ir.ghostshare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val INPUT_DEBOUNCE_MS = 150L

@Composable
fun LinkTestScreen(
    config: GhostConfig,
    onBack: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val resultState = produceState<LinkTester.Result>(LinkTester.Result.Empty, input, config) {
        if (input.isBlank()) {
            value = LinkTester.Result.Empty
            return@produceState
        }
        delay(INPUT_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { LinkTester.run(config, input) }
    }
    val result = resultState.value

    ScreenScaffold(
        title = stringResource(R.string.title_link_test),
        onBack = onBack,
        actions = {
            IconButton(onClick = { clipboard.getText()?.text?.let { input = it } }) {
                Icon(
                    painter = painterResource(R.drawable.ic_paste),
                    contentDescription = stringResource(R.string.cd_paste)
                )
            }
            IconButton(onClick = { input = "" }, enabled = input.isNotEmpty()) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.cd_clear)
                )
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.hint_link_test)) },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.msg_test_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(Modifier.height(16.dp))
            when (result) {
                LinkTester.Result.Empty -> Unit
                LinkTester.Result.NoLink -> Message(stringResource(R.string.msg_test_no_link))
                LinkTester.Result.Excluded -> Message(stringResource(R.string.msg_test_excluded))
                LinkTester.Result.Unchanged -> Message(stringResource(R.string.msg_test_nothing))
                is LinkTester.Result.Cleaned -> {
                    ResultCard(stringResource(R.string.label_result)) {
                        SelectionContainer {
                            Text(result.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ResultCard(stringResource(R.string.label_removed)) {
                        val fragmentLabel = stringResource(R.string.label_url_fragment)
                        val redirectLabel = stringResource(R.string.label_redirect_replaced)
                        result.items.forEachIndexed { index, item ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            val lines = ArrayList<String>()
                            if (item.redirected) lines.add(redirectLabel)
                            if (item.removed.isNotEmpty()) lines.add(item.removed.joinToString(", "))
                            if (item.fragmentRemoved) lines.add(fragmentLabel)
                            Text(
                                text = lines.joinToString("\n").ifEmpty { "-" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ResultCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
