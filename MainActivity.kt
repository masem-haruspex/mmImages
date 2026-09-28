package com.mm.images

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mlib.future.components.*
import com.mlib.future.modifiers.*
import com.mlib.future.FutureTheme
import kotlinx.coroutines.launch
import android.appwidget.AppWidgetManager
import coil.request.ImageRequest
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Surface
import androidx.core.view.WindowCompat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.mlib.future.FutureConfig
import com.mlib.future.rememberSystemFutureConfig

enum class AppTheme { MASEM, AURORA, JULEE, AUTUMN, SUNSET, PAINTING, SUNRISE, CHARCOAL, CELESTE }

fun AppTheme.toConfig(isDark: Boolean): FutureConfig = when (this) {
	AppTheme.MASEM -> if (isDark) FutureConfig.MasemDark else FutureConfig.MasemLight
	AppTheme.AURORA -> if (isDark) FutureConfig.AuroraDark else FutureConfig.AuroraLight
	AppTheme.JULEE -> if (isDark) FutureConfig.JuleeDark else FutureConfig.JuleeLight
	AppTheme.AUTUMN -> if (isDark) FutureConfig.AutumnDark else FutureConfig.AutumnLight
	AppTheme.SUNSET -> if (isDark) FutureConfig.SunsetDark else FutureConfig.SunsetLight
	AppTheme.PAINTING -> if (isDark) FutureConfig.PaintingDark else FutureConfig.PaintingLight
	AppTheme.SUNRISE -> if (isDark) FutureConfig.SunriseDark else FutureConfig.SunriseLight
	AppTheme.CHARCOAL -> if (isDark) FutureConfig.CharcoalDark else FutureConfig.CharcoalLight
	AppTheme.CELESTE -> if (isDark) FutureConfig.CelesteDark else FutureConfig.CelesteLight
}

@Composable
fun ImagesTheme(
	selectedTheme: AppTheme = AppTheme.MASEM,
	content: @Composable () -> Unit
) {
	val isDark = isSystemInDarkTheme()
	val config = selectedTheme.toConfig(isDark)
	FutureTheme(config = config, content = content)
}

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val widgetImageUri = intent.getStringExtra("widget_image_uri")
		val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
		setContent {
			val logic = remember { GalleryLogic(this) }
			var selectedTheme by remember { mutableStateOf(logic.getSelectedTheme()) }
			ImagesTheme(selectedTheme = selectedTheme) {
				Surface(modifier = Modifier.fillMaxSize(), color = FutureTheme.config.colors.globalBg) {
					MainContent(
						logic = logic,
						selectedTheme = selectedTheme, 
						onThemeChange = { newTheme ->  
							selectedTheme = newTheme
							logic.saveSelectedTheme(newTheme)
						},
						widgetImageUri = widgetImageUri,
						widgetId = widgetId
					)
				}
			}
		}
	}
}

private sealed class Screen {
	object Gallery : Screen()
	data class FolderView(
		val folderPath: String, 
		val folderNode: FolderNode? = null
	) : Screen()
	data class ImageViewer(
		val folderPath: String,
		val images: List<Uri>,
		val startIndex: Int
	) : Screen()
	object Settings : Screen()
}

