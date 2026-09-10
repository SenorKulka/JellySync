package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.InitialPlayback
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.initialPlayback
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import kotlin.time.Duration

internal class MediaStreamService(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}
	private var loadGeneration = 0L
	private data class ReloadRequest(
		val entry: QueueEntry,
		val originalStream: PlayableMediaStream,
		val generation: Long,
		val seekGeneration: Long,
		val position: Duration?,
	)

	override suspend fun onInitialize() {
		coroutineScope.launch(Dispatchers.Main) {
			manager.queue.entry.collectLatest { entry ->
				val generation = ++loadGeneration
				Timber.d("Queue entry changed to $entry")

				if (entry == null) {
					val backend = requireNotNull(manager.backend)
					backend.stop()
				} else {
					playEntry(entry, generation)
					entry.ensurePreloadTimedEvent()
				}
			}
		}
	}

	private suspend fun resolveMediaStream(
		entry: QueueEntry,
		selection: MediaStreamSelection? = null,
		startPosition: Duration = entry.initialPlayback.position,
	): PlayableMediaStream? = mediaStreamResolvers.firstNotNullOfOrNull { resolver ->
		runCatching {
			withContext(Dispatchers.IO) {
				resolver.getStream(entry, selection, startPosition)
			}
		}.onFailure {
			if (it is CancellationException) throw it
			Timber.e(it, "Media stream resolver failed for $entry")
		}.getOrNull()
	}

	/** Replace a participant's stream without replacing the shared playlist entry or timeline. */
	suspend fun reloadCurrentStream(selection: MediaStreamSelection, position: Duration?): Boolean = withContext(Dispatchers.Main.immediate) {
		val entry = manager.queue.entry.value ?: return@withContext false
		val originalStream = entry.mediaStream ?: return@withContext false
		val generation = ++loadGeneration
		val request = ReloadRequest(entry, originalStream, generation, manager.backend.seekGeneration, position)
		var applied = false
		try {
			val stream = resolveReload(request, selection) ?: return@withContext false

			// A scheduled party command may have changed the position or paused while the
			// HTTP request was in flight. Apply that live state instead of its old snapshot.
			entry.initialPlayback = InitialPlayback(
				position = position ?: state.positionInfo.active,
				playWhenReady = state.playState.value == PlayState.PLAYING,
			)
			entry.mediaStream = stream
			manager.backend.playItem(entry)
			applied = true
			true
		} finally {
			if (!applied && ownsCurrentStream(request)) {
				withContext(NonCancellable) { manager.backend.cancelStreamReload(entry) }
			}
		}
	}

	private fun ownsCurrentStream(request: ReloadRequest): Boolean = loadGeneration == request.generation &&
		manager.queue.entry.value === request.entry && request.entry.mediaStream === request.originalStream

	private fun isCurrent(request: ReloadRequest): Boolean = ownsCurrentStream(request) &&
		(request.position == null || manager.backend.seekGeneration == request.seekGeneration)

	private suspend fun resolveReload(request: ReloadRequest, selection: MediaStreamSelection): PlayableMediaStream? {
		while (isCurrent(request)) {
			val position = request.position ?: state.positionInfo.active
			val stream = resolveMediaStream(request.entry, selection, position) ?: return null
			if (!isCurrent(request)) return null
			val currentPosition = request.position ?: state.positionInfo.active
			if (currentPosition >= stream.startPosition) return stream
			// A backward seek arrived while a transcode was being negotiated. Resolve at
			// that new position rather than jumping forward to the old transcode's start.
		}
		return null
	}

	private fun QueueEntry.ensurePreloadTimedEvent() {
		if (preloadDuration <= Duration.ZERO) return

		val hasEvent = timedEvents?.any { it.key == TIMED_EVENT_PRELOAD } == true
		if (hasEvent) return

		addTimedEvent(
			TimedEvent.Block(
				key = TIMED_EVENT_PRELOAD,
				start = -preloadDuration,
				end = Duration.INFINITE,
				onActivate = { preloadNextEntry() }
			)
		)
	}

	private suspend fun playEntry(entry: QueueEntry, generation: Long) {
		val backend = requireNotNull(manager.backend)
		val stream = entry.mediaStream ?: resolveMediaStream(entry)
		if (manager.queue.entry.value !== entry || generation != loadGeneration) return

		if (stream != null) {
			entry.mediaStream = stream
			backend.playItem(entry)
		} else {
			if (manager.queue.entry.value !== entry) return
			Timber.e("Unable to resolve stream for entry $entry")
			manager.backendService.BackendEventListener().onMediaStreamError(entry)
			if (manager.queue.entry.value !== entry) return

			// TODO: Somehow notify the user that we skipped an unplayable entry
			val nextEntry = manager.queue.peekNext()
			if (manager.queue.entry.value !== entry) return
			if (nextEntry != null) {
				manager.queue.next(usePlaybackOrder = true, useRepeatMode = false)
			} else {
				backend.stop()
			}
		}
	}

	private fun preloadNextEntry() = coroutineScope.launch(Dispatchers.Main) {
		val currentEntry = manager.queue.entry.value
		// Peek into the next item to preload
		val nextItem = manager.queue.peekNext() ?: return@launch

		// Preload media stream information
		val stream = nextItem.mediaStream ?: resolveMediaStream(nextItem)

		if (stream != null && manager.queue.entry.value === currentEntry && manager.queue.indexOf(nextItem) != null) {
			nextItem.mediaStream = stream
			// Preload media in backend
			val backend = requireNotNull(manager.backend)
			backend.prepareItem(nextItem)
		}
	}
}

/** Returns false if resolution failed or a newer queue/selection superseded this request. */
suspend fun PlaybackManager.reloadCurrentStream(selection: MediaStreamSelection, position: Duration? = null): Boolean =
	getService<MediaStreamService>()?.reloadCurrentStream(selection, position) ?: false
