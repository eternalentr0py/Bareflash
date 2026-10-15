package com.bareflash.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.bareflash.app.R
import com.bareflash.app.ui.theme.LocalMotionSpeed

/**
 * Applies the app's dialog enter / exit animation to the window hosting the
 * current dialog. With animations turned off in Settings the window gets none.
 */
@Composable
fun DialogWindowAnimations() {
    val speed = LocalMotionSpeed.current
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window
            ?.setWindowAnimations(if (speed <= 0f) 0 else R.style.BfDialogAnimation)
    }
}

/**
 * The stock Material 3 alert dialog with the Bareflash enter / exit motion
 * (scale-and-fade in, quick fade out) so every pop-up in the app moves the
 * same way. Drop-in for [AlertDialog].
 */
@Composable
fun NcAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = {
            Column {
                DialogWindowAnimations()
                text?.invoke()
            }
        },
        shape = shape,
        containerColor = containerColor,
        properties = properties
    )
}
