package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamSelection
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.SubtitleApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class JellyfinMediaStreamResolverTests : FunSpec({
	test("server indices remain separate from embedded ordinals and external subtitles") {
		val fixture = ResolverFixture()
		val result = fixture.resolver.getStream(fixture.entry).shouldNotBeNull()
		result.queueEntry shouldBe fixture.entry
		result.trackSelection.audio.map { it.id to it.embeddedIndex } shouldBe listOf("2" to 0, "3" to 1)
		result.trackSelection.selectedAudioId shouldBe "2"
		result.trackSelection.subtitles.map { it.id to it.embeddedIndex } shouldBe listOf("0" to null, "4" to null)
		result.trackSelection.selectedSubtitleId shouldBe "4"
		result.externalSubtitles.first { it.id == "0" }.mimeType shouldBe "text/vtt"
		result.externalSubtitles.first { it.id == "0" }.url shouldBe "https://server/jellyfin/subtitles/0.vtt"
	}

	test("embedded plain text is extracted as VTT with the same server track ID") {
		val fixture = ResolverFixture()
		val result = fixture.resolver.getStream(fixture.entry).shouldNotBeNull()
		val subtitle = result.externalSubtitles.first { it.id == "4" }
		subtitle.mimeType shouldBe "text/vtt"
		subtitle.url shouldBe "https://server/jellyfin/subtitle-at-0.vtt"
		verify { fixture.subtitleApi.getSubtitleUrl(
			routeItemId = fixture.entry.baseItem.shouldNotBeNull().id, routeMediaSourceId = "source",
			routeIndex = 4, routeFormat = "vtt", startPositionTicks = 0,
		) }
		result.trackSelection.subtitles.first { it.id == "4" }.requiresReload shouldBe false
	}

	test("bitmap and styled embedded subtitles retain the native renderer") {
		for (codec in listOf("pgssub", "ass")) {
			val mediaInfo = MediaInfo("play", source(true, listOf(
				stream(4, MediaStreamType.SUBTITLE, "eng").copy(codec = codec, deliveryMethod = SubtitleDeliveryMethod.EMBED),
			)))
			mediaInfo.getExternalSubtitles(mockk(), UUID.randomUUID(), Duration.ZERO) shouldBe emptyList()
			val track = mediaInfo.getTrackSelection(MediaConversionMethod.None, null, emptyList()).subtitles.single()
			track.id shouldBe "4"
			track.embeddedIndex shouldBe 0
			track.requiresReload shouldBe false
		}
	}

	test("an explicit off selection sends minus one while initial resolution keeps server defaults") {
		val fixture = ResolverFixture()
		fixture.resolver.getStream(fixture.entry)
		fixture.request.captured.subtitleStreamIndex shouldBe null
		val result = fixture.resolver.getStream(fixture.entry, MediaStreamSelection(audioId = "3")).shouldNotBeNull()
		fixture.request.captured.audioStreamIndex shouldBe 3
		fixture.request.captured.subtitleStreamIndex shouldBe -1
		result.trackSelection.selectedAudioId shouldBe "3"
		result.trackSelection.selectedSubtitleId shouldBe null
	}

	test("HLS preserves the full title timeline and selected source when reloaded at a later position") {
		val fixture = ResolverFixture(direct = false)
		val selection = MediaStreamSelection("3", "0", 4_000_000)
		val result = fixture.resolver.getStream(fixture.entry, selection, 45.seconds).shouldNotBeNull()
		result.queueEntry shouldBe fixture.entry
		result.selection shouldBe selection
		result.startPosition shouldBe Duration.ZERO
		result.container.format shouldBe "m3u8"
		result.conversionMethod shouldBe MediaConversionMethod.Transcode
		result.trackSelection.audio.all { it.requiresReload } shouldBe true
		fixture.request.captured.startTimeTicks shouldBe 450_000_000L
		fixture.request.captured.maxStreamingBitrate shouldBe 4_000_000
		fixture.request.captured.mediaSourceId shouldBe "source"
		fixture.entry.mediaSourceId shouldBe "source"
		result.externalSubtitles.first { it.id == "0" }.url shouldBe "https://server/jellyfin/subtitles/0.vtt"
	}

	test("direct play always retains a full-title timeline even when reloaded after a seek") {
		val fixture = ResolverFixture()
		val result = fixture.resolver.getStream(fixture.entry, MediaStreamSelection("3", "0"), 45.seconds).shouldNotBeNull()
		result.startPosition shouldBe Duration.ZERO
		result.externalSubtitles.first { it.id == "0" }.url shouldBe "https://server/jellyfin/subtitles/0.vtt"
	}

	test("burned subtitles require a stream reload and are not attached as sidecar text") {
		val fixture = ResolverFixture(direct = false, burned = true)
		val result = fixture.resolver.getStream(fixture.entry, MediaStreamSelection("2", "4")).shouldNotBeNull()
		result.trackSelection.subtitles.first { it.id == "4" }.requiresReload shouldBe true
		result.externalSubtitles.none { it.id == "4" } shouldBe true
	}

	test("an absolute external delivery URL is not appended to the server base path") {
		val fixture = ResolverFixture(deliveryUrl = "https://subtitles.example/captions.vtt")
		fixture.resolver.getStream(fixture.entry).shouldNotBeNull().externalSubtitles.first { it.id == "0" }.url shouldBe
			"https://subtitles.example/captions.vtt"
	}

	test("unsupported entries never request playback information") {
		val fixture = ResolverFixture()
		fixture.resolver.getStream(QueueEntry()) shouldBe null
		coVerify(exactly = 0) { fixture.mediaInfoApi.getPostedPlaybackInfo(any(), any()) }
	}
})

