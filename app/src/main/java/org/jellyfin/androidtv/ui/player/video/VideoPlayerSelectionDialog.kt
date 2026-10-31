package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.dialog.DialogBase
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton

@Suppress("LongParameterList") // Reusable dialog state, choices, and two standard dismissal/selection callbacks.
@Composable
internal fun VideoPlayerSelectionDialog(
	title: String,
	options: List<VideoPlayerOption>,
	selectedId: String?,
	onSelect: (String?) -> Unit,
	onDismissRequest: () -> Unit,
	enabled: Boolean = true,
) {
	DialogBase(visible = true, onDismissRequest = onDismissRequest) {
		ProvideTextStyle(LocalTextStyle.current.copy(color = JellyfinTheme.colorScheme.onBackground)) {
			val focusRequester = remember { FocusRequester() }
			Column(
				verticalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier
					.width(560.dp)
					.heightIn(max = 480.dp)
					.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.large)
					.padding(24.dp)
					.verticalScroll(rememberScrollState())
					.focusRequester(focusRequester)
					.focusGroup(),
			) {
				Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold)
				for (option in options) {
					ListButton(
						enabled = enabled,
						onClick = { onSelect(option.id); onDismissRequest() },
						headingContent = { Text(option.title) },
						trailingContent = { RadioButton(checked = option.id == selectedId) },
					)
				}
				Button(onClick = onDismissRequest) { Text(stringResource(R.string.syncplay_close)) }
			}
			LaunchedEffect(Unit) { focusRequester.requestFocus() }
		}
	}
}
