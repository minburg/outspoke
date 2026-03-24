package dev.brgr.outspoke.ui.keyboard

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner

// Fixed Imports (Kotlin Extension Functions)
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * An [AbstractComposeView] that installs the lifecycle, ViewModel store, and saved-state
 * tree owners that Compose requires but an [InputMethodService] does not provide by default.
 *
 * The service implements all three owner interfaces and passes itself in; this view wires
 * them onto the view tree so that Compose internals (recomposer, viewModel(), etc.) can
 * locate them via [ViewTreeLifecycleOwner] / [ViewTreeViewModelStoreOwner] /
 * [ViewTreeSavedStateRegistryOwner].
 */
class ImeComposeView(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    viewModelStoreOwner: ViewModelStoreOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
) : AbstractComposeView(context) {

    // Backed by mutableStateOf so that a content swap (unlikely but possible)
    // triggers recomposition without needing to detach/reattach the view.
    private var content: (@Composable () -> Unit) by mutableStateOf({})

    init {
        // Updated to use the Kotlin extension functions
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
    }

    /** Replace the hosted composable content. Safe to call before or after attachment. */
    fun setKeyboardContent(newContent: @Composable () -> Unit) {
        content = newContent
    }

    @Composable
    override fun Content() {
        content()
    }
}
