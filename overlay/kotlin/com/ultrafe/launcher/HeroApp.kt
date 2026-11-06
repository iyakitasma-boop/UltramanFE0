package com.ultrafe.launcher

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import org.ppsspp.ppsspp.PpssppActivity
import java.lang.ref.WeakReference

/**
 * Application class, created in both the launcher process and the emulator process (":emu").
 *
 * The emulator runs in its own process on purpose:
 *  - PPSSPP keeps its settings in memory after the first run and writes them back on exit,
 *    which would overwrite whatever the launcher saved. A fresh process per game session
 *    makes every settings change take effect.
 *  - Quitting kills the process, so memory is returned and the next start is clean.
 *
 * It also applies runtime tuning aimed at MediaTek phones: sustained performance mode and a
 * 60 Hz display request (both can be switched off in Settings).
 */
class HeroApp : Application() {

    companion object {
        const val EMU_CLASS = "org.ppsspp.ppsspp.PpssppActivity"
        const val ACTION_QUIT_EMU = "com.ultrafe.action.QUIT_EMU"

        private var emuRef: WeakReference<PpssppActivity>? = null

        /** The running emulator screen, or null. Only meaningful inside the emulator process. */
        fun emu(): PpssppActivity? = emuRef?.get()

        private fun isEmu(a: Activity): Boolean = a.javaClass.name == EMU_CLASS
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var quitReceiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!isEmu(activity)) return
                emuRef = WeakReference(activity as PpssppActivity)
                registerQuitReceiver()
                tune(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                if (!isEmu(activity)) return
                GameOverlay.attach(activity as PpssppActivity)
            }

            override fun onActivityResumed(activity: Activity) {
                if (isEmu(activity)) tune(activity)
            }

            override fun onActivityDestroyed(activity: Activity) {
                if (!isEmu(activity) || !activity.isFinishing) return
                emuRef?.clear()
                // PPSSPP has already shut its core down in onDestroy. Ending the process now
                // guarantees the next game session starts clean with the latest settings.
                mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 700L)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }

    /** Lets the launcher ask a still-running (backgrounded) game to quit so it can restart. */
    private fun registerQuitReceiver() {
        if (quitReceiverRegistered) return
        quitReceiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val a = emu()
                if (a != null && !a.isFinishing) a.heroQuit()
                // Failsafe in case the activity never finishes.
                mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, 4000L)
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_QUIT_EMU), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun tune(activity: Activity) {
        try {
            val prefs = Prefs(activity)

            if (prefs.lock60) {
                val lp = activity.window.attributes
                if (lp.preferredRefreshRate != 60f) {
                    lp.preferredRefreshRate = 60f
                    activity.window.attributes = lp
                }
            }

            if (prefs.sustained) {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (pm.isSustainedPerformanceModeSupported) {
                    activity.window.setSustainedPerformanceMode(true)
                }
            }
        } catch (e: Exception) {
            // Tuning is best effort; never take the game down over it.
        }
    }
}
