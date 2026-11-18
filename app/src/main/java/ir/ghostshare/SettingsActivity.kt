package ir.ghostshare

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

enum class SettingsScreen { HOME, PICK_PER_APP, PICK_BYPASS, PARAMETERS, EXCEPTIONS, PARAMETER_SOURCES, EXCEPTION_SOURCES, SITE_RULES, SITE_RULE_SOURCES, REDIRECT_RULES, REDIRECT_SOURCES, LINK_TEST }

private val SettingsScreen.depth: Int
    get() = when (this) {
        SettingsScreen.HOME -> 0
        SettingsScreen.PARAMETER_SOURCES, SettingsScreen.EXCEPTION_SOURCES, SettingsScreen.SITE_RULE_SOURCES, SettingsScreen.REDIRECT_SOURCES -> 2
        else -> 1
    }

private val SettingsScreen.parent: SettingsScreen
    get() = when (this) {
        SettingsScreen.PARAMETER_SOURCES -> SettingsScreen.PARAMETERS
        SettingsScreen.EXCEPTION_SOURCES -> SettingsScreen.EXCEPTIONS
        SettingsScreen.SITE_RULE_SOURCES -> SettingsScreen.SITE_RULES
        SettingsScreen.REDIRECT_SOURCES -> SettingsScreen.REDIRECT_RULES
        else -> SettingsScreen.HOME
    }

class SettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startScreen = try {
            SettingsScreen.valueOf(intent.getStringExtra(EXTRA_START_SCREEN).orEmpty())
        } catch (_: IllegalArgumentException) {
            SettingsScreen.HOME
        }
        setContent {
            GhostTheme {
                SettingsApp(startScreen = startScreen, onExit = { finish() })
            }
        }
        UiUtils.setupSystemBars(this)
    }

    companion object {
        const val EXTRA_START_SCREEN = "start_screen"
    }
}

private const val TRANSITION_MS = 240
private const val ENTER_FADE_MS = 160
private const val ENTER_FADE_DELAY_MS = 60
private const val EXIT_FADE_MS = 90

private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private fun AnimatedContentTransitionScope<SettingsScreen>.settingsTransition(): ContentTransform {
    val direction = if (targetState.depth > initialState.depth) {
        AnimatedContentTransitionScope.SlideDirection.Start
    } else {
        AnimatedContentTransitionScope.SlideDirection.End
    }
    val slideSpec = tween<IntOffset>(TRANSITION_MS, easing = EmphasizedEasing)
    val enter = slideIntoContainer(direction, slideSpec, initialOffset = { it / 10 }) +
        fadeIn(tween(ENTER_FADE_MS, delayMillis = ENTER_FADE_DELAY_MS, easing = LinearOutSlowInEasing))
    val exit = slideOutOfContainer(direction, slideSpec, targetOffset = { it / 10 }) +
        fadeOut(tween(EXIT_FADE_MS, easing = FastOutLinearInEasing))
    return ContentTransform(enter, exit, 0f, null)
}

@Composable
fun SettingsApp(startScreen: SettingsScreen = SettingsScreen.HOME, onExit: () -> Unit) {
    val context = LocalContext.current
    val configState = remember { mutableStateOf(SettingsStore.load(context)) }
    val config = configState.value
    var screen by rememberSaveable { mutableStateOf(startScreen) }

    val update = remember(context) {
        { transform: (GhostConfig) -> GhostConfig ->
            val next = transform(configState.value)
            configState.value = next
            SettingsStore.saveAsync(context, next)
        }
    }

    BackHandler(enabled = screen != SettingsScreen.HOME) {
        if (screen == startScreen) onExit() else screen = screen.parent
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { settingsTransition() },
        label = "settings-navigation"
    ) { target ->
        when (target) {
            SettingsScreen.HOME -> SettingsHomeScreen(
                config = config,
                update = update,
                onExit = onExit,
                onNavigate = { screen = it }
            )
            SettingsScreen.PICK_PER_APP -> AppPickerScreen(
                bypass = false,
                selected = config.perAppPackages,
                onToggle = { pkg ->
                    update { current ->
                        val next = if (pkg in current.perAppPackages) current.perAppPackages - pkg else current.perAppPackages + pkg
                        current.copy(perAppPackages = next)
                    }
                },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.PICK_BYPASS -> AppPickerScreen(
                bypass = true,
                selected = config.bypassPackages,
                onToggle = { pkg ->
                    update { current ->
                        val next = if (pkg in current.bypassPackages) current.bypassPackages - pkg else current.bypassPackages + pkg
                        current.copy(bypassPackages = next)
                    }
                },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.PARAMETERS -> ParametersScreen(
                config = config,
                update = update,
                onOpenSources = { screen = SettingsScreen.PARAMETER_SOURCES },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.EXCEPTIONS -> ExceptionsScreen(
                config = config,
                update = update,
                onOpenSources = { screen = SettingsScreen.EXCEPTION_SOURCES },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.SITE_RULES -> SiteRulesScreen(
                config = config,
                update = update,
                onOpenSources = { screen = SettingsScreen.SITE_RULE_SOURCES },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.REDIRECT_RULES -> RedirectRulesScreen(
                config = config,
                update = update,
                onOpenSources = { screen = SettingsScreen.REDIRECT_SOURCES },
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.LINK_TEST -> LinkTestScreen(
                config = config,
                onBack = { if (startScreen == SettingsScreen.LINK_TEST) onExit() else screen = SettingsScreen.HOME }
            )
            SettingsScreen.PARAMETER_SOURCES -> SourcesScreen(
                kind = SourceKind.PARAMETERS,
                update = update,
                onBack = { screen = SettingsScreen.PARAMETERS }
            )
            SettingsScreen.EXCEPTION_SOURCES -> SourcesScreen(
                kind = SourceKind.EXCEPTIONS,
                update = update,
                onBack = { screen = SettingsScreen.EXCEPTIONS }
            )
            SettingsScreen.SITE_RULE_SOURCES -> SourcesScreen(
                kind = SourceKind.SITE_RULES,
                update = update,
                onBack = { screen = SettingsScreen.SITE_RULES }
            )
            SettingsScreen.REDIRECT_SOURCES -> SourcesScreen(
                kind = SourceKind.REDIRECTS,
                update = update,
                onBack = { screen = SettingsScreen.REDIRECT_RULES }
            )
        }
    }
}
