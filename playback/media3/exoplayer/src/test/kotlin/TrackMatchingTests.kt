package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.core.model.MediaTrack

class TrackMatchingTests : FunSpec({
	test("server stream indexes are independent from embedded audio ordinals") {
		findTrackIndex(MediaTrack("3", "French", embeddedIndex = 1), listOf("2", "3")) shouldBe 1
	}
	test("external subtitles are matched after ExoPlayer adds the merging source prefix") {
		findTrackIndex(MediaTrack("0", "Spanish"), listOf("4", "1:JF_EXTERNAL:0")) shouldBe 1
	}
	test("embedded subtitle ordinals exclude interleaved external sources") {
		findTrackIndex(MediaTrack("4", "English", embeddedIndex = 0), listOf("1:JF_EXTERNAL:0", "4")) shouldBe 1
	}
	test("missing embedded track is not silently mapped to another language") {
		findTrackIndex(MediaTrack("7", "French", embeddedIndex = 1), listOf("4")) shouldBe null
	}
	test("an external ID does not match the numeric suffix of a different subtitle") {
		findTrackIndex(MediaTrack("2", "Spanish"), listOf("1:JF_EXTERNAL:12")) shouldBe null
	}
})