@Composable
private fun MainContent(
	logic: GalleryLogic,
	selectedTheme: AppTheme,
	onThemeChange: (AppTheme) -> Unit,
	widgetImageUri: String?,
	widgetId: Int
) {
	val context = LocalContext.current
	val config = FutureTheme.config
	val scrollMap = remember { mutableMapOf<String, Int>() }
	val backstack = remember { mutableStateListOf<String>() }
	var currentScreen by remember { mutableStateOf<Screen>(Screen.Gallery) }

	val currentFolderPath = when (val screen = currentScreen) {
		is Screen.FolderView -> screen.folderPath
		is Screen.ImageViewer -> screen.folderPath
		else -> ""
	}

	val breadcrumbPaths = remember(currentFolderPath) {
		if (currentFolderPath.isEmpty()) emptyList()
		else buildList {
			val parts = currentFolderPath.split("/")
			for (i in parts.indices) {
				add(parts.subList(0, i + 1).joinToString("/"))
			}
		}
	}

	LaunchedEffect(widgetImageUri, widgetId) {
		if (widgetImageUri != null && widgetId != -1) {
			val config = logic.getWidgetConfig(widgetId)
			if (config != null) {
				val images = config.shuffledOrder.map { Uri.parse(it) }
				val index = images.indexOfFirst { it.toString() == widgetImageUri }.coerceAtLeast(0)

				backstack.clear()
				backstack.addAll(config.folderPath.split("/").runningFold("") { acc, part ->
					if (acc.isEmpty()) part else "$acc/$part"
				}.drop(1))

				currentScreen = Screen.ImageViewer(
					folderPath = config.folderPath,
					images = images,
					startIndex = index
				)
			}
		}
	}

	BackHandler(enabled = currentScreen != Screen.Gallery) {
		when (val screen = currentScreen) {
			is Screen.ImageViewer -> {
				currentScreen = Screen.FolderView(screen.folderPath)
			}
			is Screen.FolderView -> {
				if (backstack.isNotEmpty()) {
					backstack.removeLast()
				}
				val parentPath = backstack.lastOrNull() ?: ""
				if (parentPath.isEmpty()) {
					currentScreen = Screen.Gallery
				} else {
					currentScreen = Screen.FolderView(parentPath)
				}
			}
			is Screen.Settings -> {
				currentScreen = Screen.Gallery
			}
			else -> {}
		}
	}

	Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
		FutureScreenBackground(modifier = Modifier.fillMaxSize(), type = config.backgroundAnimation)

		when (val screen = currentScreen) {
			is Screen.Gallery -> {
				GalleryScreen(
					logic = logic,
					onFolderClick = { folderPath ->
						backstack.clear()
						backstack.addAll(folderPath.split("/").runningFold("") { acc, part ->
							if (acc.isEmpty()) part else "$acc/$part"
						}.drop(1))
						currentScreen = Screen.FolderView(folderPath)
					},
					onSettingsClick = {
						currentScreen = Screen.Settings
					}
				)
			}
			is Screen.FolderView -> {
				FolderContentScreen(
					logic = logic,
					folderPath = screen.folderPath,
					onNavigateBack = {
						if (backstack.isNotEmpty()) {
							backstack.removeLast()
						}
						val parentPath = backstack.lastOrNull() ?: ""
						currentScreen = if (parentPath.isEmpty()) Screen.Gallery else Screen.FolderView(parentPath)
					},
					onFolderClick = { folderPath ->
						backstack.add(folderPath)
						currentScreen = Screen.FolderView(folderPath)
					},
					onImageClick = { folderPath, images, index ->
						currentScreen = Screen.ImageViewer(folderPath, images, index)
					},
					breadcrumbPaths = breadcrumbPaths,
					onBreadcrumbClick = { path ->
						while (backstack.isNotEmpty() && backstack.last() != path) {
							backstack.removeLast()
						}
						currentScreen = if (path.isEmpty()) Screen.Gallery else Screen.FolderView(path)
					},
					savedScroll = scrollMap[screen.folderPath] ?: 0,
					onScrollChanged = { scrollMap[screen.folderPath] = it }

				)
			}
			is Screen.ImageViewer -> {
				val scope = rememberCoroutineScope()
				ImageViewerScreen(
					logic = logic,
					folderPath = screen.folderPath,
					images = screen.images,
					startIndex = screen.startIndex,
					onNavigateBack = {
						currentScreen = Screen.FolderView(screen.folderPath)
					},
					onImagesChanged = {
						scope.launch {
							logic.invalidateCache()
							val updatedNode = logic.loadFolder(context, screen.folderPath)
							if (updatedNode != null && updatedNode.imageUris.isNotEmpty()) {
								val currentUri = screen.images.getOrNull(screen.startIndex)
								val newIndex = if (currentUri != null) updatedNode.imageUris.indexOf(currentUri).coerceAtLeast(0) else 0

								currentScreen = Screen.ImageViewer(
									folderPath = screen.folderPath,
									images = updatedNode.imageUris,
									startIndex = newIndex.coerceIn(0, (updatedNode.imageUris.size - 1).coerceAtLeast(0))
								)
							} else {
								currentScreen = Screen.FolderView(screen.folderPath)
							}
						}
					}
				)
			}
			is Screen.Settings -> {
				SettingsScreen(
					logic = logic,
					selectedTheme = selectedTheme,
					onThemeChange = onThemeChange,
					onNavigateBack = {
						currentScreen = Screen.Gallery
					}
				)
			}
		}
	}
}

