package org.jellyfin.androidtv.ui.syncplay

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SyncPlayPlayerStateTests : FunSpec({
	test("Back minimizes first, then requires real mini-player focus before exit") {
		val state = SyncPlayPlayerState()
		state.show()

		state.handleBack() shouldBe SyncPlayBackAction.MINIMIZE
		state.mode.value shouldBe SyncPlayPlayerMode.MINIMIZED
		state.browseFocusRequests.value shouldBe 1L
		state.miniPlayerFocused.value shouldBe false

		state.handleBack() shouldBe SyncPlayBackAction.FOCUS_MINI
		state.miniPlayerFocusRequests.value shouldBe 1L
		// A delayed or unsuccessful focus request must not cause the next Back to exit.
		state.handleBack() shouldBe SyncPlayBackAction.FOCUS_MINI
		state.setMiniPlayerFocused(true)
		state.handleBack() shouldBe SyncPlayBackAction.EXIT
	}

	test("moving from mini player back to browsing restores the focus-first Back behavior") {
		val state = SyncPlayPlayerState()
		state.show()
		state.minimize()
		state.setMiniPlayerFocused(true)
		state.setMiniPlayerFocused(false)

		state.handleBack() shouldBe SyncPlayBackAction.FOCUS_MINI
		state.mode.value shouldBe SyncPlayPlayerMode.MINIMIZED
	}

	test("restoring full screen clears mini focus and the next Back returns to browsing") {
		val state = SyncPlayPlayerState()
		state.show()
		state.minimize()
		state.setMiniPlayerFocused(true)
		state.expand()

		state.mode.value shouldBe SyncPlayPlayerMode.FULLSCREEN
		state.miniPlayerFocused.value shouldBe false
		state.handleBack() shouldBe SyncPlayBackAction.MINIMIZE
		state.browseFocusRequests.value shouldBe 2L
	}

	test("hiding rejects stale mini focus and releases Back to ordinary navigation") {
		val state = SyncPlayPlayerState()
		state.show()
		state.minimize()
		state.setMiniPlayerFocused(true)
		state.hide()
		state.setMiniPlayerFocused(true)

		state.handleBack() shouldBe SyncPlayBackAction.NONE
		state.miniPlayerFocused.value shouldBe false
		state.expand()
		state.mode.value shouldBe SyncPlayPlayerMode.HIDDEN
	}
})
