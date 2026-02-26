package at.planqton.fytfm.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import at.planqton.fytfm.MainActivity
import at.planqton.fytfm.data.PresetRepository
import com.android.fmradio.FmNative
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * MediaLibraryService für fytFM.
 * Ermöglicht Car-Launchern und anderen Media-Apps die Verbindung und Steuerung.
 */
@UnstableApi
class FytFMMediaService : MediaLibraryService() {

    companion object {
        private const val TAG = "FytFMMediaService"
        private const val ROOT_ID = "fytfm_root"
        private const val FM_STATIONS_ID = "fm_stations"
        private const val AM_STATIONS_ID = "am_stations"

        // Singleton für Service-Zugriff aus MainActivity
        var instance: FytFMMediaService? = null
            private set
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: FmRadioPlayer
    private lateinit var presetRepository: PresetRepository
    private var fmNative: FmNative? = null

    // Callbacks für Radio-Steuerung (werden von MainActivity gesetzt)
    var onPlayCallback: (() -> Unit)? = null
    var onPauseCallback: (() -> Unit)? = null
    var onSkipNextCallback: (() -> Unit)? = null
    var onSkipPrevCallback: (() -> Unit)? = null
    var onTuneCallback: ((Float) -> Unit)? = null

    // Binder für lokale Bindung
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): FytFMMediaService = this@FytFMMediaService
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Prüfen ob es ein MediaBrowser-Bind ist
        val superBinder = super.onBind(intent)
        return superBinder ?: binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        instance = this

        presetRepository = PresetRepository(this)

        if (FmNative.isLibraryLoaded()) {
            fmNative = FmNative.getInstance()
        }

        // Player erstellen mit Callbacks (SimpleBasePlayer braucht Looper)
        player = FmRadioPlayer(
            looper = Looper.getMainLooper(),
            onPlayRequest = { onPlayCallback?.invoke() ?: defaultPlay() },
            onPauseRequest = { onPauseCallback?.invoke() ?: defaultPause() },
            onSkipNextRequest = { onSkipNextCallback?.invoke() },
            onSkipPrevRequest = { onSkipPrevCallback?.invoke() }
        )

        // Intent für Klick auf Notification
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // MediaLibrarySession erstellen
        mediaSession = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        Log.i(TAG, "MediaSession created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.i(TAG, "onGetSession for: ${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        instance = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // === Public API für MainActivity ===

    /**
     * Aktualisiert die Metadaten (Station-Name, Radio-Text, etc.)
     * Struktur basierend auf transistor-App für Car-Launcher Kompatibilität
     */
    fun updateMetadata(frequency: Float, ps: String?, rt: String?, isAM: Boolean = false) {
        val freqDisplay = if (isAM) {
            "AM ${frequency.toInt()}"
        } else {
            "FM %.1f".format(frequency)
        }
        val stationName = ps ?: freqDisplay

        val metadata = MediaMetadata.Builder()
            .setTitle(rt?.takeIf { it.isNotBlank() } ?: stationName)  // RT oder Stationsname
            .setSubtitle(stationName)                                  // Stationsname
            .setAlbumTitle(stationName)                                // Fallback für manche Widgets
            .setArtist(stationName)                                    // Für manche Car-Launcher
            .setDisplayTitle(freqDisplay)                              // Frequenz
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()

        player.updateMetadata(metadata)
        Log.d(TAG, "Metadata updated: $freqDisplay | $stationName | $rt")
    }

    /**
     * Aktualisiert den Playback-Status
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        player.updatePlaybackState(isPlaying)
        Log.d(TAG, "Playback state: $isPlaying")
    }

    // === Default Callbacks (falls MainActivity nicht verbunden) ===

    private fun defaultPlay() {
        Log.d(TAG, "defaultPlay - unmuting")
        fmNative?.setMute(false)
    }

    private fun defaultPause() {
        Log.d(TAG, "defaultPause - muting")
        fmNative?.setMute(true)
    }

    // === Library Callback für Station-Browsing ===

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            Log.i(TAG, "onConnect: ${controller.packageName}")

            // Erlaubt alle Standard-Commands
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT))
                .add(SessionCommand(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot")

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("fytFM")
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(TAG, "onGetChildren: parentId=$parentId")

            val children = when (parentId) {
                ROOT_ID -> {
                    // Root-Ebene: FM und AM Ordner
                    listOf(
                        createFolderItem(FM_STATIONS_ID, "FM Stations"),
                        createFolderItem(AM_STATIONS_ID, "AM Stations")
                    )
                }
                FM_STATIONS_ID -> {
                    // FM-Stationen laden
                    presetRepository.loadFmStations().map { station ->
                        createStationItem(station.frequency, station.name, false)
                    }
                }
                AM_STATIONS_ID -> {
                    // AM-Stationen laden
                    presetRepository.loadAmStations().map { station ->
                        createStationItem(station.frequency, station.name, true)
                    }
                }
                else -> emptyList()
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            )
        }

        override fun onPlaybackResumption(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onPlaybackResumption")
            // Letzte Station wiederherstellen könnte hier implementiert werden
            return Futures.immediateFailedFuture(UnsupportedOperationException())
        }

        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            Log.d(TAG, "onSetMediaItems: ${mediaItems.firstOrNull()?.mediaId}")

            // Station aus MediaId extrahieren und tunen
            mediaItems.firstOrNull()?.let { item ->
                val mediaId = item.mediaId
                val frequency = mediaId.toFloatOrNull()
                if (frequency != null) {
                    Log.i(TAG, "Tuning to: $frequency")
                    onTuneCallback?.invoke(frequency)
                }
            }

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            )
        }

        private fun createFolderItem(id: String, title: String): MediaItem {
            return MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS)
                        .setIsPlayable(false)
                        .setIsBrowsable(true)
                        .build()
                )
                .build()
        }

        private fun createStationItem(frequency: Float, name: String?, isAM: Boolean): MediaItem {
            val freqDisplay = if (isAM) {
                "AM ${frequency.toInt()}"
            } else {
                "FM %.1f".format(frequency)
            }

            return MediaItem.Builder()
                .setMediaId(frequency.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(name ?: freqDisplay)
                        .setSubtitle(freqDisplay)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .build()
                )
                .build()
        }
    }
}
