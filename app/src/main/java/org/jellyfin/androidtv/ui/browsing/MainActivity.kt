package org.jellyfin.androidtv.ui.browsing

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.integration.LeanbackChannelWorker
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideLocalInteractionTracker
import org.jellyfin.androidtv.ui.composable.compat.AppNavigationHost
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.AudioNowPlayingFragment
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment
import org.jellyfin.androidtv.ui.screensaver.InAppScreensaver
import org.jellyfin.androidtv.ui.settings.compat.MainActivitySettings
import org.jellyfin.androidtv.ui.startup.StartupActivity
import org.jellyfin.androidtv.ui.syncplay.SyncPlayBackAction
import org.jellyfin.androidtv.ui.syncplay.SyncPlayPlayerHost
import org.jellyfin.androidtv.ui.syncplay.SyncPlayPlayerMode
import org.jellyfin.androidtv.ui.syncplay.SyncPlayPlayerState
import org.jellyfin.androidtv.util.applyTheme
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.syncplay.SyncPlayService
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.lang.ref.WeakReference

class MainActivity : FragmentActivity() {
	private val navigationRepository by inject<NavigationRepository>()
	private val sessionRepository by inject<SessionRepository>()
	private val userRepository by inject<UserRepository>()
	private val interactionTrackerViewModel by viewModel<InteractionTrackerViewModel>()
	private val workManager by inject<WorkManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val syncPlayPlayerState by inject<SyncPlayPlayerState>()
	private var browsingFocus = WeakReference<View>(null)

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		if (!validateAuthentication()) return

		interactionTrackerViewModel.keepScreenOn.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { keepScreenOn ->
				if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
				else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
			}.launchIn(lifecycleScope)

		if (savedInstanceState == null && navigationRepository.canGoBack) navigationRepository.reset(clearHistory = true)

		sessionRepository.currentSession
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.filter { it == null }
			.onEach { validateAuthentication() }
			.launchIn(lifecycleScope)

		observeSyncPlayNavigation()

		navigationRepository.currentAction
			.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
			.onEach {
				interactionTrackerViewModel.notifyInteraction(canCancel = false, userInitiated = false)
			}.launchIn(lifecycleScope)

