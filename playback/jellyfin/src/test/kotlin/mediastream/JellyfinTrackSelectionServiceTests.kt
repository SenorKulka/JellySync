@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.jellyfin.playback.jellyfin.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.PlayerState
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.MediaStreamSelection
import org.jellyfin.playback.core.mediastream.reloadCurrentStream
import org.jellyfin.playback.core.model.PlayerTracks
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import kotlin.time.Duration.Companion.seconds

class JellyfinTrackSelectionServiceTests : FunSpec({
	test("quality changes preserve the current local audio and subtitle selection") {
		withSelectionService {
			service.setMaxBitrate(4_000_000)
			testScope.runCurrent()
			coVerify { manager.reloadCurrentStream(MediaStreamSelection("3", "0", 4_000_000), null) }
			service.busy.value shouldBe false
			service.error.value shouldBe null
		}
	}

	test("replacing the queue entry cancels pending local options and clears busy state") {
		withSelectionService {
			val response = CompletableDeferred<Boolean>()
			coEvery { manager.reloadCurrentStream(any(), any()) } coAnswers { response.await() }
			service.setMaxBitrate(4_000_000)
			testScope.runCurrent()
			service.busy.value shouldBe true
			currentEntry.value = QueueEntry()
			testScope.runCurrent()
			service.busy.value shouldBe false
			response.complete(false)
			testScope.runCurrent()
			service.error.value shouldBe null
		}
	}

	test("an unsuccessful option change shows an error and allows retry") {
		withSelectionService {
			coEvery { manager.reloadCurrentStream(any(), any()) } returns false
			service.setMaxBitrate(4_000_000)
			testScope.runCurrent()
			service.error.value.shouldNotBeNull()
			service.busy.value shouldBe false
			coEvery { manager.reloadCurrentStream(any(), any()) } returns true
			service.setMaxBitrate(null)
			testScope.runCurrent()
			service.error.value shouldBe null
		}
	}

	test("a later seek supersedes stream resolution without displaying an options error") {
		withSelectionService {
			val response = CompletableDeferred<Boolean>()
			coEvery { manager.reloadCurrentStream(any(), any()) } coAnswers { response.await() }
			listener.captured.onSeekRequested(currentEntry.value.shouldNotBeNull(), 10.seconds)
			testScope.runCurrent()
			seekGeneration++
			response.complete(false)
			testScope.runCurrent()
			service.error.value shouldBe null
			service.busy.value shouldBe false
		}
	}

	test("service disposal removes its backend listener") {
		withSelectionService {
			scope.cancel()
			testScope.runCurrent()
			verify(exactly = 1) { manager.removeBackendListener(listener.captured) }
		}
	}
})

private suspend fun withSelectionService(block: suspend SelectionServiceFixture.() -> Unit) = runTest {
	val fixture = SelectionServiceFixture(this)
	try {
		fixture.service.onInitialize()
		fixture.block()
	} finally {
		fixture.scope.cancel()
		runCurrent()
		unmockkObject(fixture.service)
		unmockkStatic("org.jellyfin.playback.core.mediastream.MediaStreamServiceKt")
		Dispatchers.resetMain()
	}
}

private class SelectionServiceFixture(val testScope: TestScope) {
	val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))
	val manager = mockk<PlaybackManager>(relaxed = true)
	val state = mockk<PlayerState>()
	val backend = mockk<PlayerBackend>()
	val queue = mockk<QueueService>()
	val currentEntry = MutableStateFlow<QueueEntry?>(QueueEntry())
	val listener = slot<PlayerBackendEventListener>()
	val service = JellyfinTrackSelectionService()
	var seekGeneration = 0L

	init {
		Dispatchers.setMain(StandardTestDispatcher(testScope.testScheduler))
		mockkObject(service)
		mockkStatic("org.jellyfin.playback.core.mediastream.MediaStreamServiceKt")
		every { service.manager } returns manager
		every { service.state } returns state
		every { service.coroutineScope } returns scope
		every { manager.backend } returns backend
		every { backend.seekGeneration } answers { seekGeneration }
		every { manager.getService(QueueService::class) } returns queue
		every { manager.addBackendListener(capture(listener)) } returns Unit
		every { queue.entry } returns currentEntry
		every { state.tracks } returns MutableStateFlow(PlayerTracks(selectedAudioId = "3", selectedSubtitleId = "0"))
		coEvery { manager.reloadCurrentStream(any(), any()) } returns true
	}
}
