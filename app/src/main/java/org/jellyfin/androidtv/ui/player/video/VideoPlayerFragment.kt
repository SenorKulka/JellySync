package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.ui.syncplay.SyncPlayPlayerMode
import org.jellyfin.androidtv.ui.syncplay.SyncPlayPlayerState
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.syncplay.SyncPlayService
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val api by inject<ApiClient>()
	private val syncPlayPlayerState by inject<SyncPlayPlayerState>()
	private val syncPlay get() = playbackManager.getService<SyncPlayService>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// The group owns its queue and start position, including preparation while paused.
		if (syncPlay?.group?.value != null) return

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, videoQueueManager.getCurrentVideoQueue(), false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier)

		// Set position
		arguments?.getInt(EXTRA_POSITION)?.milliseconds?.let {
			lifecycleScope.launch {
				playbackManager.state.seek(it)
			}
		}

		// Pause player until the initial resume
		if (syncPlay?.group?.value == null) playbackManager.state.pause()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		BaseScreen {
			VideoPlayerScreen()
		}
	}

	override fun onPause() {
		super.onPause()

		if (syncPlay?.group?.value == null) playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		if (syncPlay?.group?.value == null) playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		// Party playback belongs to the activity's persistent player host. A page being
		// detached while browsing must not stop it or send a leave request to the group.
		if (syncPlay?.active == true || syncPlayPlayerState.mode.value != SyncPlayPlayerMode.HIDDEN) return

		playbackManager.state.stop()
	}
}
