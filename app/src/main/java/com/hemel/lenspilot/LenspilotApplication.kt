package com.hemel.lenspilot

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Root cause of the 2026-09-09 crash loop (bugreport-INE-LX2r...17-06-31):
 * "java.lang.IllegalStateException: Default FirebaseApp is not initialized
 * in this process com.hemel.lenspilot" at MainActivity.onCreate, repeating
 * every ~5s with a fresh PID each time (13296, 13502, 13690, 13775, 13831...)
 * right after ActivityManager killed the app at cached priority
 * (am_kill ...,900/901,remove task).
 *
 * Firebase normally self-initializes via its own FirebaseInitProvider
 * (a ContentProvider that every Firebase library merges into the manifest
 * automatically), which the OS is supposed to instantiate before any
 * Activity/Service in the process. Without a custom Application class there
 * was nothing forcing that to happen, and on this device the
 * Huawei/EMUI 9 process-resurrection path that keeps relaunching
 * MainActivity directly after the OOM kill is skipping it — so
 * FirebaseAuth.getInstance() in MainActivity.onCreate blows up before the
 * user ever sees the accessibility-vs-YOLO-fallback decision at all. That's
 * why it looked like "YOLO isn't even trying" — the whole app was
 * crash-looping before reaching that code.
 *
 * Calling initializeApp() here is safe even when auto-init already
 * succeeded (Firebase treats it as a no-op in that case via
 * getApps().isNotEmpty() internally), so this is a pure safety net with no
 * downside — it just guarantees Firebase is ready no matter which
 * component (Activity, the accessibility service, a resurrected task) is
 * first to run in a fresh process.
 */
class LenspilotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w(
                "LenspilotApplication",
                "FirebaseApp auto-init did not run before Application.onCreate — " +
                    "initializing manually (see class kdoc, 2026-09-09 bugreport)"
            )
            FirebaseApp.initializeApp(this)
        }
    }
}
