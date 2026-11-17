# v160 — CI fix
GitHub Actions failed at `android-actions/setup-android@v3` ("7 of 8 SDK package licenses not accepted").
Removed that action; the ubuntu runner already has the Android SDK ($ANDROID_HOME), so the workflow now
accepts the licenses itself with the preinstalled sdkmanager. No app/Kotlin/app.py changes.
