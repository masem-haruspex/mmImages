package com.mm.images

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mlib.future.components.*
import com.mlib.future.modifiers.*
import androidx.compose.material3.Surface
import com.mlib.future.FutureTheme
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
		val logic = GalleryLogic(context)
		logic.getAllWidgetIds().forEach { appWidgetId ->
			val config = logic.getWidgetConfig(appWidgetId) ?: return@forEach
			WidgetScheduler.schedule(context, appWidgetId, config.intervalMinutes)
			ImageWidgetProvider.updateWidget(context, appWidgetId)
		}
	}
}

class WidgetAlarmReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
		if (appWidgetId == -1) return
		ImageWidgetProvider.advanceAndUpdate(context, appWidgetId)
		val logic = GalleryLogic(context)
		val config = logic.getWidgetConfig(appWidgetId) ?: return
		WidgetScheduler.schedule(context, appWidgetId, config.intervalMinutes)
	}
}

object WidgetScheduler {
	private fun canScheduleExactAlarms(context: Context): Boolean {
		val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		return alarmMgr.canScheduleExactAlarms()
	}

	fun schedule(context: Context, appWidgetId: Int, intervalMinutes: Int) {
		val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val intent = Intent(context, WidgetAlarmReceiver::class.java).apply {
			putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
		}
		val pendingIntent = PendingIntent.getBroadcast(
			context,
			appWidgetId,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		val intervalMillis = intervalMinutes * 60_000L
		val triggerAt = SystemClock.elapsedRealtime() + intervalMillis

		if (!canScheduleExactAlarms(context)) {
			alarmMgr.setAndAllowWhileIdle(
				AlarmManager.ELAPSED_REALTIME_WAKEUP,
				triggerAt,
				pendingIntent
			)
		} else {
			alarmMgr.setExactAndAllowWhileIdle(
				AlarmManager.ELAPSED_REALTIME_WAKEUP,
				triggerAt,
				pendingIntent
			)
		}
	}

	fun cancel(context: Context, appWidgetId: Int) {
		val alarmMgr = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val intent = Intent(context, WidgetAlarmReceiver::class.java)
		val pendingIntent = PendingIntent.getBroadcast(
			context,
			appWidgetId,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
		)
		alarmMgr.cancel(pendingIntent)
	}
}

class ImageWidgetProvider : AppWidgetProvider() {

	override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
		appWidgetIds.forEach { updateWidget(context, it, appWidgetManager) }
	}

