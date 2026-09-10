package org.jellyfin.androidtv.ui.syncplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SyncPlayPlayerMode {
	HIDDEN,
	FULLSCREEN,
	MINIMIZED,
}

enum class SyncPlayBackAction {
	NONE,
	MINIMIZE,
	FOCUS_MINI,
	EXIT,
}

/** Presentation only: changing the player's size must never change playback or group membership. */
class SyncPlayPlayerState {
	private val _mode = MutableStateFlow(SyncPlayPlayerMode.HIDDEN)
	val mode = _mode.asStateFlow()
	private val _miniPlayerFocused = MutableStateFlow(false)
	val miniPlayerFocused = _miniPlayerFocused.asStateFlow()

	private val _miniPlayerFocusRequests = MutableStateFlow(0L)
	val miniPlayerFocusRequests = _miniPlayerFocusRequests.asStateFlow()

	private val _browseFocusRequests = MutableStateFlow(0L)
	val browseFocusRequests = _browseFocusRequests.asStateFlow()

	fun show() {
		_miniPlayerFocused.value = false
		_mode.value = SyncPlayPlayerMode.FULLSCREEN
	}

	fun minimize() {
		if (_mode.value != SyncPlayPlayerMode.FULLSCREEN) return
		_miniPlayerFocused.value = false
		_mode.value = SyncPlayPlayerMode.MINIMIZED
		_browseFocusRequests.value++
	}

	fun expand() {
		if (_mode.value != SyncPlayPlayerMode.HIDDEN) show()
	}

	fun hide() {
		_miniPlayerFocused.value = false
		_mode.value = SyncPlayPlayerMode.HIDDEN
	}

	fun setMiniPlayerFocused(focused: Boolean) {
		_miniPlayerFocused.value = focused && _mode.value == SyncPlayPlayerMode.MINIMIZED
	}

	fun handleBack(): SyncPlayBackAction = when (_mode.value) {
		SyncPlayPlayerMode.HIDDEN -> SyncPlayBackAction.NONE
		SyncPlayPlayerMode.FULLSCREEN -> {
			minimize()
			SyncPlayBackAction.MINIMIZE
		}
		SyncPlayPlayerMode.MINIMIZED -> {
			if (_miniPlayerFocused.value) {
				SyncPlayBackAction.EXIT
			} else {
				_miniPlayerFocusRequests.value++
				SyncPlayBackAction.FOCUS_MINI
			}
		}
	}
}