private class ResolverFixture(
	direct: Boolean = true,
	burned: Boolean = false,
	deliveryUrl: String = "/subtitles/0.vtt",
) {
	val api = mockk<ApiClient>()
	val mediaInfoApi = mockk<MediaInfoApi>()
	val subtitleApi = mockk<SubtitleApi>()
	val videosApi = mockk<VideosApi>()
	val request = slot<PlaybackInfoDto>()
	val entry = QueueEntry().apply { baseItem = BaseItemDto(id = UUID.randomUUID(), mediaType = MediaType.VIDEO, type = BaseItemKind.MOVIE) }
	val resolver = JellyfinMediaStreamResolver(api) { mockk<DeviceProfile>() }

	init {
		val source = source(
			direct,
			listOf(
				stream(0, MediaStreamType.SUBTITLE, "spa", external = true).copy(deliveryUrl = deliveryUrl),
				stream(2, MediaStreamType.AUDIO, "eng"),
				stream(3, MediaStreamType.AUDIO, "fra"),
				stream(4, MediaStreamType.SUBTITLE, "eng").copy(
					deliveryMethod = if (burned) SubtitleDeliveryMethod.ENCODE else SubtitleDeliveryMethod.EMBED,
				),
			),
		)
		every { api.getOrCreateApi(MediaInfoApi::class, any()) } returns mediaInfoApi
		every { api.getOrCreateApi(SubtitleApi::class, any()) } returns subtitleApi
		every { api.getOrCreateApi(VideosApi::class, any()) } returns videosApi
		every { api.createUrl(any(), any(), any(), any()) } answers { "https://server/jellyfin${firstArg<String>()}" }
		every { videosApi.getVideoStreamUrl(itemId = any(), container = any(), mediaSourceId = any(), static = true,
			tag = any(), liveStreamId = any()) } returns "https://server/video.mkv"
		every { subtitleApi.getSubtitleUrl(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
			"https://server/jellyfin/subtitle-at-${arg<Long>(7)}.vtt"
		}
		coEvery { mediaInfoApi.getPostedPlaybackInfo(any(), capture(request)) } returns Response(
			PlaybackInfoResponse(listOf(source), "play-session"), 200, emptyMap(),
		)
	}
}

private fun stream(index: Int, type: MediaStreamType, language: String, external: Boolean = false) = MediaStream(
	index = index, type = type, language = language, codec = if (type == MediaStreamType.AUDIO) "aac" else "srt",
	isInterlaced = false, isDefault = index == 2, isForced = false, isHearingImpaired = false,
	isExternal = external, isTextSubtitleStream = type == MediaStreamType.SUBTITLE, supportsExternalStream = external,
	deliveryMethod = if (external) SubtitleDeliveryMethod.EXTERNAL else null,
)

private fun source(direct: Boolean, streams: List<MediaStream>) = MediaSourceInfo(
	id = "source", container = "mkv", protocol = MediaProtocol.FILE, type = MediaSourceType.DEFAULT,
	isRemote = false, readAtNativeFramerate = false, ignoreDts = false, ignoreIndex = false, genPtsInput = false,
	supportsTranscoding = true, supportsDirectStream = false, supportsDirectPlay = direct, isInfiniteStream = false,
	requiresOpening = false, requiresClosing = false, requiresLooping = false, supportsProbing = false,
	transcodingSubProtocol = MediaStreamProtocol.HLS, hasSegments = false, mediaStreams = streams,
	transcodingUrl = "/video/master.m3u8", transcodingContainer = "m3u8",
	defaultAudioStreamIndex = 2, defaultSubtitleStreamIndex = 4,
)
