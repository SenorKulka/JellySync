package org.jellyfin.playback.core.mediastream

/** A non-null selection with no subtitle ID explicitly disables subtitles. */
data class MediaStreamSelection(
	val audioId: String? = null,
	val subtitleId: String? = null,
	val maxBitrate: Int? = null,
)