		setContent {
			JellyfinTheme {
				ProvideLocalInteractionTracker(
					interactionTracker = { interactionTrackerViewModel.notifyInteraction(false, userInitiated = true) }
				) {
					AppBackground()
					val playerMode by syncPlayPlayerState.mode.collectAsState()
					Box(Modifier.fillMaxSize()) {
						AppNavigationHost(
							navigationRepository = navigationRepository,
							interactive = playerMode != SyncPlayPlayerMode.FULLSCREEN,
						)
						SyncPlayPlayerHost(
							state = syncPlayPlayerState,
							playbackManager = playbackManager,
							onBrowseFocusRequested = ::restoreBrowsingFocus,
						)
					}
					BackHandler(enabled = playerMode != SyncPlayPlayerMode.HIDDEN) {
						if (playerMode == SyncPlayPlayerMode.MINIMIZED) rememberBrowsingFocus()
						if (syncPlayPlayerState.handleBack() == SyncPlayBackAction.EXIT) finish()
					}
					InAppScreensaver()
					MainActivitySettings()
				}
			}
		}
	}

	private fun observeSyncPlayNavigation() {
		val service = playbackManager.getService<SyncPlayService>() ?: return
		combine(service.group, playbackManager.queue.entry) { group, entry ->
			Triple(group?.groupId, entry?.baseItem?.mediaType, service.isGroupEntry(entry))
		}
			.distinctUntilChanged()
			.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
			.onEach { playback ->
				if (playback.first == null) {
					// Explicitly leaving a group can retain the current video as local playback.
					// Logout, disconnect, and app exit clear the queue and dismiss the host.
					if (playback.second != MediaType.VIDEO) syncPlayPlayerState.hide()
					return@onEach
				}
				if (!playback.third) {
					// Keep the current viewport while the next video's metadata is loading.
					if (playback.second != MediaType.VIDEO) syncPlayPlayerState.hide()
					return@onEach
				}
				when (playback.second) {
					MediaType.VIDEO -> {
						if (syncPlayPlayerState.mode.value == SyncPlayPlayerMode.HIDDEN) {
							rememberBrowsingFocus()
							// Replace an old playback page, keeping the browsing route beneath it.
							val currentFragment = supportFragmentManager.findFragmentById(R.id.container)
							if (currentFragment is VideoPlayerFragment || currentFragment is CustomPlaybackOverlayFragment ||
								currentFragment is AudioNowPlayingFragment
							) {
								if (!navigationRepository.goBack()) navigationRepository.reset(clearHistory = true)
							}
							syncPlayPlayerState.show()
						}
					}
					MediaType.AUDIO -> {
						syncPlayPlayerState.hide()
						if (supportFragmentManager.findFragmentById(R.id.container) !is AudioNowPlayingFragment) {
							navigationRepository.navigate(Destinations.nowPlaying)
						}
					}
					else -> syncPlayPlayerState.hide()
				}
			}.launchIn(lifecycleScope)
	}

	private fun rememberBrowsingFocus() {
		findViewById<View>(R.id.container)?.findFocus()?.let { browsingFocus = WeakReference(it) }
	}

	private fun restoreBrowsingFocus() {
		val previous = browsingFocus.get()
		if (previous?.isAttachedToWindow == true && previous.isShown && previous.requestFocus()) return
		findViewById<View>(R.id.container)?.requestFocus()
	}

	@SuppressLint("RestrictedApi") // Activity key dispatch must delegate through FragmentActivity's AndroidX bridge.
	override fun dispatchKeyEvent(event: KeyEvent): Boolean {
		if (syncPlayPlayerState.mode.value == SyncPlayPlayerMode.MINIMIZED) rememberBrowsingFocus()
		return super.dispatchKeyEvent(event)
	}

	override fun onResume() {
		super.onResume()

		if (!validateAuthentication()) return

		applyTheme()

		interactionTrackerViewModel.activityPaused = false
	}

	private fun validateAuthentication(): Boolean {
		if (isFinishing) return false
		if (sessionRepository.currentSession.value == null || userRepository.currentUser.value == null) {
			Timber.w("Activity ${this::class.qualifiedName} started without a session, bouncing to StartupActivity")
			startActivity(Intent(this, StartupActivity::class.java))
			finish()
			return false
		}

		return true
	}

	override fun onPause() {
		super.onPause()

		interactionTrackerViewModel.activityPaused = true
	}

	override fun onStop() {
		super.onStop()

		workManager.enqueue(OneTimeWorkRequestBuilder<LeanbackChannelWorker>().build())

		lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
			withContext(NonCancellable) {
				Timber.i("MainActivity stopped")
				val syncPlay = playbackManager.getService<SyncPlayService>()
				if (syncPlay?.active == true) {
					if (isChangingConfigurations) return@withContext
					syncPlay.endSession()
				} else if (!isChangingConfigurations && syncPlayPlayerState.mode.value != SyncPlayPlayerMode.HIDDEN) {
					playbackManager.state.stop()
				}
				if (!isChangingConfigurations) syncPlayPlayerState.hide()
				if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
					withContext(Dispatchers.IO) { sessionRepository.restoreSession(destroyOnly = true) }
				}
			}
		}
	}

	// Forward key events to fragments

	private fun Fragment.onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		var result = childFragmentManager.fragments.any { it.onKeyEvent(keyCode, event) }
		if (!result && this is View.OnKeyListener) result = onKey(currentFocus, keyCode, event)
		return result
	}

	private fun onKeyEvent(keyCode: Int, event: KeyEvent?): Boolean {
		if (syncPlayPlayerState.mode.value == SyncPlayPlayerMode.FULLSCREEN || syncPlayPlayerState.miniPlayerFocused.value) return false
		return supportFragmentManager.fragments.filter { it.isResumed }.any { it.onKeyEvent(keyCode, event) }
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyDown(keyCode, event)

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)

	override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean =
		onKeyEvent(keyCode, event) || super.onKeyUp(keyCode, event)
}
