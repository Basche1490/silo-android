package org.siloserver.silo.tv.ui.screens.player.external

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.siloserver.silo.common.player.external.ZidooHandoffLog
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.screens.player.TvPlayerChrome

/**
 * Settings → External Player → Hand-off Log: the box, how Silo's last runs
 * ended (Android 11 and later say why: a crash, low memory, the firmware),
 * then the latest hand-off step by step. On a Zidoo without adb this is the
 * way to see what happened; a photo of it is enough to diagnose most
 * problems. Up and Down scroll, Back closes it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvHandoffLogDialog(
    onDismiss: () -> Unit,
    log: ZidooHandoffLog = koinInject(),
) {
    val context = LocalContext.current
    val lines = remember {
        listOf("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}") +
            lastExits(context) +
            "" +
            log.lines().ifEmpty { listOf("No hand-off recorded yet.") }
    }
    val focus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(TvPlayerChrome.Card)
                .border(1.dp, TvPlayerChrome.PanelStroke, RoundedCornerShape(18.dp))
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyUp && (event.key == Key.Back || event.key == Key.Escape) -> {
                            onDismiss()
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                            scrollScope.launch {
                                scrollState.animateScrollTo((scrollState.value + SCROLL_STEP_PX).coerceAtMost(scrollState.maxValue))
                            }
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                            scrollScope.launch {
                                scrollState.animateScrollTo((scrollState.value - SCROLL_STEP_PX).coerceAtLeast(0))
                            }
                            true
                        }
                        else -> false
                    }
                }
                .then(rememberTvDialogInitialFocus(focus)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
                    .focusRequester(focus)
                    .focusable()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Hand-off Log",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** How Silo's last few runs ended, newest first, in a line each. */
private fun lastExits(context: Context): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        exitsSinceAndroid11(context)
    } else {
        listOf("How Silo's last runs ended: Android ${Build.VERSION.RELEASE} doesn't say.")
    }

@RequiresApi(Build.VERSION_CODES.R)
private fun exitsSinceAndroid11(context: Context): List<String> {
    val manager = context.getSystemService(ActivityManager::class.java)
        ?: return listOf("How Silo's last runs ended: unavailable.")
    val exits = try {
        manager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
    } catch (e: RuntimeException) {
        return listOf("How Silo's last runs ended: unavailable ($e).")
    }
    if (exits.isEmpty()) return listOf("How Silo's last runs ended: nothing recorded.")
    val time = SimpleDateFormat("MMM d HH:mm:ss", Locale.ROOT)
    return listOf("How Silo's last runs ended, newest first:") + exits.map { exit ->
        val description = exit.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        "  ${time.format(Date(exit.timestamp))}  ${exitReason(exit.reason)} while ${importance(exit.importance)}$description"
    }
}

private fun exitReason(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_EXIT_SELF -> "closed itself"
    ApplicationExitInfo.REASON_SIGNALED -> "stopped by a signal (often the firmware)"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
    ApplicationExitInfo.REASON_CRASH -> "crashed"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code"
    ApplicationExitInfo.REASON_ANR -> "stopped responding"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "stopped for using too much"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped"
    ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "stopped with another app"
    ApplicationExitInfo.REASON_OTHER -> "closed by the system"
    ApplicationExitInfo.REASON_FREEZER -> "frozen in the background"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "updated"
    else -> "ended (reason $reason)"
}

private fun importance(importance: Int): String = when {
    importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "in front"
    importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "behind, with its keep-alive service"
    importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "visible or perceptible"
    importance < RunningAppProcessInfo.IMPORTANCE_CACHED -> "in the background"
    importance < RunningAppProcessInfo.IMPORTANCE_GONE -> "idle in the background"
    else -> "gone (importance $importance)"
}

private const val MAX_EXITS = 5
private const val SCROLL_STEP_PX = 240
