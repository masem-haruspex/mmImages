package com.mm.images

import android.net.Uri

data class WatchedFolder(
	val uri: Uri,           
	val name: String,       
	val path: String        
)

data class FolderNode(
	val uri: Uri,           
	val name: String,
	val path: String,       
	val parentPath: String?, 
	val children: List<FolderNode> = emptyList(),
	val imageCount: Int = 0,
	val imageUris: List<Uri> = emptyList() 
)

data class WidgetConfig(
	val appWidgetId: Int,
	val folderUri: Uri,
	val folderPath: String,  
	val intervalMinutes: Int, 
	val shuffledOrder: List<String> = emptyList(), 
	val currentIndex: Int = 0
)
