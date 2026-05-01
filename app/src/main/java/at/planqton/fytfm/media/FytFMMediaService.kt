package at.planqton.fytfm.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.common.util.UnstableApi
import at.planqton.fytfm.MainActivity
import at.planqton.fytfm.R
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
    private var mediaButtonSession: MediaButtonSession? = null
    private val imageLoader by lazy { MediaMetadataImageLoader(this) }

    // Callbacks für Radio-Steuerung (werden von MainActivity gesetzt)
    var onPlayCallback: (() -> Unit)? = null
    var onPauseCallback: (() -> Unit)? = null
    var onSkipNextCallback: (() -> Unit)? = null
    var onSkipPrevCallback: (() -> Unit)? = null
    var onTuneCallback: ((Float) -> Unit)? = null

    // Binder für lokale Bindung
    private val binder = LocalBinder()

    // Current artwork source for debug UI
    var currentArtworkSource: String = "(none)"
        private set

    // Request counter to prevent race conditions with async URL loading
    private var artworkRequestCounter = java.util.concurrent.atomic.AtomicInteger(0)

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

        // Legacy MediaSession für Media Button Events (Lenkradtasten)
        initMediaButtonSession()
    }

    /**
     * Initialisiert die Legacy MediaSession für Media Button Events
     */
    private fun initMediaButtonSession() {
        mediaButtonSession = MediaButtonSession(
            context = this,
            onNext = {
                Log.i(TAG, "MediaButton: NEXT")
                onSkipNextCallback?.invoke()
            },
            onPrevious = {
                Log.i(TAG, "MediaButton: PREVIOUS")
                onSkipPrevCallback?.invoke()
            },
            onPlayPause = {
                Log.i(TAG, "MediaButton: PLAY_PAUSE")
                // Toggle Play/Pause
                if (player.playWhenReady) {
                    onPauseCallback?.invoke() ?: defaultPause()
                } else {
                    onPlayCallback?.invoke() ?: defaultPlay()
                }
            }
        )
        mediaButtonSession?.initialize()
        Log.i(TAG, "MediaButtonSession initialized")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        Log.i(TAG, "onGetSession for: ${controllerInfo.packageName}")
        return mediaSession
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        instance = null
        mediaButtonSession?.release()
        mediaButtonSession = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // === Public API für MainActivity ===

    /**
     * Aktualisiert die Metadaten (Station-Name, Radio-Text, Cover, etc.)
     * Struktur basierend auf transistor-App für Car-Launcher Kompatibilität
     *
     * @param coverUrl Spotify Cover URL (online)
     * @param localCoverPath Lokaler Pfad zum gecachten Cover (Fallback wenn Cache aktiv)
     * @param radioLogoPath Lokaler Pfad zum Radio-Logo (Fallback wenn kein Spotify-Cover)
     */
    fun updateMetadata(
        frequency: Float,
        ps: String?,
        rt: String?,
        isAM: Boolean = false,
        coverUrl: String? = null,
        localCoverPath: String? = null,
        radioLogoPath: String? = null
    ) {
        val freqDisplay = if (isAM) {
            "AM ${frequency.toInt()}"
        } else {
            "FM %.1f".format(frequency)
        }
        val stationName = ps ?: freqDisplay

        // Title: RT wenn vorhanden, sonst Stationsname
        // Artist/Subtitle: Frequenz (manche Player zeigen Artist, andere Subtitle)
        val displayTitle = rt?.takeIf { it.isNotBlank() } ?: stationName

        // Artwork: Deezer-Cover → Radio-Logo → AM/FM Fallback
        val artworkPath = localCoverPath ?: radioLogoPath
        val fallbackDrawable = if (isAM) R.drawable.placeholder_am else R.drawable.placeholder_fm
        val artworkData: ByteArray? = when {
            !artworkPath.isNullOrBlank() && File(artworkPath).exists() -> {
                currentArtworkSource = artworkPath
                imageLoader.loadImageAsBytes(artworkPath)
            }
            else -> {
                currentArtworkSource = if (isAM) "drawable:placeholder_am" else "drawable:placeholder_fm"
                imageLoader.loadDrawableAsBytes(fallbackDrawable)
            }
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(displayTitle)                                    // RT oder Stationsname
            .setSubtitle(stationName)                                  // Sendername als Untertitel
            .setArtist(stationName)                                    // Sendername (für Player die Artist zeigen)
            .setAlbumTitle(freqDisplay)                                // Frequenz für Fallback
            .setDisplayTitle(displayTitle)                             // RT oder Stationsname (gleich wie title)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                if (artworkData != null) {
                    setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    Log.d(TAG, "Setting artworkData: ${artworkData.size} bytes")
                }
            }
            .build()

        player.updateMetadata(metadata)

        // Legacy MediaButtonSession auch aktualisieren (für manche Car-Launcher)
        // Wenn RT vorhanden (Track gefunden), zeige "Artist - Title", sonst Sendername
        val displayForLegacy = rt?.takeIf { it.isNotBlank() } ?: stationName
        val coverForLegacy = localCoverPath ?: radioLogoPath
        val subtitleForLegacy = if (isAM) {
            "${frequency.toInt()} kHz"
        } else {
            String.format("%.1f MHz", frequency)
        }
        mediaButtonSession?.updateMetadata(displayForLegacy, subtitleForLegacy, coverForLegacy, isAM)

        Log.d(TAG, "Metadata updated: $freqDisplay | $stationName | $rt | data=${artworkData?.size ?: 0}b")
    }

    /**
     * Aktualisiert MediaSession Metadaten für DAB+ Modus
     * @param serviceLabel Service Label (Sendername, z.B. "Ö1")
     * @param ensembleLabel Ensemble Label (z.B. "ORF DAB Wien")
     * @param dls Dynamic Label Segment (Radiotext, z.B. "Nachrichten um 12:00")
     * @param slideshowBitmap MOT Slideshow Bild (falls gesendet)
     * @param radioLogoPath Lokaler Pfad zum Radio-Logo (Fallback wenn kein Slideshow)
     * @param deezerCoverPath Lokaler Pfad oder URL zum Deezer Cover (höchste Priorität wenn vorhanden)
     */
    fun updateDabMetadata(
        serviceLabel: String?,
        ensembleLabel: String? = null,
        dls: String? = null,
        slideshowBitmap: android.graphics.Bitmap? = null,
        radioLogoPath: String? = null,
        deezerCoverPath: String? = null
    ) {
        val stationName = serviceLabel ?: "DAB+"
        val displayTitle = dls?.takeIf { it.isNotBlank() } ?: stationName

        // Check if we need to load from URL (asynchronously)
        val isUrl = !deezerCoverPath.isNullOrBlank() &&
                    (deezerCoverPath.startsWith("http://") || deezerCoverPath.startsWith("https://"))

        if (isUrl) {
            // Increment request counter to prevent race conditions
            val requestId = artworkRequestCounter.incrementAndGet()

            // Sofort Fallback-Artwork setzen, damit kein schwarzes Bild erscheint
            val initialArtwork: ByteArray? = when {
                slideshowBitmap != null -> {
                    currentArtworkSource = "slideshow (loading URL...)"
                    imageLoader.bitmapToByteArray(slideshowBitmap)
                }
                !radioLogoPath.isNullOrBlank() && File(radioLogoPath).exists() -> {
                    currentArtworkSource = "$radioLogoPath (loading URL...)"
                    imageLoader.loadImageAsBytes(radioLogoPath)
                }
                else -> {
                    currentArtworkSource = "drawable:ic_fytfm_dab_plus_light (loading URL...)"
                    imageLoader.loadDrawableAsBytes(R.drawable.ic_fytfm_dab_plus_light)
                }
            }
            updateDabMetadataInternal(stationName, displayTitle, ensembleLabel, initialArtwork, deezerCoverPath, radioLogoPath)

            // Dann URL asynchron laden und Artwork aktualisieren
            Thread {
                val artworkData = imageLoader.loadImageAsBytes(deezerCoverPath!!)
                if (artworkData != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // Only update if this is still the current request (prevent race condition)
                        if (artworkRequestCounter.get() == requestId) {
                            currentArtworkSource = deezerCoverPath
                            updateDabMetadataInternal(stationName, displayTitle, ensembleLabel, artworkData, deezerCoverPath, radioLogoPath)
                        } else {
                            Log.d(TAG, "Ignoring stale artwork request $requestId (current: ${artworkRequestCounter.get()})")
                        }
                    }
                }
            }.start()
        } else {
            // Load locally (synchronous)
            val artworkData: ByteArray? = when {
                // Deezer Cover: Lokaler Pfad
                !deezerCoverPath.isNullOrBlank() && deezerCoverPath.startsWith("/") && File(deezerCoverPath).exists() -> {
                    currentArtworkSource = deezerCoverPath
                    imageLoader.loadImageAsBytes(deezerCoverPath)
                }
                // MOT Slideshow
                slideshowBitmap != null -> {
                    currentArtworkSource = "slideshow"
                    imageLoader.bitmapToByteArray(slideshowBitmap)
                }
                // Radio Logo
                !radioLogoPath.isNullOrBlank() && File(radioLogoPath).exists() -> {
                    currentArtworkSource = radioLogoPath
                    imageLoader.loadImageAsBytes(radioLogoPath)
                }
                // Fallback: DAB+ Icon (weiß auf dunklem Hintergrund)
                else -> {
                    currentArtworkSource = "drawable:ic_fytfm_dab_plus_light"
                    imageLoader.loadDrawableAsBytes(R.drawable.ic_fytfm_dab_plus_light)
                }
            }
            updateDabMetadataInternal(stationName, displayTitle, ensembleLabel, artworkData, deezerCoverPath, radioLogoPath)
        }
    }

    private fun updateDabMetadataInternal(
        stationName: String,
        displayTitle: String,
        ensembleLabel: String?,
        artworkData: ByteArray?,
        deezerCoverPath: String?,
        radioLogoPath: String?
    ) {
        val metadata = MediaMetadata.Builder()
            .setTitle(displayTitle)                                    // DLS oder Stationsname
            .setSubtitle(stationName)                                  // Sendername als Untertitel
            .setArtist(stationName)                                    // Sendername (für Player die Artist zeigen)
            .setAlbumTitle(ensembleLabel ?: "DAB+")                    // Ensemble Label
            .setDisplayTitle(displayTitle)                             // DLS oder Stationsname
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                if (artworkData != null) {
                    setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    Log.d(TAG, "Setting DAB artworkData: ${artworkData.size} bytes")
                }
            }
            .build()

        player.updateMetadata(metadata)

        // Legacy MediaButtonSession auch aktualisieren (nur lokale Pfade unterstützt)
        val displayForLegacy = displayTitle
        val coverForLegacy = if (deezerCoverPath?.startsWith("/") == true) deezerCoverPath else radioLogoPath
        val subtitleForLegacy = ensembleLabel ?: "DAB+"
        mediaButtonSession?.updateMetadata(displayForLegacy, subtitleForLegacy, coverForLegacy)

        Log.d(TAG, "DAB Metadata updated: $stationName | $displayTitle | deezerCover=$deezerCoverPath | data=${artworkData?.size ?: 0}b")
    }

    /**
     * Aktualisiert den Playback-Status
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        player.updatePlaybackState(isPlaying)
        mediaButtonSession?.updatePlaybackState(isPlaying)
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
                        .setTitle(getString(R.string.app_name))
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
                        createFolderItem(FM_STATIONS_ID, getString(R.string.media_folder_fm)),
                        createFolderItem(AM_STATIONS_ID, getString(R.string.media_folder_am))
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
