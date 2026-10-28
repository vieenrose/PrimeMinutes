package studio.primeminutes.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app's one snackbar queue (replaces the Toasts). Every screen shows it: the Session scaffold
 * places its host above the player bar, the other screens use the root host in MainActivity.
 */
object AppSnackbar {
    val host = SnackbarHostState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun show(msg: String) { scope.launch { host.showSnackbar(msg, withDismissAction = false, duration = SnackbarDuration.Long) } }
    fun show(context: Context, @StringRes res: Int) = show(context.getString(res))

    /** A message with an action (Undo); [onResult] gets true when the action was tapped. */
    fun showAction(msg: String, action: String, onResult: (Boolean) -> Unit) {
        scope.launch {
            val r = host.showSnackbar(msg, actionLabel = action, duration = SnackbarDuration.Long)
            onResult(r == SnackbarResult.ActionPerformed)
        }
    }
}
