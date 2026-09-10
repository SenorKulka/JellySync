package org.jellyfin.playback.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.playback.core.backend.BackendService
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.model.MediaTrack
import org.jellyfin.playback.core.model.PlayerTracks
import kotlin.time.Duration.Companion.seconds

class PlayerTrackSelectionTests : FunSpec({
	test("participant audio and subtitle choices bypass group transport commands") {
		val backend = mockk<PlayerBackend>(relaxed = true)
		val service = BackendService().apply { switchBackend(backend) }
		val commands = mutableListOf<PlayerCommand>()
		val state = MutablePlayerState(
			PlaybackManagerOptions(mockk(relaxed = true), { 10.seconds }, { 10.seconds }),
			service,
			null,
		) { command -> commands.add(command); true }
		state.selectAudioTrack("3")
		state.selectSubtitleTrack("4")
		state.selectSubtitleTrack(null)
		verify(exactly = 1) { backend.selectAudioTrack("3") }
		verify(exactly = 1) { backend.selectSubtitleTrack("4") }
		verify(exactly = 1) { backend.selectSubtitleTrack(null) }
		commands shouldBe emptyList()
	}
	test("selected track state follows backend confirmation and clears with the backend") {
		val service = BackendService()
		val state = MutablePlayerState(
			PlaybackManagerOptions(mockk(relaxed = true), { 10.seconds }, { 10.seconds }), service, null,
		)
		val selected = PlayerTracks(audio = listOf(MediaTrack("3", "French")), selectedAudioId = "3")
		service.BackendEventListener().onTracksChanged(selected)
		state.tracks.value shouldBe selected
		service.BackendEventListener().onTracksChanged(PlayerTracks())
		state.tracks.value shouldBe PlayerTracks()
	}
})
