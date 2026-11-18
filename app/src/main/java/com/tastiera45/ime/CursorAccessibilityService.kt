package com.tastiera45.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class CursorAccessibilityService : AccessibilityService() {

    companion object {
        var instance: CursorAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        fun performClickAt(screenX: Float, screenY: Float): Boolean {
            val service = instance ?: return false
            val path = Path().apply {
                moveTo(screenX.coerceAtLeast(1f), screenY.coerceAtLeast(1f))
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()
            return service.dispatchGesture(gesture, null, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
