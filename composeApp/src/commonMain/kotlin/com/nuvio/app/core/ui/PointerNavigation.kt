package com.nuvio.app.core.ui

import androidx.compose.ui.input.pointer.PointerEvent

// Mouse navigation buttons have a desktop-specific event API.
internal expect fun PointerEvent.isNavigationBackButton(): Boolean
internal expect fun PointerEvent.isNavigationForwardButton(): Boolean
