@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.jellyfin.playback.media3.exoplayer

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.playback.core.timedevent.BlockActivation
import org.jellyfin.playback.core.timedevent.TimedEvent
import kotlin.time.Duration.Companion.seconds

class TimedEventOffsetTests : FunSpec({
	test("transcoded segment events use local timestamps and retain absolute seek callbacks") {
		val message = mockk<PlayerMessage>(relaxed = true)
		every { message.send() } returns message
		val player = mockk<ExoPlayer> {
			every { duration } returns 100_000
			every { createMessage(any()) } returns message
		}
		val activations = mutableListOf<BlockActivation>()
		val state = TimedEventState()
		state.setTimedEvents(player, listOf(TimedEvent.Block(start = 40.seconds, end = 50.seconds,
			onActivate = activations::add)), 30.seconds)
		verify { message.setPosition(10_000) }
		verify { message.setPosition(20_000) }
		state.onSeek(35.seconds, 45.seconds, 130.seconds)
		activations shouldBe listOf(BlockActivation.Seek(35.seconds, 45.seconds))
	}
	test("end relative events keep their distance from the end after a stream replacement") {
		val message = mockk<PlayerMessage>(relaxed = true)
		every { message.send() } returns message
		val player = mockk<ExoPlayer> {
			every { duration } returns 100_000
			every { createMessage(any()) } returns message
		}
		val state = TimedEventState()
		val event = TimedEvent.Instant(position = (-10).seconds, onActivate = {})
		state.setTimedEvents(player, listOf(event), 30.seconds)
		verify(exactly = 1) { message.setPosition(90_000) }
		every { player.duration } returns 80_000
		state.setTimedEvents(player, listOf(event), 50.seconds)
		verify(exactly = 1) { message.cancel() }
		verify(exactly = 1) { message.setPosition(70_000) }
	}
})
