package com.mm.images

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mlib.future.components.*
import com.mlib.future.modifiers.*
import com.mlib.future.FutureTheme
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageViewerScreen(
	logic: GalleryLogic,
	folderPath: String,
	images: List<Uri>,
	startIndex: Int,
	onNavigateBack: () -> Unit,
	onImagesChanged: () -> Unit = {}
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val config = FutureTheme.config
	var showMenu by remember { mutableStateOf(false) }
	var showRename by remember { mutableStateOf(false) }
	var renameText by remember { mutableStateOf("") }
	val pagerState = rememberPagerState(
		initialPage = startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0)),
		pageCount = { images.size }
	)
	val moveFolderPicker = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocumentTree()
	) { uri ->
		uri?.let { targetUri ->
			context.contentResolver.takePersistableUriPermission(
				targetUri,
				android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			)
			val currentUri = images.getOrNull(pagerState.currentPage) ?: return@let
			scope.launch {
				val results = logic.moveImages(context, listOf(currentUri), targetUri)
				if (results.firstOrNull() == true) {
					logic.invalidateCache()
					onImagesChanged()
				}
			}
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		FutureScreenBackground(type = com.mlib.future.BackgroundAnimationType.NONE)

		Column(modifier = Modifier.fillMaxSize()) {
			Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
				if (images.isEmpty()) {
					Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						FutureTextMuted("No Images")
					}
				} else {
					HorizontalPager(
						state = pagerState,
						modifier = Modifier.fillMaxSize(),
						userScrollEnabled = true
					) { page ->
						val uri = images[page]
						ZoomableAsyncImage(uri = uri, context = context)
					}
				}
			}

			FutureHeader(
				title = folderPath.substringAfterLast("/"),
				subtitle = "${pagerState.currentPage + 1} / ${images.size}",
				isBottom = true,
				actions = {
					FutureButton(
						icon = { FutureText("☰", variant = TextVariant.Primary) },
						onClick = { showMenu = true }
					)
					FutureButton(
						icon = { FutureIconArrowLeft() },
						onClick = {
							scope.launch {
								if (pagerState.currentPage > 0) {
									pagerState.animateScrollToPage(pagerState.currentPage - 1)
								}
							}
						}
					)
					FutureButton(
						icon = { FutureIconArrowRight() },
						onClick = {
							scope.launch {
								if (images.isNotEmpty() && pagerState.currentPage < images.size - 1) {
									pagerState.animateScrollToPage(pagerState.currentPage + 1)
								}
							}
						}
					)
				}
			)
		}

		if (showMenu) {
			FutureDialog(title = "Options", onDismissRequest = { showMenu = false }) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					val currentUri = images.getOrNull(pagerState.currentPage)
					FutureButton(
						"RENAME",
						modifier = Modifier.fillMaxWidth(),
						onClick = {
							currentUri?.let { uri ->
								val doc = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
								renameText = doc?.name ?: ""
								showRename = true
							}
							showMenu = false
						}
					)
					FutureButton(
						"MOVE",
						modifier = Modifier.fillMaxWidth(),
						onClick = {
							showMenu = false
							moveFolderPicker.launch(null)
						}
					)
					FutureButton(
						"DELETE IMAGE",
						modifier = Modifier.fillMaxWidth(),
						onClick = {
							currentUri?.let { uri ->
								scope.launch {
									val deleted = logic.deleteImage(context, uri)
									if (deleted) {
										logic.invalidateCache()
										onImagesChanged()
									}
									showMenu = false
									onNavigateBack()
								}
							}
						},
						type = ButtonType.Destructive
					)
					FutureButton(
						"CANCEL", 
						modifier = Modifier.fillMaxWidth(),
						onClick = { showMenu = false }
					)
				}
			}
		}

		if (showRename) {
			FutureDialog(title = "RENAME", onDismissRequest = { showRename = false }) {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					FutureTextField(
						value = renameText,
						onValueChange = { renameText = it },
						modifier = Modifier.fillMaxWidth().padding(8.dp)
					)
					FutureButton(
						text = "SAVE",
						onClick = {
							val currentUri = images.getOrNull(pagerState.currentPage)
							scope.launch {
								val parentNode = logic.loadFolder(context, folderPath)
								val parentUri = parentNode?.uri
								val success = currentUri?.let { logic.renameImage(context, it, renameText, parentUri) } ?: false
								if (success) {
									logic.invalidateCache()
									onImagesChanged()
								}
								showRename = false
							}
						},
						type = ButtonType.Highlighted
					)
					FutureButton(text = "CANCEL", onClick = { showRename = false })
				}
			}
		}
	}
}

@Composable
private fun ZoomableAsyncImage(uri: Uri, context: android.content.Context) {
	var scale by remember { mutableFloatStateOf(1f) }
	var offset by remember { mutableStateOf(Offset.Zero) }
	val animatedScale by animateFloatAsState(targetValue = scale, label = "scale")
	val animatedOffsetX by animateFloatAsState(targetValue = offset.x, label = "offsetX")
	val animatedOffsetY by animateFloatAsState(targetValue = offset.y, label = "offsetY")

	Box(
		modifier = Modifier
		.fillMaxSize()
		.pointerInput(Unit) {
			detectTransformGestures { _, pan, zoom, _ ->
				scale = (scale * zoom).coerceIn(1f, 5f)
				val maxX = (size.width * (scale - 1)) / 2
				val maxY = (size.height * (scale - 1)) / 2
				offset = Offset(
					x = (offset.x + pan.x * scale).coerceIn(-maxX, maxX),
					y = (offset.y + pan.y * scale).coerceIn(-maxY, maxY)
				)
			}
		}
		.pointerInput(Unit) {
			detectTapGestures(
				onDoubleTap = {
					if (scale > 1f) {
						scale = 1f
						offset = Offset.Zero
					} else {
						scale = 3f
					}
				}
			)
		},
		contentAlignment = Alignment.Center
	) {
		AsyncImage(
			model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
			contentDescription = null,
			contentScale = androidx.compose.ui.layout.ContentScale.Fit,
			modifier = Modifier
			.fillMaxSize()
			.graphicsLayer {
				scaleX = animatedScale
				scaleY = animatedScale
				translationX = animatedOffsetX
				translationY = animatedOffsetY
			}
		)
	}
}
