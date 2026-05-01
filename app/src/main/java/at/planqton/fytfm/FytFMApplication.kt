package at.planqton.fytfm

import android.app.Application
import android.util.Log
import at.planqton.fytfm.controller.FmNativeAdapter
import at.planqton.fytfm.controller.RadioController
import at.planqton.fytfm.dab.DabTunerManager
import at.planqton.fytfm.dab.MockDabTunerManager
import at.planqton.fytfm.data.PresetRepository
import at.planqton.fytfm.data.settings.AppSettingsRepository
import com.android.fmradio.FmNative

/**
 * Application-scoped Holder für die Audio- und Radio-Komponenten. Liegt
 * über dem Activity-Lifecycle und überlebt darum Theme-/Configuration-
 * Changes inkl. Activity-Recreate. Ohne diesen Wrapper hingen MediaPlayer
 * (DAB-Demo), AudioTrack (echtes DAB) und FM-Native an MainActivity-Lifetime
 * — bei jedem Recreate wäre das Audio kurz weg.
 *
 * Die Controller persistieren ihren Zustand selbst (PresetRepository für
 * Frequenz/Mode, FmAmController liest beim initialize() aus den Prefs).
 * MainActivity hängt sich on `onCreate` per `lifecycleScope.collect` an die
 * SharedFlows von [radioController] und löst die Subscription bei
 * `onDestroy` automatisch wieder. Lambda-basierte Listener (z. B.
 * `rdsManager.setRootRequiredListener`) werden bei jedem Activity-onCreate
 * neu gesetzt — das alte Lambda zeigt auf eine tote Activity, das neue
 * setzt es einfach wieder.
 */
class FytFMApplication : Application() {

    lateinit var presetRepository: PresetRepository
        private set

    lateinit var fmNative: FmNative
        private set

    lateinit var rdsManager: RdsManager
        private set

    val dabTunerManager = DabTunerManager()
    val mockDabTunerManager = MockDabTunerManager()

    /** Null wenn das Gerät kein TWUtil unterstützt (Nicht-FYT-HU). */
    var twUtil: TWUtilHelper? = null
        private set

    lateinit var radioController: RadioController
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i("FytFMApplication", "=== Application onCreate ===")

        // Install crash handler
        CrashHandler.install(this)
        Log.i("FytFMApplication", "Crash handler installed")

        // Seed an application context into the demo DAB backend so
        // playRandomTrack() never hits a null context — it would otherwise
        // happen if setBackend(mock) is called without a prior powerOn()
        // (e.g. lastMode=DAB_DEV restored on app start with autoplay off,
        // then user taps a station).
        mockDabTunerManager.attachApplicationContext(this)

        // Root-Fallback nur aktivieren, wenn der User es explizit erlaubt hat.
        SqlFMServiceClient.setRootFallbackAllowed(
            AppSettingsRepository(this).isAllowRootFallback()
        )

        presetRepository = PresetRepository(this)

        FmNative.initAudio(this)
        fmNative = FmNative.getInstance()
        rdsManager = RdsManager(fmNative)

        // TWUtil ist optional: Nicht-FYT-Geräte haben den Klassenpfad nicht
        // — `isAvailable` deckt das ab. open() liefert für nicht-verfügbare
        // Geräte false; das ist ok, dann läuft FmAmController im non-MCU-Pfad.
        twUtil = TWUtilHelper().also {
            if (it.isAvailable) it.open()
        }

        radioController = RadioController(
            context = this,
            fmNative = FmNativeAdapter(fmNative),
            rdsManager = rdsManager,
            realDabBackend = dabTunerManager,
            mockDabBackend = mockDabTunerManager,
            presetRepository = presetRepository,
            twUtil = twUtil
        )
        radioController.initialize()
    }
}
