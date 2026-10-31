package org.jellyfin.playback.core.model

/** A server track ID is stable across direct play, remuxing, and transcoding. */
data class MediaTrack(
	val id: String,
	val title: String,
	val language: String? = null,
	val embeddedIndex: Int? = null,
	val requiresReload: Boolean = false,
)

data class PlayerTracks(
	val audio: List<MediaTrack> = emptyList(),
	val subtitles: List<MediaTrack> = emptyList(),
	val selectedAudioId: String? = null,
	val selectedSubtitleId: String? = null,
)
