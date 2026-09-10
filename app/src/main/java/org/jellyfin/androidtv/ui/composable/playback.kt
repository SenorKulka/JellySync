package org.jellyfin.androidtv.ui.composable

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.queue
import org.koin.compose.koinInject
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun rememberQueueEntry(
	playbackManager: PlaybackManager = koinInject(),
) = remember(playbackManager) {
	playbackManager.queue.entry
}.collectAsState()

@Composable
fun rememberPlayerPositionInfo(
	playbackManager: PlaybackManager = koinInject(),
	precision: Duration = 1.seconds
): MutableState<PositionInfo> {
	val positionInfo = remember(playbackManager) { mutableStateOf(playbackManager.state.positionInfo) }

	LaunchedEffect(playbackManager, precision) {
		val precisionMs = precision.inWholeMilliseconds.coerceAtLeast(1)

		// A paused player can still seek, buffer, or select a new item remotely.
		while (isActive) {
			positionInfo.value = playbackManager.state.positionInfo

			delay(precisionMs - (positionInfo.value.active.inWholeMilliseconds % precisionMs))
		}
	}

	return positionInfo
}

@Composable
fun rememberPlayerProgress(
	playbackManager: PlaybackManager = koinInject(),
): State<Float> {
	val playState by playbackManager.state.playState.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)

	return rememberPlayerProgress(
		playing = playState == PlayState.PLAYING,
		active = positionInfo.active,
		duration = positionInfo.duration,
	)
}

@Composable
fun rememberPlayerProgress(
	playing: Boolean,
	active: Duration,
	duration: Duration,
): State<Float> {
	val animatable = remember { Animatable(0f, 0f) }

	LaunchedEffect(playing, active, duration) {
		val activeMs = active.inWholeMilliseconds.toFloat()
		val durationMs = duration.inWholeMilliseconds.toFloat()

		if (active == Duration.ZERO) animatable.snapTo(0f)
		else animatable.snapTo((activeMs / durationMs).coerceIn(0f, 1f))

		if (playing) withContext(FixedMotionDurationScale) {
			animatable.animateTo(
				targetValue = 1f,
				animationSpec = tween(
					durationMillis = (durationMs - activeMs).roundToInt(),
					easing = LinearEasing,
				)
			)
		}
	}

	return animatable.asState()
}
