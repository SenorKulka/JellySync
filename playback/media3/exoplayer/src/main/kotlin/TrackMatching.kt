package org.jellyfin.playback.media3.exoplayer

import org.jellyfin.playback.core.model.MediaTrack

internal const val EXTERNAL_SUBTITLE_PREFIX = "JF_EXTERNAL:"

/** ExoPlayer prefixes merged subtitle IDs with a source number. */
internal fun findTrackIndex(track: MediaTrack, formatIds: List<String?>): Int? {
	val external = formatIds.indexOfFirst { it?.endsWith("$EXTERNAL_SUBTITLE_PREFIX${track.id}") == true }
	if (external >= 0) return external
	val embedded = formatIds.indices.filter { formatIds[it]?.contains(EXTERNAL_SUBTITLE_PREFIX) != true }
	return track.embeddedIndex?.let(embedded::getOrNull)
}
