package com.mm.images

import android.util.Log
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "images_prefs"
private const val KEY_WATCHED_FOLDERS = "watched_folders"
private const val KEY_WIDGET_PREFIX = "widget_"

class GalleryLogic(context: Context) {
	private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private var folderTreeCache: List<FolderNode>? = null
	private var cacheTimestamp: Long = 0
	private val folderCache = mutableMapOf<String, FolderNode>()

	fun getCachedFolder(path: String): FolderNode? = folderCache[path]
	fun cacheFolder(path: String, node: FolderNode) { folderCache[path] = node }
	fun invalidateCache() { folderTreeCache = null; folderCache.clear() }

	fun getWatchedFolders(): List<WatchedFolder> {
		val json = prefs.getString(KEY_WATCHED_FOLDERS, "[]") ?: "[]"
		val arr = JSONArray(json)
		return (0 until arr.length()).map {
			val obj = arr.getJSONObject(it)
			WatchedFolder(
				uri = Uri.parse(obj.getString("uri")),
				name = obj.getString("name"),
				path = obj.getString("path")
			)
		}
	}

	fun addWatchedFolder(folder: WatchedFolder) {
		val current = getWatchedFolders().toMutableList()
		if (current.none { it.uri == folder.uri }) {
			current.add(folder)
			saveWatchedFolders(current)
		}
	}

	fun removeWatchedFolder(uri: Uri) {
		saveWatchedFolders(getWatchedFolders().filter { it.uri != uri })
	}

	private fun saveWatchedFolders(folders: List<WatchedFolder>) {
		val arr = JSONArray()
		folders.forEach {
			arr.put(JSONObject().apply {
				put("uri", it.uri.toString())
				put("name", it.name)
				put("path", it.path)
			})
		}
		prefs.edit { putString(KEY_WATCHED_FOLDERS, arr.toString()) }
	}

	suspend fun buildShallowTree(context: Context, forceRefresh: Boolean = false): List<FolderNode> = withContext(Dispatchers.IO) {
		if (!forceRefresh && folderTreeCache != null && System.currentTimeMillis() - cacheTimestamp < 30_000) {
			return@withContext folderTreeCache!!
		}
		val tree = getWatchedFolders().map { scanShallow(context, it.uri, it.name, null) }
		folderTreeCache = tree
		cacheTimestamp = System.currentTimeMillis()
		tree
	}

	suspend fun buildDeepFolderTree(context: Context): List<FolderNode> = withContext(Dispatchers.IO) {
		getWatchedFolders().map { scanDeepFolders(context, it.uri, it.name, null) }
	}

	private fun scanShallow(context: Context, folderUri: Uri, name: String, parentPath: String?): FolderNode {
		val docFile = DocumentFile.fromTreeUri(context, folderUri)
		if (docFile == null) {
			return FolderNode(uri = folderUri, name = name, path = name, parentPath = parentPath)
		}
		val currentPath = if (parentPath != null) "$parentPath/$name" else name
		val children = mutableListOf<FolderNode>()
		val imageFiles = mutableListOf<Pair<Uri, Long>>()

		val listStartTime = System.currentTimeMillis()
		val filesArray = docFile.listFiles()
		Log.d("PERF_TEST", "[$name] listFiles() returned ${filesArray.size} items in ${System.currentTimeMillis() - listStartTime}ms")

		val iterStartTime = System.currentTimeMillis()
		filesArray.forEach { child ->
			when {
				child.isDirectory -> {
					val childName = child.name ?: "?"
					children.add(FolderNode(
						uri = child.uri,
						name = childName,
						path = "$currentPath/$childName",
						parentPath = currentPath,
						children = emptyList(),
						imageCount = 0,
						imageUris = emptyList()
					))
				}
				isImageFile(child.name) -> {
					val lastMod = try { child.lastModified() } catch (e: Exception) { 0L }
					imageFiles.add(child.uri to lastMod)
				}
			}
		}
		val images = imageFiles.sortedByDescending { it.second }.map { it.first }


		return FolderNode(
			uri = folderUri,
			name = name,
			path = currentPath,
			parentPath = parentPath,
			children = children.sortedBy { it.name },
			imageCount = images.size,
			imageUris = images
		)
	}

