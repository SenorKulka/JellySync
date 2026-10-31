package org.jellyfin.playback.jellyfin.mediastream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.MediaStreamSelection
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.reloadCurrentStream
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import timber.log.Timber
import kotlin.time.Duration

/** Reloads local playback options without changing the server-owned playlist or playback commands. */
class JellyfinTrackSelectionService : PlayerService() {
	private val _busy = MutableStateFlow(false)
	val busy = _busy.asStateFlow()
	private val _error = MutableStateFlow<String?>(null)
	val error = _error.asStateFlow()
	private var requestJob: Job? = null
	private var requestGeneration = 0L
	private var desiredEntry: QueueEntry? = null
	private var desiredSelection: MediaStreamSelection? = null

	private val listener = object : PlayerBackendEventListener() {
		override fun onTrackSelectionRequested(entry: QueueEntry, audioId: String?, subtitleId: String?) {
			request(entry) { previous ->
				val tracks = state.tracks.value
				previous.copy(
					audioId = if (audioId != tracks.selectedAudioId) audioId else previous.audioId,
					subtitleId = if (subtitleId != tracks.selectedSubtitleId) subtitleId else previous.subtitleId,
				)
			}
		}

		override fun onSeekRequested(entry: QueueEntry, position: Duration) {
			request(entry, position) { it }
		}
	}

	override suspend fun onInitialize() = withContext(Dispatchers.Main.immediate) {
		manager.addBackendListener(listener)
		coroutineScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
			manager.queue.entry.collect { entry ->
				if (desiredEntry != null && desiredEntry !== entry) {
					requestGeneration++
					requestJob?.cancel()
					desiredEntry = null
					desiredSelection = null
					_busy.value = false
					_error.value = null
				}
			}
		}
		coroutineScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
			try {
				awaitCancellation()
			} finally {
				withContext(NonCancellable + Dispatchers.Main.immediate) { manager.removeBackendListener(listener) }
			}
		}
		Unit
	}

	fun clearError() { _error.value = null }

	fun setMaxBitrate(value: Int?) {
		require(value == null || value > 0)
		manager.queue.entry.value?.let { entry -> request(entry) { it.copy(maxBitrate = value) } }
	}

	private fun currentSelection(entry: QueueEntry): MediaStreamSelection {
		if (desiredEntry === entry && requestJob?.isActive == true) return requireNotNull(desiredSelection)
		val tracks = state.tracks.value
		return MediaStreamSelection(tracks.selectedAudioId, tracks.selectedSubtitleId, entry.mediaStream?.selection?.maxBitrate)
	}

	private fun request(
		entry: QueueEntry,
		position: Duration? = null,
		update: (MediaStreamSelection) -> MediaStreamSelection,
	) {
		coroutineScope.launch(Dispatchers.Main.immediate) {
			if (manager.queue.entry.value !== entry) return@launch
			val selection = update(currentSelection(entry))
			desiredEntry = entry
			desiredSelection = selection
			val generation = ++requestGeneration
			requestJob?.cancel()
			_error.value = null
			_busy.value = true
			requestJob = coroutineScope.launch(Dispatchers.Main.immediate) { reload(entry, selection, position, generation) }
		}
	}

	private fun isCurrent(entry: QueueEntry, generation: Long) =
		manager.queue.entry.value === entry && generation == requestGeneration

	@Suppress("TooGenericExceptionCaught") // A failed local stream option leaves the previous playable stream in place.
	private suspend fun reload(entry: QueueEntry, selection: MediaStreamSelection, position: Duration?, generation: Long) {
		val seekGeneration = manager.backend.seekGeneration
		try {
			val changed = manager.reloadCurrentStream(selection, position)
			val supersededSeek = position != null && manager.backend.seekGeneration != seekGeneration
			if (!changed && !supersededSeek && isCurrent(entry, generation)) {
				_error.value = "Unable to change playback options. Your previous settings were kept."
			}
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			Timber.w(exception, "Unable to reload playback options")
			if (isCurrent(entry, generation)) _error.value = "Unable to change playback options."
		} finally {
			if (generation == requestGeneration) _busy.value = false
		}
	}
}
