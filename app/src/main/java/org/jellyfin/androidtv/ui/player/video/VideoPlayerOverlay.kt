package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jellyfin.playback.jellyfin.mediastream.JellyfinTrackSelectionService
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.androidtv.ui.syncplay.SyncPlayDialog
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject

@Suppress("LongMethod") // Modal visibility belongs to the same persistent player overlay.
@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
	scaleMode: VideoPlayerScaleMode = VideoPlayerScaleMode.FIT,
	onScaleModeChange: (VideoPlayerScaleMode) -> Unit = {},
) {
	val visibilityState = rememberPlayerOverlayVisibility()
	var showPlaybackInfo by remember { mutableStateOf(false) }
	var showSyncPlay by remember { mutableStateOf(false) }
	var selection by remember { mutableStateOf<VideoPlayerSelection?>(null) }
	val trackService = playbackManager.getService<JellyfinTrackSelectionService>()

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					actions = VideoPlayerControlActions(
						onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
						onSyncPlayClick = { showSyncPlay = true },
						onAudioClick = { selection = VideoPlayerSelection.AUDIO },
						onSubtitlesClick = { selection = VideoPlayerSelection.SUBTITLES },
						onChaptersClick = { selection = VideoPlayerSelection.CHAPTERS },
						onScaleClick = { selection = VideoPlayerSelection.SCALE },
						onQualityClick = { selection = VideoPlayerSelection.QUALITY },
					),
					hasChapters = !item?.chapters.isNullOrEmpty(),
				)
			},
		)

		// Playback info overlay - positioned below header area, always visible when enabled
		if (showPlaybackInfo) {
			PlaybackInfoOverlay(
				playbackManager = playbackManager,
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(start = 48.dp, top = 100.dp)
			)
		}

		MediaToasts(mediaToastRegistry)

		SyncPlayDialog(
			visible = showSyncPlay,
			onDismissRequest = { showSyncPlay = false },
			playbackManager = playbackManager,
		)

		val dismiss = { selection = null; visibilityState.show() }
		selection?.let {
			VideoPlayerOptionsDialog(it, playbackManager, scaleMode, onScaleModeChange, dismiss)
		}
		trackService?.let { service ->
			val error by service.error.collectAsState()
			if (error != null) VideoPlayerSelectionDialog(
				title = error.orEmpty(),
				options = emptyList(),
				selectedId = null,
				onSelect = {},
				onDismissRequest = service::clearError,
			)
		}
	}
}
