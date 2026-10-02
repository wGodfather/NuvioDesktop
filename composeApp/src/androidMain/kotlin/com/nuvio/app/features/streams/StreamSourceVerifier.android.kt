package com.nuvio.app.features.streams

internal actual object StreamSourceVerifier {
    actual val enabled = false
    actual suspend fun prepareContext(context: StreamVerificationContext) = context
    actual suspend fun verify(stream: StreamItem, context: StreamVerificationContext): StreamItem? = stream
}
