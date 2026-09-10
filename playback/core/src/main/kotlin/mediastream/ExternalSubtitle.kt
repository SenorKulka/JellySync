package org.jellyfin.playback.core.mediastream

data class ExternalSubtitle(
	val id: String,
	val url: String,
	val mimeType: String,
	val language: String? = null,
	val title: String? = null,
	val default: Boolean = false,
	val forced: Boolean = false,
)
