package ir.ghostshare

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(
    val packageName: String,
    val label: String,
    val hiddenSystem: Boolean
)

private object IconCache {
    private val cache = LruCache<String, ImageBitmap>(256)

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(context: Context, packageName: String): ImageBitmap? {
        val cached = cache.get(packageName)
        if (cached != null) return cached
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val size = 96
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            val image = bitmap.asImageBitmap()
            cache.put(packageName, image)
            image
        } catch (_: Throwable) {
            null
        }
    }
}

private suspend fun loadApps(context: Context, chosen: Set<String>): List<AppEntry> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val entries = try {
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName }
                .map { info ->
                    val systemFlag = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val updatedSystem = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val launchable = try {
                        pm.getLaunchIntentForPackage(info.packageName) != null
                    } catch (_: Throwable) {
                        false
                    }
                    AppEntry(
                        packageName = info.packageName,
                        label = info.loadLabel(pm).toString(),
                        hiddenSystem = systemFlag && !updatedSystem && !launchable
                    )
                }
        } catch (_: Throwable) {
            emptyList()
        }
        entries.sortedWith(
            compareByDescending<AppEntry> { it.packageName in chosen }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
    }

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = IconCache.get(packageName), key1 = packageName) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { IconCache.load(context, packageName) }
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier)
    }
}

@Composable
fun AppPickerScreen(
    bypass: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = loadApps(context, selected)
    }

    val loaded = apps
    val needle = query.trim().lowercase(Locale.ROOT)
    val visible = loaded?.filter { app ->
        val byType = showSystem || !app.hiddenSystem || app.packageName in selected
        val byQuery = needle.isEmpty() ||
            app.label.lowercase(Locale.ROOT).contains(needle) ||
            app.packageName.lowercase(Locale.ROOT).contains(needle)
        byType && byQuery
    }

    ScreenScaffold(
        title = stringResource(if (bypass) R.string.title_pick_bypass else R.string.title_pick_per_app),
        onBack = onBack
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize()) {
            if (visible == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item(key = "header") {
                        Text(
                            text = stringResource(if (bypass) R.string.desc_pick_bypass else R.string.desc_pick_per_app),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                        )
                    }
                    item(key = "search") {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(stringResource(R.string.hint_search_apps)) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_search),
                                    contentDescription = null
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(28.dp),
                            colors = TextFieldDefaults.colors(
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
                    item(key = "system") {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.label_show_system)) },
                            trailingContent = {
                                Switch(checked = showSystem, onCheckedChange = { showSystem = it })
                            },
                            modifier = Modifier.clickable { showSystem = !showSystem }
                        )
                    }
                    if (visible.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.label_no_apps),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp)
                            )
                        }
                    }
                    items(visible, key = { it.packageName }) { app ->
                        ListItem(
                            leadingContent = { AppIcon(app.packageName, Modifier.size(40.dp)) },
                            headlineContent = { Text(app.label, maxLines = 1) },
                            supportingContent = { Text(app.packageName, maxLines = 1) },
                            trailingContent = {
                                Checkbox(checked = app.packageName in selected, onCheckedChange = null)
                            },
                            modifier = Modifier.clickable { onToggle(app.packageName) }
                        )
                    }
                }
            }
        }
    }
}
