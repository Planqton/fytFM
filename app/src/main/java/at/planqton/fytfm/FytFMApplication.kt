package at.planqton.fytfm

import android.app.Application
import android.util.Log

class FytFMApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("FytFMApplication", "=== Application onCreate ===")

        // Install crash handler
        CrashHandler.install(this)
        Log.i("FytFMApplication", "Crash handler installed")
    }
}