	suspend fun loadFolder(context: Context, folderPath: String): FolderNode? = withContext(Dispatchers.IO) {
		val startTime = System.currentTimeMillis()
		Log.d("PERF_TEST", "[$folderPath] loadFolder START")

		getCachedFolder(folderPath)?.let {
			Log.d("PERF_TEST", "[$folderPath] loadFolder HIT CACHE in ${System.currentTimeMillis() - startTime}ms")
			return@withContext it
		}

		val parts = folderPath.split("/")
		val rootName = parts.firstOrNull() ?: return@withContext null
		val rootFolder = getWatchedFolders().find { it.name == rootName } ?: return@withContext null
		var currentUri = rootFolder.uri

		for (i in 1 until parts.size) {
			val walkStart = System.currentTimeMillis()
			val doc = DocumentFile.fromTreeUri(context, currentUri) ?: return@withContext null
			val next = doc.listFiles().find { it.isDirectory && it.name == parts[i] } ?: return@withContext null
			currentUri = next.uri
			Log.d("PERF_TEST", "[$folderPath] Walking step '$i' (${parts[i]}) took: ${System.currentTimeMillis() - walkStart}ms")
		}

		val name = parts.lastOrNull() ?: rootName
		val parentPath = parts.dropLast(1).joinToString("/").takeIf { it.isNotEmpty() }
		val node = scanShallow(context, currentUri, name, parentPath)
		cacheFolder(folderPath, node)

		Log.d("PERF_TEST", "[$folderPath] loadFolder TOTAL TIME: ${System.currentTimeMillis() - startTime}ms")
		node
	}

	private fun scanDeepFolders(context: Context, folderUri: Uri, name: String, parentPath: String?): FolderNode {
		val docFile = DocumentFile.fromTreeUri(context, folderUri) ?: return FolderNode(
			uri = folderUri, name = name, path = name, parentPath = parentPath
		)
		val currentPath = if (parentPath != null) "$parentPath/$name" else name
		val children = mutableListOf<FolderNode>()

		docFile.listFiles().forEach { child ->
			if (child.isDirectory) {
				children.add(scanDeepFolders(context, child.uri, child.name ?: "?", currentPath))
			}
		}

		return FolderNode(
			uri = folderUri,
			name = name,
			path = currentPath,
			parentPath = parentPath,
			children = children.sortedBy { it.name },
			imageCount = 0,
			imageUris = emptyList()
		)
	}

	fun saveWidgetConfig(config: WidgetConfig) {
		val key = "$KEY_WIDGET_PREFIX${config.appWidgetId}"
		prefs.edit {
			putString(key, JSONObject().apply {
				put("folderUri", config.folderUri.toString())
				put("folderPath", config.folderPath)
				put("intervalMinutes", config.intervalMinutes)
				put("shuffledOrder", JSONArray(config.shuffledOrder))
				put("currentIndex", config.currentIndex)
			}.toString())
		}
	}

	fun getWidgetConfig(appWidgetId: Int): WidgetConfig? {
		val key = "$KEY_WIDGET_PREFIX$appWidgetId"
		val json = prefs.getString(key, null) ?: return null
		val obj = JSONObject(json)
		return WidgetConfig(
			appWidgetId = appWidgetId,
			folderUri = Uri.parse(obj.getString("folderUri")),
			folderPath = obj.getString("folderPath"),
			intervalMinutes = obj.getInt("intervalMinutes"),
			shuffledOrder = (0 until obj.getJSONArray("shuffledOrder").length()).map {
				obj.getJSONArray("shuffledOrder").getString(it)
			},
			currentIndex = obj.getInt("currentIndex")
		)
	}

	fun deleteWidgetConfig(appWidgetId: Int) {
		prefs.edit { remove("$KEY_WIDGET_PREFIX$appWidgetId") }
	}

	fun createShuffledOrder(imageUris: List<Uri>): List<String> = imageUris.map { it.toString() }.shuffled()

	fun getAllWidgetIds(): List<Int> = prefs.all.keys
	.filter { it.startsWith(KEY_WIDGET_PREFIX) }
	.mapNotNull { it.removePrefix(KEY_WIDGET_PREFIX).toIntOrNull() }

	fun getSelectedTheme(): AppTheme {
		val name = prefs.getString("selected_theme", AppTheme.MASEM.name) ?: AppTheme.MASEM.name
		return try { AppTheme.valueOf(name) } catch (_: Exception) { AppTheme.MASEM }
	}

	fun saveSelectedTheme(theme: AppTheme) {
		prefs.edit { putString("selected_theme", theme.name) }
	}

	suspend fun deleteImage(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
		try {
			val docFile = DocumentFile.fromSingleUri(context, uri)
			if (docFile != null && docFile.exists()) return@withContext docFile.delete()
			context.contentResolver.delete(uri, null, null) > 0
		} catch (e: Exception) {
			e.printStackTrace()
			false
		}
	}

