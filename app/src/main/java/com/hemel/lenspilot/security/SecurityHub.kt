package com.hemel.lenspilot.security

import android.content.Context

/**
 * সেশন ৩: নিরাপত্তা-স্তরের (Redactor, VaultRefs, PermissionGate) জন্য অ্যাপ-কনটেক্সটের একটাই ধারক।
 * LenspilotApplication.onCreate-এ একবার [init]। Context না থাকা অবস্থায় (init-এর আগে) সব কিছু নিরাপদে
 * "কিছু না করে" ফেরে — ক্র্যাশ নয়।
 */
object SecurityHub {
    @Volatile
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun context(): Context? = app
}
