package ir.ghostshare

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class SettingsScreen { HOME, PICK_PER_APP, PICK_BYPASS, PARAMETERS, EXCEPTIONS }

class SettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GhostTheme {
                SettingsApp(onExit = { finish() })
            }
        }
        UiUtils.setupSystemBars(this)
    }
}

private const val TRANSITION_MS = 340

@Composable
fun SettingsApp(onExit: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(SettingsStore.load(context)) }
    var screen by rememberSaveable { mutableStateOf(SettingsScreen.HOME) }

    val update: ((GhostConfig) -> GhostConfig) -> Unit = { transform ->
        config = SettingsStore.update(context, transform)
    }

    BackHandler(enabled = screen != SettingsScreen.HOME) { screen = SettingsScreen.HOME }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val forward = targetState != SettingsScreen.HOME
            if (forward) {
                (slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = tween(TRANSITION_MS, easing = FastOutSlowInEasing),
                    initialOffset = { it / 4 }
                ) + fadeIn(tween(TRANSITION_MS, delayMillis = 60)))
                    .togetherWith(
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Start,
                            animationSpec = tween(TRANSITION_MS, easing = FastOutSlowInEasing),
                            targetOffset = { it / 6 }
                        ) + fadeOut(tween(TRANSITION_MS / 2))
                    )
            } else {
                (slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = tween(TRANSITION_MS, easing = FastOutSlowInEasing),
                    initialOffset = { it / 6 }
                ) + fadeIn(tween(TRANSITION_MS, delayMillis = 60)))
                    .togetherWith(
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.End,
                            animationSpec = tween(TRANSITION_MS, easing = FastOutSlowInEasing),
                            targetOffset = { it / 4 }
                        ) + fadeOut(tween(TRANSITION_MS / 2))
                    )
            }
        },
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
                onBack = { screen = SettingsScreen.HOME }
            )
            SettingsScreen.EXCEPTIONS -> ExceptionsScreen(
                config = config,
                update = update,
                onBack = { screen = SettingsScreen.HOME }
            )
        }
    }
}
