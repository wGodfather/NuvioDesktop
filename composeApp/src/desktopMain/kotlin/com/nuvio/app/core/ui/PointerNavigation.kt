package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isNavigationBackButton() = button == PointerButton.Back
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun PointerEvent.isNavigationForwardButton() = button == PointerButton.Forward
