package org.jellyfin.androidtv.ui.syncplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.player.video.VideoPlayerScreen
import org.jellyfin.playback.core.PlaybackManager
import org.koin.compose.koinInject

private const val MINI_PLAYER_ASPECT_RATIO = 16f / 9f

@Composable
fun SyncPlayPlayerHost(
	state: SyncPlayPlayerState,
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	onBrowseFocusRequested: () -> Unit = {},
) {
	val mode by state.mode.collectAsState()
	val focused by state.miniPlayerFocused.collectAsState()
	val focusRequester = remember { FocusRequester() }
	val interactionSource = remember { MutableInteractionSource() }
	val restoreLabel = stringResource(R.string.syncplay_restore_player)

	SyncPlayPlayerFocusEffects(state, focusRequester, onBrowseFocusRequested)

	if (mode == SyncPlayPlayerMode.HIDDEN) return
	val minimized = mode == SyncPlayPlayerMode.MINIMIZED

	Box(modifier.fillMaxSize()) {
		val presentationModifier = if (minimized) {
			Modifier
				.align(Alignment.BottomEnd)
				.padding(32.dp)
				.width(320.dp)
				.aspectRatio(MINI_PLAYER_ASPECT_RATIO)
				.focusRequester(focusRequester)
				.onFocusChanged { state.setMiniPlayerFocused(it.isFocused) }
				.clickable(interactionSource = interactionSource, indication = null, onClick = state::expand)
				.semantics { contentDescription = restoreLabel }
		} else {
			Modifier.fillMaxSize()
		}

		Box(presentationModifier) {
			// Keep this call in the same composition slot so resizing retains the decoder's surface.
			VideoPlayerScreen(playbackManager = playbackManager, showControls = !minimized)
			if (minimized) {
				Box(
					Modifier.fillMaxSize().border(
						width = if (focused) 3.dp else 1.dp,
						color = if (focused) JellyfinTheme.colorScheme.onBackground else Color.White.copy(alpha = 0.5f),
					)
				)
				if (focused) {
					Text(
						text = restoreLabel,
						color = Color.White,
						modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.8f)).padding(8.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun SyncPlayPlayerFocusEffects(
	state: SyncPlayPlayerState,
	focusRequester: FocusRequester,
	onBrowseFocusRequested: () -> Unit,
) {
	val miniRequests by state.miniPlayerFocusRequests.collectAsState()
	val browseRequests by state.browseFocusRequests.collectAsState()
	val requestBrowseFocus by rememberUpdatedState(onBrowseFocusRequested)
	LaunchedEffect(state, miniRequests) {
		if (state.mode.value == SyncPlayPlayerMode.MINIMIZED && miniRequests > 0) focusRequester.requestFocus()
	}
	LaunchedEffect(state, browseRequests) {
		val pendingMiniRequests = state.miniPlayerFocusRequests.value
		if (state.mode.value == SyncPlayPlayerMode.MINIMIZED && browseRequests > 0) {
			// Let the navigation host enable its native descendants before restoring their focus.
			withFrameNanos { }
			if (state.mode.value == SyncPlayPlayerMode.MINIMIZED && !state.miniPlayerFocused.value &&
				state.miniPlayerFocusRequests.value == pendingMiniRequests
			) {
				requestBrowseFocus()
			}
		}
	}
}
