package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.jellyfin.mediastream.JellyfinTrackSelectionService
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.jellyfin.sdk.model.extensions.ticks

private const val BITS_PER_MEGABIT = 1_000_000

@Composable
internal fun VideoPlayerOptionsDialog(
	selection: VideoPlayerSelection,
	playbackManager: PlaybackManager,
	scaleMode: VideoPlayerScaleMode,
	onScaleModeChange: (VideoPlayerScaleMode) -> Unit,
	onDismissRequest: () -> Unit,
) {
	val tracks by playbackManager.state.tracks.collectAsState()
	val trackService = playbackManager.getService<JellyfinTrackSelectionService>()
	val changingStream = trackService?.busy?.collectAsState()?.value == true
	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value
	val stream = entry?.run { mediaStreamFlow.collectAsState(mediaStream) }?.value
	when (selection) {
		VideoPlayerSelection.AUDIO -> VideoPlayerSelectionDialog(
			enabled = !changingStream,
			title = stringResource(R.string.lbl_audio_track),
			options = tracks.audio.map { VideoPlayerOption(it.id, it.title) },
			selectedId = tracks.selectedAudioId,
			onSelect = { id -> id?.let(playbackManager.state::selectAudioTrack) },
			onDismissRequest = onDismissRequest,
		)
		VideoPlayerSelection.SUBTITLES -> VideoPlayerSelectionDialog(
			enabled = !changingStream,
			title = stringResource(R.string.lbl_subtitle_track),
			options = listOf(VideoPlayerOption(null, stringResource(R.string.lbl_none))) +
				tracks.subtitles.map { VideoPlayerOption(it.id, it.title) },
			selectedId = tracks.selectedSubtitleId,
			onSelect = playbackManager.state::selectSubtitleTrack,
			onDismissRequest = onDismissRequest,
		)
		VideoPlayerSelection.CHAPTERS -> VideoPlayerSelectionDialog(
			title = stringResource(R.string.lbl_chapters),
			options = item?.chapters.orEmpty().mapIndexed { index, chapter ->
				VideoPlayerOption(chapter.startPositionTicks.toString(), chapter.name ?: "${index + 1}")
			},
			selectedId = item?.chapters?.lastOrNull {
				it.startPositionTicks.ticks <= playbackManager.state.positionInfo.active
			}?.startPositionTicks?.toString(),
			onSelect = { id -> id?.toLongOrNull()?.let { playbackManager.state.seek(it.ticks) } },
			onDismissRequest = onDismissRequest,
		)
		VideoPlayerSelection.SCALE -> VideoPlayerSelectionDialog(
			title = stringResource(R.string.lbl_zoom),
			options = listOf(
				VideoPlayerOption(VideoPlayerScaleMode.FIT.name, stringResource(R.string.lbl_fit)),
				VideoPlayerOption(VideoPlayerScaleMode.ZOOM.name, stringResource(R.string.lbl_zoom)),
			),
			selectedId = scaleMode.name,
			onSelect = { id -> id?.let { onScaleModeChange(VideoPlayerScaleMode.valueOf(it)) } },
			onDismissRequest = onDismissRequest,
		)
		VideoPlayerSelection.QUALITY -> VideoPlayerSelectionDialog(
			enabled = !changingStream,
			title = stringResource(R.string.lbl_quality_profile),
			options = listOf(VideoPlayerOption(null, stringResource(R.string.auto))) +
				getQualityProfiles(LocalContext.current).map { (mbps, label) ->
					VideoPlayerOption((mbps.toDouble() * BITS_PER_MEGABIT).toInt().toString(), label)
				},
			selectedId = stream?.selection?.maxBitrate?.toString(),
			onSelect = { trackService?.setMaxBitrate(it?.toIntOrNull()) },
			onDismissRequest = onDismissRequest,
		)
	}
}