	suspend fun renameImage(context: Context, uri: Uri, newName: String, parentFolderUri: Uri? = null): Boolean = withContext(Dispatchers.IO) {
		if (newName.isBlank()) return@withContext false
		val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
		if (!docFile.exists()) return@withContext false

		try {
			if (docFile.renameTo(newName)) return@withContext true
		} catch (_: Exception) { }

		if (parentFolderUri == null) return@withContext false

		try {
			val parentDoc = DocumentFile.fromTreeUri(context, parentFolderUri) ?: return@withContext false
			val mimeType = getMimeType(context, uri, docFile.name)
			val finalName = resolveNameWithExtension(newName, docFile.name)
			val uniqueName = resolveNameCollision(parentDoc, finalName, uri)

			val newFile = parentDoc.createFile(mimeType, uniqueName) ?: return@withContext false
			context.contentResolver.openInputStream(uri)?.use { input ->
				context.contentResolver.openOutputStream(newFile.uri, "w")?.use { output ->
					input.copyTo(output)
				}
			}
			docFile.delete()
			true
		} catch (e: Exception) {
			e.printStackTrace()
			false
		}
	}

	suspend fun moveImages(context: Context, uris: List<Uri>, targetFolderUri: Uri): List<Boolean> = withContext(Dispatchers.IO) {
		val targetDoc = DocumentFile.fromTreeUri(context, targetFolderUri) ?: return@withContext uris.map { false }
		uris.map { uri ->
			try {
				val srcDoc = DocumentFile.fromSingleUri(context, uri) ?: return@map false
				if (!srcDoc.exists()) return@map false

				val mimeType = getMimeType(context, uri, srcDoc.name)
				val fileName = resolveNameCollision(targetDoc, srcDoc.name ?: "image", uri)
				val newFile = targetDoc.createFile(mimeType, fileName) ?: return@map false

				context.contentResolver.openInputStream(uri)?.use { input ->
					context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
						input.copyTo(output)
					}
				}
				srcDoc.delete()
				true
			} catch (e: Exception) {
				e.printStackTrace()
				false
			}
		}
	}

	private fun getMimeType(context: Context, uri: Uri, fallbackName: String?): String {
		return context.contentResolver.getType(uri) ?: when {
			fallbackName == null -> "image/*"
			fallbackName.endsWith(".jpg", true) || fallbackName.endsWith(".jpeg", true) -> "image/jpeg"
			fallbackName.endsWith(".png", true) -> "image/png"
			fallbackName.endsWith(".gif", true) -> "image/gif"
			fallbackName.endsWith(".webp", true) -> "image/webp"
			fallbackName.endsWith(".bmp", true) -> "image/bmp"
			fallbackName.endsWith(".heic", true) || fallbackName.endsWith(".heif", true) -> "image/heic"
			else -> "image/*"
		}
	}

	private fun resolveNameWithExtension(newName: String, oldName: String?): String {
		val oldExt = oldName?.substringAfterLast(".", "") ?: ""
		val newExt = newName.substringAfterLast(".", "")
		return if (newExt.isEmpty() && oldExt.isNotEmpty()) "$newName.$oldExt" else newName
	}

	private fun resolveNameCollision(parentDoc: DocumentFile, desiredName: String, originalUri: Uri): String {
		if (parentDoc.findFile(desiredName)?.let { it.uri == originalUri } == true) return desiredName
		var existing = parentDoc.findFile(desiredName)
		if (existing == null) return desiredName

		val nameWithoutExt = desiredName.substringBeforeLast(".")
		val ext = desiredName.substringAfterLast(".", "")
		var counter = 1
		var candidate = desiredName
		while (existing != null && existing.uri != originalUri) {
			candidate = if (ext.isNotEmpty()) "${nameWithoutExt}_$counter.$ext" else "${nameWithoutExt}_$counter"
			existing = parentDoc.findFile(candidate)
			counter++
		}
		return candidate
	}

	private fun isImageFile(name: String?): Boolean {
		if (name == null) return false
		val lower = name.lowercase()
		return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
		lower.endsWith(".png") || lower.endsWith(".webp") ||
		lower.endsWith(".gif") || lower.endsWith(".bmp") ||
		lower.endsWith(".heic") || lower.endsWith(".heif")
	}

	fun flattenFolderTree(nodes: List<FolderNode>): List<FolderNode> {
		val result = mutableListOf<FolderNode>()
		fun traverse(node: FolderNode) {
			result.add(node)
			node.children.forEach { traverse(it) }
		}
		nodes.forEach { traverse(it) }
		return result
	}
}
