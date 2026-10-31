@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.initialPlayback
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MediaStreamReloadTests : FunSpec({
	test("track reload retains entry identity and the position and pause command received during resolution") {
		runTest {
			val started = CompletableDeferred<Duration>()
			val release = CompletableDeferred<Unit>()
			withReloadFixture({ entry, _, position ->
				started.complete(position)
				release.await()
				stream(entry, "french")
			}) {
				val reload = async { service.reloadCurrentStream(MediaStreamSelection(audioId = "2"), null) }
				started.await() shouldBe 30.seconds
				position = 42.seconds
				playState.value = PlayState.PAUSED
				release.complete(Unit)
				reload.await() shouldBe true
				selected.value shouldBe entry
				entry.mediaStream?.identifier shouldBe "french"
				entry.initialPlayback.position shouldBe 42.seconds
				entry.initialPlayback.playWhenReady shouldBe false
				verify(exactly = 1) { backend.playItem(entry) }
			}
		}
	}

	test("a resolved track change cannot restart an item after the shared queue replaces it") {
		runTest {
			val started = CompletableDeferred<Unit>()
			val release = CompletableDeferred<Unit>()
			withReloadFixture({ entry, _, _ ->
				started.complete(Unit)
				release.await()
				stream(entry, "stale")
			}) {
				val reload = async { service.reloadCurrentStream(MediaStreamSelection(audioId = "2"), null) }
				started.await()
				val next = QueueEntry().also { it.mediaStream = stream(it, "next") }
				selected.value = next
				runCurrent()
				release.complete(Unit)
				reload.await() shouldBe false
				entry.mediaStream?.identifier shouldBe "original"
				selected.value shouldBe next
				verify(exactly = 0) { backend.playItem(entry) }
				verify(exactly = 0) { backend.cancelStreamReload(entry) }
				verify(exactly = 1) { backend.playItem(next) }
			}
		}
	}

	test("the newest subtitle selection wins when responses finish in reverse order") {
		runTest {
			val started = CompletableDeferred<Unit>()
			val release = CompletableDeferred<Unit>()
			withReloadFixture({ entry, selection, _ ->
				if (selection?.subtitleId == "slow") {
					started.complete(Unit)
					release.await()
				}
				stream(entry, selection?.subtitleId.orEmpty())
			}) {
				val first = async { service.reloadCurrentStream(MediaStreamSelection(subtitleId = "slow"), null) }
				started.await()
				service.reloadCurrentStream(MediaStreamSelection(subtitleId = "latest"), null) shouldBe true
				release.complete(Unit)
				first.await() shouldBe false
				entry.mediaStream?.identifier shouldBe "latest"
				verify(exactly = 1) { backend.playItem(entry) }
			}
		}
	}

	test("failed stream resolution leaves the current playback intact") {
		runTest {
			withReloadFixture({ _, _, _ -> null }) {
				service.reloadCurrentStream(MediaStreamSelection(maxBitrate = 1_000_000), null) shouldBe false
				entry.mediaStream?.identifier shouldBe "original"
				verify(exactly = 0) { backend.playItem(any()) }
				verify(exactly = 0) { backend.stop() }
				verify(exactly = 1) { backend.cancelStreamReload(entry) }
			}
		}
	}

	test("an explicit seek reload applies the requested absolute position") {
		runTest {
			withReloadFixture({ entry, _, position -> stream(entry, "seek").copy(startPosition = position) }) {
				service.reloadCurrentStream(MediaStreamSelection(), 5.seconds) shouldBe true
				entry.initialPlayback.position shouldBe 5.seconds
				entry.mediaStream?.startPosition shouldBe 5.seconds
				entry.initialPlayback.playWhenReady shouldBe true
			}
		}
	}

	test("a later in-buffer seek supersedes an earlier request to rebuild a transcode") {
		runTest {
			val started = CompletableDeferred<Unit>()
			val release = CompletableDeferred<Unit>()
			withReloadFixture({ entry, _, position ->
				started.complete(Unit)
				release.await()
				stream(entry, "obsolete-seek").copy(startPosition = position)
			}) {
				var seekGeneration = 1L
				every { backend.seekGeneration } answers { seekGeneration }
				val reload = async { service.reloadCurrentStream(MediaStreamSelection(), 5.seconds) }
				started.await()
				seekGeneration++
				position = 90.seconds
				release.complete(Unit)
				reload.await() shouldBe false
				entry.mediaStream?.identifier shouldBe "original"
				verify(exactly = 0) { backend.playItem(entry) }
				verify(exactly = 1) { backend.cancelStreamReload(entry) }
			}
		}
	}

	test("quality change re-resolves a transcode after a backward seek during negotiation") {
		runTest {
			val started = CompletableDeferred<Unit>()
			val release = CompletableDeferred<Unit>()
			val requestedPositions = mutableListOf<Duration>()
			withReloadFixture({ entry, _, position ->
				requestedPositions.add(position)
				if (requestedPositions.size == 1) {
					started.complete(Unit)
					release.await()
				}
				stream(entry, "quality").copy(startPosition = position)
			}) {
				val reload = async { service.reloadCurrentStream(MediaStreamSelection(maxBitrate = 1_000_000), null) }
				started.await()
				position = 5.seconds
				release.complete(Unit)
				reload.await() shouldBe true
				requestedPositions shouldBe listOf(30.seconds, 5.seconds)
				entry.initialPlayback.position shouldBe 5.seconds
				entry.mediaStream?.startPosition shouldBe 5.seconds
				verify(exactly = 1) { backend.playItem(entry) }
			}
		}
	}
})

