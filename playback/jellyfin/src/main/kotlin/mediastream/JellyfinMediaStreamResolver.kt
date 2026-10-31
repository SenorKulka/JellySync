package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamSelection
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
	}

	override suspend fun getStream(
		queueEntry: QueueEntry,
		selection: MediaStreamSelection?,
		startPosition: Duration,
	): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		var mediaInfo = getPlaybackInfo(baseItem, queueEntry.mediaSourceId, selection, startPosition)
		// Jellyfin applies requested language indices only after a media source has been selected.
		if (selection != null && queueEntry.mediaSourceId == null) {
			mediaInfo = getPlaybackInfo(baseItem, requireNotNull(mediaInfo.mediaSource.id), selection, startPosition)
		}
		queueEntry.mediaSourceId = mediaInfo.mediaSource.id

		return when {
			// Direct play video
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.VIDEO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				selection = selection,
				startPosition = Duration.ZERO,
				conversionMethod = MediaConversionMethod.None,
				url = api.videosApi.getVideoStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Direct play audio
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.AUDIO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				selection = selection,
				startPosition = Duration.ZERO,
				conversionMethod = MediaConversionMethod.None,
				url = api.audioApi.getAudioStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Remux (direct stream)
			mediaInfo.mediaSource.supportsDirectStream && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				selection = selection,
				startPosition = mediaInfo.getTranscodingStartPosition(),
				conversionMethod = MediaConversionMethod.Remux,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// Transcode
			mediaInfo.mediaSource.supportsTranscoding && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				selection = selection,
				startPosition = mediaInfo.getTranscodingStartPosition(),
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// No compatible stream found
			else -> null
		}
	}

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String?,
		selection: MediaStreamSelection?,
		startPosition: Duration,
	): MediaInfo {
		val profile = deviceProfileBuilder()
		val response by api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				audioStreamIndex = selection?.audioId?.toInt(),
				subtitleStreamIndex = selection?.let { it.subtitleId?.toInt() ?: -1 },
				maxStreamingBitrate = selection?.maxBitrate,
				startTimeTicks = startPosition.inWholeMicroseconds * TICKS_PER_MICROSECOND,
				deviceProfile = profile,
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
			)
		)

		if (response.errorCode != null) {
			error("Failed to get media info for item ${item.id} source ${mediaSourceId}: ${response.errorCode}")
		}

		val mediaSource = response.mediaSources
			// Filter out invalid streams (like strm files)
			.filter { it.protocol == MediaProtocol.FILE && !it.isRemote }
			// Select first media source
			.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }

		requireNotNull(mediaSource) {
			"Failed to get media info for item ${item.id} source ${mediaSourceId}: media source missing in response"
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource
		)
	}

	private fun MediaInfo.getTranscodingStartPosition(): Duration {
		// Jellyfin HLS manifests describe the entire title, including on-demand segments before the requested position.
		if (mediaSource.transcodingSubProtocol == MediaStreamProtocol.HLS) return Duration.ZERO
		val ticks = mediaSource.transcodingUrl?.substringAfter('?', "")?.split('&')
			?.firstOrNull { it.substringBefore('=').equals("StartTimeTicks", ignoreCase = true) }
			?.substringAfter('=')?.toLongOrNull() ?: return Duration.ZERO
		return (ticks / TICKS_PER_MICROSECOND).microseconds
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
		selection: MediaStreamSelection?,
		startPosition: Duration,
	): PlayableMediaStream {
		val externalSubtitles = getExternalSubtitles(api, requireNotNull(queueEntry.baseItem).id, startPosition)
		return PlayableMediaStream(
			identifier = playSessionId,
			conversionMethod = conversionMethod,
			container = if (conversionMethod == MediaConversionMethod.None) getMediaStreamContainer()
			else MediaStreamContainer(mediaSource.transcodingContainer ?: requireNotNull(mediaSource.container)),
			tracks = getTracks(),
			queueEntry = queueEntry,
			url = url,
			trackSelection = getTrackSelection(conversionMethod, selection, externalSubtitles),
			externalSubtitles = externalSubtitles,
			startPosition = startPosition,
			selection = selection,
		)
	}
}
