package com.huigu.phone10.mobile

/** Only the WindowManager attachment boundary; never owns audio. */
class Phone10OverlayLifecycle(private val onVisible: (Boolean) -> Unit) {
    private var remove: (() -> Unit)? = null
    val visible: Boolean get() = remove != null

    fun attached(removeView: () -> Unit) {
        remove = removeView
        onVisible(true)
    }

    fun detached() {
        remove = null
        onVisible(false)
    }

    fun hide() {
        val action = remove ?: return
        detached()
        try { action() }
        catch (_: IllegalArgumentException) { /* System already removed this view. */ }
        catch (_: SecurityException) { /* Overlay permission was revoked. */ }
    }

    fun move(updateLayout: () -> Unit) {
        if (!visible) return
        try { updateLayout() }
        catch (_: IllegalArgumentException) { hide() }
        catch (_: SecurityException) { hide() }
    }
}
