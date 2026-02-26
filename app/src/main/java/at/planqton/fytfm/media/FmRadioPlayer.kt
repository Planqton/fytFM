package at.planqton.fytfm.media

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * FM Radio Player basierend auf SimpleBasePlayer.
 * Nutzt invalidateState() um die MediaSession automatisch zu benachrichtigen.
 */
@UnstableApi
class FmRadioPlayer(
    looper: Looper,
    private val onPlayRequest: () -> Unit,
    private val onPauseRequest: () -> Unit,
    private val onSkipNextRequest: () -> Unit,
    private val onSkipPrevRequest: () -> Unit
) : SimpleBasePlayer(looper) {

    companion object {
        private const val TAG = "FmRadioPlayer"
    }

    private var currentMediaItem: MediaItem? = null
    private var _isPlaying = false

    init {
        Log.i(TAG, "FmRadioPlayer created")
    }

    fun updateMetadata(metadata: MediaMetadata) {
        currentMediaItem = MediaItem.Builder()
            .setMediaId("fm_current")
            .setMediaMetadata(metadata)
            .build()
        // Wenn Metadaten gesetzt werden, ist das Radio aktiv
        if (!_isPlaying) {
            _isPlaying = true
        }
        invalidateState()
        Log.d(TAG, "updateMetadata: ${metadata.title} | ${metadata.subtitle} | playing=$_isPlaying")
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        _isPlaying = isPlaying
        invalidateState()
        Log.d(TAG, "updatePlaybackState: $isPlaying")
    }

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE
                    )
                    .build()
            )
            .setPlayWhenReady(_isPlaying, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (_isPlaying) STATE_READY else STATE_IDLE)
            .setContentPositionMs(0)
            .setContentBufferedPositionMs(PositionSupplier.ZERO)
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .setIsLoading(false)
            .setPlaybackParameters(PlaybackParameters.DEFAULT)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT)
            .setDeviceVolume(15)
            .setIsDeviceMuted(false)

        // MediaItem hinzufügen wenn vorhanden
        currentMediaItem?.let { item ->
            val mediaItemData = MediaItemData.Builder(item.mediaId ?: "fm_current")
                .setMediaItem(item)
                .setMediaMetadata(item.mediaMetadata)
                .setIsSeekable(false)
                .setIsDynamic(false)
                .setIsPlaceholder(false)
                .setDurationUs(C.TIME_UNSET)
                .setDefaultPositionUs(0)
                .build()
            builder.setPlaylist(listOf(mediaItemData))
            builder.setCurrentMediaItemIndex(0)
        }

        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        Log.i(TAG, "handleSetPlayWhenReady: $playWhenReady")
        _isPlaying = playWhenReady
        if (playWhenReady) {
            onPlayRequest()
        } else {
            onPauseRequest()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        Log.i(TAG, "handleStop")
        _isPlaying = false
        onPauseRequest()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        Log.i(TAG, "handleSeek: seekCommand=$seekCommand")
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                onSkipNextRequest()
            }
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                onSkipPrevRequest()
            }
        }
        return Futures.immediateVoidFuture()
    }
}
