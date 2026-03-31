package com.gateshot.coaching.replay

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gateshot.core.api.ApiEndpoint
import com.gateshot.core.api.ApiResponse
import com.gateshot.core.event.AppEvent
import com.gateshot.core.event.EventBus
import com.gateshot.core.event.collect
import com.gateshot.core.mode.AppMode
import com.gateshot.core.module.FeatureModule
import com.gateshot.core.module.ModuleHealth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplayFeatureModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: EventBus
) : FeatureModule {

    override val name = "replay"
    override val version = "0.1.0"
    override val requiredMode = AppMode.COACH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var lastRecordedClipUri: String? = null

    private val _replayState = MutableStateFlow(ReplayState())
    val replayState: StateFlow<ReplayState> = _replayState.asStateFlow()

    override suspend fun initialize() {
        // Track latest recorded clip for instant replay
        eventBus.collect<AppEvent.VideoRecordingStopped>(scope) { event ->
            lastRecordedClipUri = event.clipUri
        }
    }

    override suspend fun shutdown() {
        player?.release()
        player = null
    }

    override fun endpoints(): List<ApiEndpoint<*, *>> = listOf(
        LoadClip(),
        PlayControl(),
        GetReplayStatus(),
        SetPlaybackSpeed(),
        SeekTo(),
        SetupSplitScreen()
    )

    override fun healthCheck() = ModuleHealth(name, ModuleHealth.Status.OK)

    private fun getOrCreatePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    updateState()
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateState()
                }
            })
        }
    }

    private fun updateState() {
        val p = player ?: return
        _replayState.value = ReplayState(
            isLoaded = p.mediaItemCount > 0,
            isPlaying = p.isPlaying,
            currentPositionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0),
            playbackSpeed = p.playbackParameters.speed,
            clipUri = lastRecordedClipUri
        )
    }

    // --- coach/replay/load ---
    inner class LoadClip : ApiEndpoint<LoadClipRequest, Boolean> {
        override val path = "coach/replay/load"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: LoadClipRequest): ApiResponse<Boolean> {
            val uri = request.clipUri ?: lastRecordedClipUri
                ?: return ApiResponse.error(404, "No clip available")
            val p = getOrCreatePlayer()
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            lastRecordedClipUri = uri
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/play ---
    inner class PlayControl : ApiEndpoint<PlayControlRequest, Boolean> {
        override val path = "coach/replay/play"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: PlayControlRequest): ApiResponse<Boolean> {
            val p = player ?: return ApiResponse.error(409, "No clip loaded")
            when (request.action) {
                "play" -> p.play()
                "pause" -> p.pause()
                "toggle" -> if (p.isPlaying) p.pause() else p.play()
            }
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/status ---
    inner class GetReplayStatus : ApiEndpoint<Unit, ReplayState> {
        override val path = "coach/replay/status"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Unit): ApiResponse<ReplayState> {
            updateState()
            return ApiResponse.success(_replayState.value)
        }
    }

    // --- coach/replay/speed ---
    inner class SetPlaybackSpeed : ApiEndpoint<Float, Boolean> {
        override val path = "coach/replay/speed"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Float): ApiResponse<Boolean> {
            val speed = request.coerceIn(0.1f, 4.0f)
            player?.setPlaybackSpeed(speed)
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/replay/seek ---
    inner class SeekTo : ApiEndpoint<Long, Boolean> {
        override val path = "coach/replay/seek"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: Long): ApiResponse<Boolean> {
            player?.seekTo(request.coerceAtLeast(0))
            updateState()
            return ApiResponse.success(true)
        }
    }

    // --- coach/compare/split ---
    inner class SetupSplitScreen : ApiEndpoint<SplitScreenRequest, Boolean> {
        override val path = "coach/compare/split"
        override val module = "replay"
        override val requiredMode = AppMode.COACH

        override suspend fun handle(request: SplitScreenRequest): ApiResponse<Boolean> {
            // Store split-screen config — UI layer will read this to render two players
            _replayState.value = _replayState.value.copy(
                splitScreen = SplitScreenConfig(
                    leftClipUri = request.leftClipUri,
                    rightClipUri = request.rightClipUri,
                    syncMode = request.syncMode
                )
            )
            return ApiResponse.success(true)
        }
    }
}

data class ReplayState(
    val isLoaded: Boolean = false,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val clipUri: String? = null,
    val splitScreen: SplitScreenConfig? = null
)

data class LoadClipRequest(val clipUri: String? = null)
data class PlayControlRequest(val action: String = "toggle")  // play, pause, toggle
data class SplitScreenRequest(
    val leftClipUri: String,
    val rightClipUri: String,
    val syncMode: String = "gate"  // gate, timestamp, manual
)
data class SplitScreenConfig(
    val leftClipUri: String,
    val rightClipUri: String,
    val syncMode: String
)