@Composable
private fun GalleryScreen(
	logic: GalleryLogic,
	onFolderClick: (String) -> Unit,
	onSettingsClick: () -> Unit
) {
	val watchedFolders = logic.getWatchedFolders()

	Column(modifier = Modifier.fillMaxSize()) {
		if (watchedFolders.isEmpty()) {
			Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.Center
			) {
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(16.dp)
				) {
					FutureTextMuted("NO WATCHED FOLDERS")
					FutureTextMuted("Add folders in Settings")
				}
			}
		} else {
			FutureScrollArea(
				modifier = Modifier.fillMaxWidth().weight(1f),
				isBottomToTop = true,
			) {
				watchedFolders.forEach { folder ->
					Row(
						modifier = Modifier
						.fillMaxWidth()
						.clickable { onFolderClick(folder.name) },
						verticalAlignment = Alignment.CenterVertically,
					) {
						FutureIconFolder(size = 32)
						FutureText(folder.name, size = 18.sp)
					}
				}
			}
		}

		FutureHeader(
			"IMAGES",
			isBottom = true,
			subtitle = "${watchedFolders.size} FOLDERS",
			actions = {
				FutureButton(icon = { FutureIconSettings() }, onClick = onSettingsClick)
			}
		)
	}
}

@Composable
private fun FolderListItem(
	node: FolderNode,
	onFolderClick: (String) -> Unit,
) {
	val config = FutureTheme.config

	Column {
		Row(
			modifier = Modifier.fillMaxWidth().clickable{ onFolderClick(node.path) },
			verticalAlignment = Alignment.CenterVertically,
		) {
			FutureIconFolder(size = 32)
			FutureText(node.name, size = 18.sp)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderContentScreen(
	logic: GalleryLogic,
	folderPath: String,
	onNavigateBack: () -> Unit,
	onFolderClick: (String) -> Unit,
	onImageClick: (String, List<Uri>, Int) -> Unit,
	breadcrumbPaths: List<String>,
	onBreadcrumbClick: (String) -> Unit,
	savedScroll: Int = 0,
	onScrollChanged: (Int) -> Unit
) {
	val context = LocalContext.current
	val config = FutureTheme.config
	val scope = rememberCoroutineScope()
	var folderNode by remember(folderPath) { mutableStateOf(logic.getCachedFolder(folderPath)) }
	var isLoading by remember(folderPath) { mutableStateOf(folderNode == null) }
	val images = folderNode?.imageUris ?: emptyList()
	val subfolders = folderNode?.children ?: emptyList()
	var isSelectMode by remember { mutableStateOf(false) }
	var selectedImages by remember { mutableStateOf(setOf<Uri>()) }
	val moveFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
		uri?.let { targetUri ->
			context.contentResolver.takePersistableUriPermission(
				targetUri,
				android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
			scope.launch {
				logic.moveImages(context, selectedImages.toList(), targetUri)
				logic.invalidateCache()
				folderNode = logic.loadFolder(context, folderPath)
				isSelectMode = false
				selectedImages = emptySet()
			}
		}
	}
	val scrollState = rememberScrollState(initial = savedScroll)

	BackHandler(enabled = isSelectMode) {
		isSelectMode = false
		selectedImages = emptySet()
	}

	LaunchedEffect(folderPath) {
		if (folderNode == null) {
			folderNode = logic.loadFolder(context, folderPath)
			isLoading = false
		}
	}

	DisposableEffect(Unit) {
		onDispose {
			onScrollChanged(scrollState.value)
		}
	}

	Column(modifier = Modifier.fillMaxSize()) {
		Column(modifier = Modifier.weight(1f).fillMaxWidth()) {

			if (breadcrumbPaths.isNotEmpty()) {
				val breadcrumbItems = listOf("#") + breadcrumbPaths.map { it.substringAfterLast("/") }
				FutureBreadcrumb(
					items = breadcrumbItems,
					onItemClick = { index ->
						if (index == 0) onBreadcrumbClick("")
						else onBreadcrumbClick(breadcrumbPaths[index - 1])
					},
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				)
			}
			if (isLoading) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center
				) {
					FutureLoading("LOADING")
				}
			} else {
				FutureScrollArea(
					modifier = Modifier.fillMaxWidth().weight(1f),
					isBottomToTop = true,
					scrollState = scrollState,
					contentPadding = PaddingValues(16.dp),
				) {
					if (images.isNotEmpty()) {
						val rows = images.chunked(3)
						rows.forEachIndexed { rowIndex, rowImages ->
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(8.dp)
							) {
								rowImages.forEachIndexed { index, uri ->
									val globalIndex = rowIndex * 3 + index
									Box(
										modifier = Modifier
										.weight(1f)
										.aspectRatio(1f)
										.combinedClickable(
											onClick = {
												if (isSelectMode) {
													selectedImages = if (selectedImages.contains(uri)) {
														selectedImages - uri
													} else {
														selectedImages + uri
													}
													if (selectedImages.isEmpty()) isSelectMode = false
												} else {
													onImageClick(folderPath, images, globalIndex)
												}
											},
											onLongClick = {
												if (!isSelectMode) {
													isSelectMode = true
													selectedImages = setOf(uri)
												}
											}
										),
										contentAlignment = Alignment.Center
									) {
										AsyncImage(
											model = ImageRequest.Builder(context)
											.data(uri)
											.crossfade(true)
											.build(),
											contentDescription = null,
											contentScale = ContentScale.Crop,
											modifier = Modifier.fillMaxSize()
										)
										if (isSelectMode) {
											val isSelected = selectedImages.contains(uri)
											Box(
												modifier = Modifier
												.fillMaxSize()
												.background(if (isSelected) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
											)
											if (isSelected) {
												FutureText(
													text = "✓",
													variant = TextVariant.Glow,
													color = Color.White,
													modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
												)
											}
										}
									}
								}
								repeat(3 - rowImages.size) {
									Spacer(modifier = Modifier.weight(1f))
								}
							}
						}
					}

					if (subfolders.isNotEmpty() && images.isNotEmpty()) {
						FutureSeparator(modifier = Modifier.fillMaxWidth())
					}

					if (subfolders.isNotEmpty()) {
						subfolders.forEach { child ->
							FolderListItem(
								node = child,
								onFolderClick = onFolderClick,
							)
						}
					}
				}
			}
		}
		FutureHeader(
			title = if (isSelectMode) "" else (folderNode?.name ?: ""),
			isBottom = true,
			subtitle = if (isSelectMode) "${selectedImages.size} Selected" else "${folderNode?.imageCount ?: 0} images",
			actions = {
				if (isSelectMode) {
					Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						FutureButton("ALL", onClick = { selectedImages = images.toSet() })
						FutureButton("NONE", onClick = { selectedImages = emptySet() })
						FutureButton("MOVE", onClick = { moveFolderPicker.launch(null) })
						FutureButton(
							text = "DEL",
							onClick = {
								scope.launch {
									selectedImages.forEach { uri ->
										logic.deleteImage(context, uri)
									}
									logic.invalidateCache()
									isSelectMode = false
									selectedImages = emptySet()
									folderNode = logic.loadFolder(context, folderPath)
								}
							},
							type = ButtonType.Destructive
						)
					}
				} else {
					FutureButton(icon = { FutureIconArrowLeft() }, onClick = onNavigateBack)
				}
			}
		)

	}
}
