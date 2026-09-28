package com.mm.images

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mlib.future.components.*
import com.mlib.future.modifiers.*
import com.mlib.future.FutureTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
	logic: GalleryLogic,
	selectedTheme: AppTheme,
	onThemeChange: (AppTheme) -> Unit,
	onNavigateBack: () -> Unit
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val config = FutureTheme.config

	var watchedFolders by remember { mutableStateOf(logic.getWatchedFolders()) }
	var themeExpanded by remember { mutableStateOf(false) }

	val folderPicker = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocumentTree()
	) { uri ->
		uri?.let {
			context.contentResolver.takePersistableUriPermission(
				it,
				android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
				android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
			val doc = DocumentFile.fromTreeUri(context, it)
			doc?.let { df ->
				logic.addWatchedFolder(
					WatchedFolder(uri = it, name = df.name ?: "?", path = df.name ?: "?")
				)
				watchedFolders = logic.getWatchedFolders()
			}
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		FutureScreenBackground()

		Column(modifier = Modifier.fillMaxSize()) {

			Column(
				modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp)
			) {
				FutureTextHeading("WATCHED FOLDERS")
				Spacer(modifier = Modifier.height(8.dp))

				if (watchedFolders.isEmpty()) {
					FutureTextMuted("NO FOLDERS ADDED")
				} else {
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
						watchedFolders.forEach { folder ->
							FutureCard(
								modifier = Modifier.fillMaxWidth(),
								onClick = null
							) {
								Row(
									modifier = Modifier.fillMaxWidth(),
									horizontalArrangement = Arrangement.SpaceBetween,
									verticalAlignment = Alignment.CenterVertically
								) {
									Column(modifier = Modifier.weight(1f)) {
										FutureText(
											text = folder.name,
											variant = TextVariant.Primary
										)
										FutureTextMuted(
											text = folder.path
										)
									}
									FutureButton(
										text = "REMOVE",
										onClick = {
											logic.removeWatchedFolder(folder.uri)
											watchedFolders = logic.getWatchedFolders()
										},
										type = ButtonType.Destructive
									)
								}
							}
						}
					}
				}

				FutureButton(
					text = "ADD FOLDER",
					onClick = { folderPicker.launch(null) },
					type = ButtonType.Highlighted,
					modifier = Modifier.fillMaxWidth()
				)
				FutureSeparator(modifier = Modifier.padding(vertical = 8.dp))

				FutureText("Theme")
				Spacer(modifier = Modifier.height(8.dp))

				FutureDropdown(
					expanded = themeExpanded,
					onExpandedChange = { themeExpanded = it },
					selectedText = selectedTheme.name,
					options = AppTheme.values().map { it.name },
					onOptionSelected = { name ->
						val theme = AppTheme.values().find { it.name == name } ?: AppTheme.MASEM
						onThemeChange(theme)
					},
					modifier = Modifier.fillMaxWidth()
				)
			}
			FutureHeader(
				title = "Settings",
				isBottom = true,
				actions = {
					FutureButton(
						text = "BACK",
						onClick = onNavigateBack
					)
				}
			)
		}
	}
}