	override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
		updateWidget(context, appWidgetId, appWidgetManager)
	}

	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		val logic = GalleryLogic(context)
		appWidgetIds.forEach {
			logic.deleteWidgetConfig(it)
			WidgetScheduler.cancel(context, it)
		}
	}

	companion object {
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

		fun updateWidget(context: Context, appWidgetId: Int) {
			updateWidget(context, appWidgetId, AppWidgetManager.getInstance(context))
		}

		fun updateWidget(context: Context, appWidgetId: Int, mgr: AppWidgetManager) {
			val logic = GalleryLogic(context)
			val config = logic.getWidgetConfig(appWidgetId) ?: return
			val views = RemoteViews(context.packageName, R.layout.image_widget)

			val openIntent = Intent(context, MainActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
				putExtra("widget_image_uri", config.shuffledOrder.getOrNull(config.currentIndex))
				putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
			}

			val pendingIntent = PendingIntent.getActivity(
				context,
				appWidgetId,
				openIntent,
				PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
			)
			views.setOnClickPendingIntent(R.id.widget_image, pendingIntent)

			val uriString = config.shuffledOrder.getOrNull(config.currentIndex)
			if (uriString == null) {
				views.setViewVisibility(R.id.widget_image, android.view.View.GONE)
				views.setViewVisibility(R.id.widget_placeholder, android.view.View.VISIBLE)
				mgr.updateAppWidget(appWidgetId, views)
				return
			}

			scope.launch {
				val bitmap = loadWidgetBitmap(context, Uri.parse(uriString), mgr, appWidgetId)
				withContext(Dispatchers.Main) {
					if (bitmap != null) {
						views.setImageViewBitmap(R.id.widget_image, bitmap)
						views.setViewVisibility(R.id.widget_image, android.view.View.VISIBLE)
						views.setViewVisibility(R.id.widget_placeholder, android.view.View.GONE)
					} else {
						views.setViewVisibility(R.id.widget_image, android.view.View.GONE)
						views.setViewVisibility(R.id.widget_placeholder, android.view.View.VISIBLE)
					}
					mgr.updateAppWidget(appWidgetId, views)
				}
			}
		}

		private suspend fun loadWidgetBitmap(
			context: Context,
			uri: Uri,
			mgr: AppWidgetManager,
			appWidgetId: Int
		): Bitmap? = withContext(Dispatchers.IO) {
			try {
				val options = context.contentResolver.openInputStream(uri)?.use { stream ->
					BitmapFactory.Options().apply {
						inJustDecodeBounds = true
						BitmapFactory.decodeStream(stream, null, this)
					}
				} ?: return@withContext null

				val sizes = mgr.getAppWidgetOptions(appWidgetId)
				val density = context.resources.displayMetrics.density
				val w = (sizes.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300) * density).toInt()
				val h = (sizes.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300) * density).toInt()
				val maxDim = maxOf(w, h).coerceAtLeast(100)

				var sampleSize = 1
				while (options.outWidth / sampleSize > maxDim * 2 || options.outHeight / sampleSize > maxDim * 2) {
					sampleSize *= 2
				}

				context.contentResolver.openInputStream(uri)?.use { stream ->
					BitmapFactory.Options().apply {
						inSampleSize = sampleSize
					}.let { BitmapFactory.decodeStream(stream, null, it) }
				}
			} catch (_: Exception) {
				null
			}
		}

		fun advanceAndUpdate(context: Context, appWidgetId: Int) {
			val logic = GalleryLogic(context)
			val config = logic.getWidgetConfig(appWidgetId) ?: return
			if (config.shuffledOrder.isEmpty()) {
				updateWidget(context, appWidgetId)
				return
			}

			val newIndex = (config.currentIndex + 1) % config.shuffledOrder.size
			val newConfig = config.copy(currentIndex = newIndex)
			if (newIndex == 0 && config.shuffledOrder.size > 1) {
				val images = config.shuffledOrder.map { Uri.parse(it) }
				val reshuffled = logic.createShuffledOrder(images)
				logic.saveWidgetConfig(newConfig.copy(shuffledOrder = reshuffled, currentIndex = 0))
			} else {
				logic.saveWidgetConfig(newConfig)
			}

			updateWidget(context, appWidgetId)
		}

		fun updateAllWidgets(context: Context) {
			val mgr = AppWidgetManager.getInstance(context)
			val ids = mgr.getAppWidgetIds(ComponentName(context, ImageWidgetProvider::class.java))
			ids.forEach { updateWidget(context, it, mgr) }
		}
	}
}

class WidgetConfigActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val appWidgetId = intent?.extras?.getInt(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID
		) ?: AppWidgetManager.INVALID_APPWIDGET_ID

		if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
			finish()
			return
		}

		setContent {
			val logic = remember { GalleryLogic(this) }
			val theme = remember { logic.getSelectedTheme() }

			ImagesTheme(selectedTheme = theme) {
				Surface(modifier = Modifier.fillMaxSize(), color = FutureTheme.config.colors.globalBg) {
					WidgetConfigContent(
						logic = logic,
						appWidgetId = appWidgetId,
						onFinish = { result ->
							setResult(result)
							finish()
						}
					)
				}
			}
		}
	}
}