private fun stream(entry: QueueEntry, id: String) = PlayableMediaStream(
	identifier = id,
	conversionMethod = MediaConversionMethod.None,
	container = MediaStreamContainer("mp4"),
	tracks = emptyList(),
	queueEntry = entry,
	url = "https://example.test/$id",
)

private class ReloadFixture(
	val scope: CoroutineScope,
	resolve: suspend (QueueEntry, MediaStreamSelection?, Duration) -> PlayableMediaStream?,
) {
	val entry = QueueEntry().also { it.mediaStream = stream(it, "original") }
	val selected = MutableStateFlow<QueueEntry?>(entry)
	val playState = MutableStateFlow(PlayState.PLAYING)
	var position = 30.seconds
	val backend = mockk<PlayerBackend>(relaxed = true)
	private val playerState = mockk<PlayerState>()
	private val manager = mockk<PlaybackManager>()
	private val queue = mockk<QueueService>()
	val service = MediaStreamService(listOf(object : MediaStreamResolver {
		override suspend fun getStream(queueEntry: QueueEntry, selection: MediaStreamSelection?, startPosition: Duration) =
			resolve(queueEntry, selection, startPosition)
	}), Duration.ZERO)

	init {
		mockkObject(service)
		every { service.manager } returns manager
		every { service.state } returns playerState
		every { service.coroutineScope } returns scope
		every { manager.backend } returns backend
		every { manager.getService(QueueService::class) } returns queue
		every { queue.entry } returns selected
		every { playerState.playState } returns playState
		every { playerState.positionInfo } answers { PositionInfo(position, position, 180.seconds) }
	}
}

private suspend fun TestScope.withReloadFixture(
	resolve: suspend (QueueEntry, MediaStreamSelection?, Duration) -> PlayableMediaStream?,
	block: suspend ReloadFixture.() -> Unit,
) {
	val dispatcher = StandardTestDispatcher(testScheduler)
	Dispatchers.setMain(dispatcher)
	val fixture = ReloadFixture(CoroutineScope(SupervisorJob() + dispatcher), resolve)
	try {
		fixture.service.onInitialize()
		runCurrent()
		clearMocks(fixture.backend, answers = false)
		fixture.block()
	} finally {
		fixture.scope.cancel()
		runCurrent()
		unmockkObject(fixture.service)
		Dispatchers.resetMain()
	}
}
