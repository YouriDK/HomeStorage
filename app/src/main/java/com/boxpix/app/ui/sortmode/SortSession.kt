package com.boxpix.app.ui.sortmode

import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import com.boxpix.app.ui.viewer.MediaRef
import javax.inject.Inject
import javax.inject.Singleton

/** Hand-off from a folder grid to the sort mode screen. */
@Singleton
class SortSession @Inject constructor() {
    var folder: FolderRef? = null
        private set
    var items: List<MediaRef> = emptyList()
        private set

    fun open(folder: FolderRef, items: List<MediaRef>) {
        this.folder = folder
        this.items = items
    }
}
