package at.planqton.fytfm.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import at.planqton.fytfm.MainActivity

/**
 * Legacy MediaSession für Media Button Events (Lenkradtasten).
 *
 * FYT Head Units senden Media Button Events über das Android Media Framework.
 * Diese Session fängt die Events ab und leitet sie an die App weiter.
 */
class MediaButtonSession(
    private val context: Context,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onPlayPause: () -> Unit
) {
    companion object {
        private const val TAG = "MediaButtonSession"
    }

    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Callback für Media Button Events
     */
    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            val action = mediaButtonIntent.action
            Log.d(TAG, "onMediaButtonEvent: action=$action")

            if (Intent.ACTION_MEDIA_BUTTON == action) {
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (event != null) {
                    val keyCode = event.keyCode
                    val eventAction = event.action

                    Log.d(TAG, "KeyEvent: keyCode=$keyCode, action=$eventAction")

                    // Nur auf KEY_UP oder LONG_PRESS reagieren (wie navradio)
                    if (eventAction == KeyEvent.ACTION_UP || event.isLongPress) {
                        handler.post { handleKeyCode(keyCode) }
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext")
            handler.post {
                showToast("NEXT")
                onNext()
            }
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious")
            handler.post {
                showToast("PREV")
                onPrevious()
            }
        }

        override fun onPlay() {
            Log.d(TAG, "onPlay")
            handler.post { onPlayPause() }
        }

        override fun onPause() {
            Log.d(TAG, "onPause")
            handler.post { onPlayPause() }
        }
    }

    private fun handleKeyCode(keyCode: Int) {
        Log.d(TAG, "handleKeyCode: $keyCode")
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Log.i(TAG, "MEDIA_NEXT pressed")
                showToast("NEXT")
                onNext()
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Log.i(TAG, "MEDIA_PREVIOUS pressed")
                showToast("PREV")
                onPrevious()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                Log.i(TAG, "PLAY_PAUSE pressed")
                onPlayPause()
            }
            else -> {
                Log.d(TAG, "Unknown keyCode: $keyCode")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Initialisieren und aktivieren der MediaSession
     */
    fun initialize() {
        if (mediaSession != null) {
            Log.d(TAG, "Already initialized")
            return
        }

        try {
            // MediaSession erstellen
            mediaSession = MediaSession(context, "fytFM_MediaButton").apply {
                // Callback setzen
                setCallback(callback, handler)

                // Flags setzen - WICHTIG für Media Button Events
                // FLAG_HANDLES_MEDIA_BUTTONS = 1
                // FLAG_HANDLES_TRANSPORT_CONTROLS = 2
                // Manche Systeme brauchen auch Flag 4 für exklusive Priorität
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )

                // Activity Intent für Klick auf Notification
                val activityIntent = Intent(context, MainActivity::class.java)
                val activityPendingIntent = PendingIntent.getActivity(
                    context, 0, activityIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setSessionActivity(activityPendingIntent)

                // WICHTIG: MediaButtonReceiver setzen!
                // Das ist der PendingIntent der aufgerufen wird wenn Media-Buttons gedrückt werden
                val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                mediaButtonIntent.setPackage(context.packageName)
                // Für Service-basierte Verarbeitung:
                val mediaButtonPendingIntent = PendingIntent.getBroadcast(
                    context, 0, mediaButtonIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setMediaButtonReceiver(mediaButtonPendingIntent)
                Log.d(TAG, "MediaButtonReceiver set: $mediaButtonPendingIntent")

                // PlaybackState setzen - ohne das bekommt man keine Media Button Events
                val playbackState = PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                    .build()
                setPlaybackState(playbackState)

                // Aktivieren
                isActive = true
            }

            Log.i(TAG, "MediaSession initialized and active")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaSession", e)
        }
    }

    /**
     * PlaybackState aktualisieren
     */
    fun updatePlaybackState(isPlaying: Boolean) {
        try {
            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            val playbackState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_STOP
                )
                .setState(state, 0, 1.0f)
                .build()
            mediaSession?.setPlaybackState(playbackState)
            Log.d(TAG, "PlaybackState updated: isPlaying=$isPlaying")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update playback state", e)
        }
    }

    /**
     * Aufräumen
     */
    fun release() {
        try {
            mediaSession?.apply {
                isActive = false
                release()
            }
            mediaSession = null
            Log.i(TAG, "MediaSession released")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release MediaSession", e)
        }
    }
}