@Composable
private fun WidgetConfigContent(
	logic: GalleryLogic,
	appWidgetId: Int,
	onFinish: (Int) -> Unit
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val config = FutureTheme.config

	var folderTree by remember { mutableStateOf<List<FolderNode>>(emptyList()) }
	var selectedFolder by remember { mutableStateOf<FolderNode?>(null) }
	var folderExpanded by remember { mutableStateOf(false) }
	var intervalExpanded by remember { mutableStateOf(false) }
	var selectedInterval by remember { mutableStateOf(60) }

	var selectedFolderImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
	var isLoadingImages by remember { mutableStateOf(false) }

	val intervals = listOf(
		15 to "15 MINUTES", 30 to "30 MINUTES", 60 to "1 HOUR",
		180 to "3 HOURS", 360 to "6 HOURS", 720 to "12 HOURS",
		1440 to "DAILY", 10080 to "WEEKLY"
	)

	LaunchedEffect(Unit) {
		folderTree = logic.buildDeepFolderTree(context)
	}

	val flatFolders = remember(folderTree) {
		logic.flattenFolderTree(folderTree)
	}

	LaunchedEffect(selectedFolder) {
		selectedFolder?.let { folder ->
			isLoadingImages = true
			val shallowNode = logic.loadFolder(context, folder.path)
			selectedFolderImages = shallowNode?.imageUris ?: emptyList()
			isLoadingImages = false
		} ?: run {
			selectedFolderImages = emptyList()
			isLoadingImages = false
		}
	}

	Box(modifier = Modifier.fillMaxSize()) {
		FutureScreenBackground()
		Column(
			modifier = Modifier
			.fillMaxSize()
			.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp)
		) {
			FutureTextHeading("WIDGET CONFIGURATION")

			FutureText("FOLDER", variant = TextVariant.Muted)
			FutureDropdown(
				expanded = folderExpanded,
				onExpandedChange = { folderExpanded = it },
				selectedText = selectedFolder?.path ?: "SELECT FOLDER...",
				options = flatFolders.map { it.path },
				onOptionSelected = { path ->
					selectedFolder = flatFolders.find { it.path == path }
				},
				modifier = Modifier.fillMaxWidth()
			)

			if (isLoadingImages) {
				FutureTextMuted("Loading images...")
			} else if (selectedFolder != null) {
				FutureTextMuted("Found ${selectedFolderImages.size} images in this folder.")
			}

			FutureText("REFRESH INTERVAL", variant = TextVariant.Muted)
			FutureDropdown(
				expanded = intervalExpanded,
				onExpandedChange = { intervalExpanded = it },
				selectedText = intervals.find { it.first == selectedInterval }?.second ?: "SELECT...",
				options = intervals.map { it.second },
				onOptionSelected = { label ->
					selectedInterval = intervals.find { it.second == label }?.first ?: 60
				},
				modifier = Modifier.fillMaxWidth()
			)

			Spacer(modifier = Modifier.weight(1f))

			FutureButton(
				text = if (isLoadingImages) "LOADING..." else "SAVE",
				onClick = {
					selectedFolder?.let { folder ->
						val shuffled = if (selectedFolderImages.isNotEmpty()) {
							logic.createShuffledOrder(selectedFolderImages)
						} else emptyList()

						val widgetConfig = WidgetConfig(
							appWidgetId = appWidgetId,
							folderUri = folder.uri,
							folderPath = folder.path,
							intervalMinutes = selectedInterval,
							shuffledOrder = shuffled,
							currentIndex = 0
						)
						logic.saveWidgetConfig(widgetConfig)
						WidgetScheduler.schedule(context, appWidgetId, selectedInterval)
						ImageWidgetProvider.updateWidget(context, appWidgetId)
						onFinish(Activity.RESULT_OK)
					}
				},
				type = ButtonType.Highlighted,
				enabled = selectedFolder != null && !isLoadingImages,
				modifier = Modifier.fillMaxWidth()
			)

			FutureButton(
				text = "CANCEL",
				onClick = { onFinish(Activity.RESULT_CANCELED) },
				modifier = Modifier.fillMaxWidth()
			)
		}
	}
}
