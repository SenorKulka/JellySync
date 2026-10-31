package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.queue.QueueEntry

/**
 * A base class that implements the event listening part of [PlayerBackend].
 */
abstract class BasePlayerBackend : PlayerBackend {
	private var _listener: PlayerBackendEventListener? = null
	protected val listener: PlayerBackendEventListener? get() = _listener
	override val seekGeneration: Long get() = 0

	override fun setListener(eventListener: PlayerBackendEventListener?) {
		_listener = eventListener
	}

	override fun cancelStreamReload(entry: QueueEntry) = Unit
}
