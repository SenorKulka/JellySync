package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.button.IconButton

data class VideoPlayerControlActions(
	val onPlaybackInfoClick: () -> Unit = {},
	val onSyncPlayClick: () -> Unit = {},
	val onAudioClick: () -> Unit = {},
	val onSubtitlesClick: () -> Unit = {},
	val onChaptersClick: () -> Unit = {},
	val onScaleClick: () -> Unit = {},
	val onQualityClick: () -> Unit = {},
)

@Composable
internal fun PlayerOptionButton(icon: Int, label: Int, onClick: () -> Unit, enabled: Boolean = true) = IconButton(
	onClick = onClick,
	enabled = enabled,
) {
	Icon(imageVector = ImageVector.vectorResource(icon), contentDescription = stringResource(label))
}
