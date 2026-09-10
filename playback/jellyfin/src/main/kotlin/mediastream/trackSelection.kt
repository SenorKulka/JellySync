package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.ExternalSubtitle
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamSelection
import org.jellyfin.playback.core.model.MediaTrack
import org.jellyfin.playback.core.model.PlayerTracks
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID
import kotlin.time.Duration

internal fun MediaInfo.getTrackSelection(
	conversionMethod: MediaConversionMethod,
	selection: MediaStreamSelection?,
	externalSubtitles: List<ExternalSubtitle>,
): PlayerTracks {
	val streams = mediaSource.mediaStreams.orEmpty().sortedBy { it.index }
	val audio = streams.filter { it.type == MediaStreamType.AUDIO }
	val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE && it.deliveryMethod != SubtitleDeliveryMethod.DROP }
	val audioId = selection?.audioId ?: mediaSource.defaultAudioStreamIndex?.takeIf { it >= 0 }?.toString()
		?: audio.firstOrNull { it.isDefault }?.index?.toString() ?: audio.firstOrNull()?.index?.toString()
	val subtitleId = if (selection == null) mediaSource.defaultSubtitleStreamIndex?.takeIf { it >= 0 }?.toString()
	else selection.subtitleId
	val direct = conversionMethod == MediaConversionMethod.None
	return PlayerTracks(
		audio = audio.map { stream ->
			MediaTrack(
				id = stream.index.toString(),
				title = stream.trackTitle(),
				language = stream.language,
				embeddedIndex = if (direct) audio.filterNot { it.isExternal }.indexOf(stream).takeIf { it >= 0 } else 0,
				requiresReload = !direct || stream.isExternal,
			)
		},
		subtitles = subtitles.map { stream ->
			val external = externalSubtitles.any { it.id == stream.index.toString() }
			val embeddedIndex = streams.filter { it.type == MediaStreamType.SUBTITLE && !it.isExternal }
				.indexOf(stream).takeIf { it >= 0 }
			MediaTrack(
				id = stream.index.toString(),
				title = stream.trackTitle(),
				language = stream.language,
				embeddedIndex = if (external) null else embeddedIndex,
				requiresReload = stream.deliveryMethod == SubtitleDeliveryMethod.ENCODE ||
					(!external && (!direct || embeddedIndex == null)),
			)
		},
		selectedAudioId = audioId?.takeIf { id -> audio.any { it.index.toString() == id } },
		selectedSubtitleId = subtitleId?.takeIf { id -> subtitles.any { it.index.toString() == id } },
	)
}

internal fun MediaInfo.getExternalSubtitles(
	api: ApiClient,
	itemId: UUID,
	startPosition: Duration,
): List<ExternalSubtitle> = mediaSource.mediaStreams.orEmpty()
	.filter { it.type == MediaStreamType.SUBTITLE }
	.filter { it.deliveryMethod == SubtitleDeliveryMethod.EXTERNAL || it.isExternal || it.extractEmbeddedText() }
	.filterNot { it.deliveryMethod == SubtitleDeliveryMethod.ENCODE || it.deliveryMethod == SubtitleDeliveryMethod.DROP }
	.mapNotNull { stream ->
		val sourceId = mediaSource.id ?: return@mapNotNull null
		val deliveryUrl = stream.deliveryUrl
		val format = deliveryUrl?.substringBefore('?')?.substringAfterLast('.')?.lowercase()
		val mimeType = subtitleMimeType(format) ?: subtitleMimeType(stream.codec) ?: return@mapNotNull null
		val useDeliveryUrl = deliveryUrl != null && startPosition == Duration.ZERO && !stream.extractEmbeddedText()
		val url = if (useDeliveryUrl) {
			if (requireNotNull(deliveryUrl).startsWith("https://") || deliveryUrl.startsWith("http://")) deliveryUrl
			else api.createUrl(deliveryUrl, ignorePathParameters = true)
		} else api.subtitleApi.getSubtitleUrl(
			routeItemId = itemId,
			routeMediaSourceId = sourceId,
			routeIndex = stream.index,
			routeFormat = "vtt",
			startPositionTicks = startPosition.inWholeMicroseconds * TICKS_PER_MICROSECOND,
		)
		ExternalSubtitle(
			id = stream.index.toString(),
			url = url,
			mimeType = if (useDeliveryUrl) mimeType else "text/vtt",
			language = stream.language,
			title = stream.trackTitle(),
			default = stream.isDefault,
			forced = stream.isForced,
		)
	}

// Extract plain text so switching languages also displays a cue that started before the current position.
// Bitmap captions and styled ASS/SSA retain their native renderer or server burn-in path.
private fun MediaStream.extractEmbeddedText() = deliveryMethod == SubtitleDeliveryMethod.EMBED &&
	codec?.lowercase() in setOf("srt", "subrip", "vtt", "webvtt", "ttml")

private fun subtitleMimeType(codec: String?) = when (codec?.lowercase()) {
	"srt", "subrip" -> "application/x-subrip"
	"vtt", "webvtt" -> "text/vtt"
	"ass", "ssa" -> "text/x-ssa"
	"ttml" -> "application/ttml+xml"
	else -> null
}

private fun MediaStream.trackTitle() = displayTitle?.takeIf { it.isNotBlank() }
	?: title?.takeIf { it.isNotBlank() }
	?: listOfNotNull(language, codec).joinToString(" · ").ifBlank { "${type.serialName} ${index + 1}" }

internal const val TICKS_PER_MICROSECOND = 10
