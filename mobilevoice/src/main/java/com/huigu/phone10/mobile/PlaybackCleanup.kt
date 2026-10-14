package com.huigu.phone10.mobile

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Core playback ownership is released synchronously, even in a cancelled coroutine. */
internal suspend fun finishPlayback(
    visualDispatcher: CoroutineDispatcher,
    finishFocus: () -> Unit,
    clearPlayer: () -> Unit,
    closePlayer: () -> Unit,
    clearVisual: () -> Unit,
    finishArchive: suspend () -> Unit = {},
) {
    try {
        finishFocus()
    } finally {
        try {
            clearPlayer()
        } finally {
            try {
                closePlayer()
            } finally {
                try { withContext(NonCancellable + visualDispatcher) { clearVisual() } }
                finally { withContext(NonCancellable) { finishArchive() } }
            }
        }
    }
}
