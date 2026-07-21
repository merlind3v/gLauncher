package app.glauncher.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.glauncher.R
import app.glauncher.data.Constants
import app.glauncher.data.Prefs
import app.glauncher.ui.AppLockActivity

class MyAccessibilityService : AccessibilityService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onServiceConnected() {
        Prefs(applicationContext).lockModeOn = true
        AppLockState.clear()
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            try {
                handleWindowStateChanged(event)
            } catch (e: Exception) {
                // Never let a foreground-app-lock bug crash the accessibility service.
            }
            return
        }

        try {
            val source: AccessibilityNodeInfo = event.source ?: return
            if (source.className != "android.widget.FrameLayout") return

            when (source.contentDescription) {
                getString(R.string.lock_layout_description) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                getString(R.string.recents_layout_description) -> {
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }
        } catch (e: Exception) {
            return
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (pkg == applicationContext.packageName) {
            // Foreground came back to Olauncher itself (home, settings, or the lock screen).
            AppLockState.clear()
            return
        }

        val prefs = Prefs(applicationContext)
        if (!prefs.appLockEnabled) return

        val key = "$pkg|${Process.myUserHandle()}"
        if (key !in prefs.lockedApps) {
            AppLockState.clear()
            return
        }
        if (AppLockState.isUnlocked(key)) return
        if (!AppLockState.tryMarkPending(key)) return

        startActivity(Intent(this, AppLockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Constants.Key.APP_LOCK_PACKAGE, pkg)
            putExtra(Constants.Key.APP_LOCK_USER, Process.myUserHandle())
            putExtra(Constants.Key.APP_LOCK_MODE, Constants.AppLockMode.REAUTH)
        })
    }

    override fun onInterrupt() {

    }
}