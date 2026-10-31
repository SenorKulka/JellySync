package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.map
import org.jellyfin.androidtv.data.service.BackgroundService
import org.jellyfin.androidtv.ui.ScreensaverLock
import org.jellyfin.androidtv.ui.player.base.PlayerSubtitles
import org.jellyfin.androidtv.ui.player.base.PlayerSurface
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.video.toast.rememberPlaybackManagerMediaToastEmitter
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject

private const val DefaultVideoAspectRatio = 16f / 9f

@Composable
fun VideoPlayerScreen(
	modifier: Modifier = Modifier,
	showControls: Boolean = true,
	playbackManager: PlaybackManager = koinInject(),
) {
	val backgroundService = koinInject<BackgroundService>()
	LaunchedEffect(backgroundService) {
		backgroundService.clearBackgrounds()
	}

	val playing by remember {
		playbackManager.state.playState.map { it == PlayState.PLAYING }
	}.collectAsState(false)
	ScreensaverLock(
		enabled = playing,
	)

	val coroutineScope = rememberCoroutineScope()
	val mediaToastRegistry = remember { MediaToastRegistry(coroutineScope) }
	rememberPlaybackManagerMediaToastEmitter(playbackManager, mediaToastRegistry)

	VideoPlayerContent(playbackManager, mediaToastRegistry, showControls, modifier)
}

@Composable
private fun VideoPlayerContent(
	playbackManager: PlaybackManager,
	mediaToastRegistry: MediaToastRegistry,
	showControls: Boolean,
	modifier: Modifier,
) {
	val videoSize by playbackManager.state.videoSize.collectAsState()
	val aspectRatio = videoSize.aspectRatio.takeIf { it.isFinite() && it > 0f } ?: DefaultVideoAspectRatio
	var scaleMode by rememberSaveable { mutableStateOf(VideoPlayerScaleMode.FIT) }

	BoxWithConstraints(
		modifier = modifier
			.background(Color.Black)
			.fillMaxSize()
			.clipToBounds()
	) {
		val widerThanContainer = aspectRatio > maxWidth / maxHeight
		val fitWidth = if (widerThanContainer) maxWidth else maxHeight * aspectRatio
		val fitHeight = fitWidth / aspectRatio
		val zoom = showControls && scaleMode == VideoPlayerScaleMode.ZOOM
		val surfaceWidth = if (!zoom) fitWidth else if (widerThanContainer) maxHeight * aspectRatio else maxWidth
		val surfaceHeight = surfaceWidth / aspectRatio

		PlayerSurface(
			playbackManager = playbackManager,
			modifier = Modifier
				.requiredSize(surfaceWidth, surfaceHeight)
				.align(Alignment.Center)
		)

		if (showControls) {
			VideoPlayerOverlay(
				playbackManager = playbackManager,
				mediaToastRegistry = mediaToastRegistry,
				scaleMode = scaleMode,
				onScaleModeChange = { scaleMode = it },
			)
		}

		PlayerSubtitles(
			playbackManager = playbackManager,
			modifier = (if (zoom) Modifier.fillMaxSize() else Modifier.requiredSize(fitWidth, fitHeight))
				.align(Alignment.Center)
		)
	}
}
