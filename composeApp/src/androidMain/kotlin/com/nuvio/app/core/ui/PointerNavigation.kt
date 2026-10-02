package com.nuvio.app.core.ui

import androidx.compose.ui.input.pointer.PointerEvent

// Mobile system back gestures are handled by the platform back dispatcher.
internal actual fun PointerEvent.isNavigationBackButton() = false
internal actual fun PointerEvent.isNavigationForwardButton() = false
