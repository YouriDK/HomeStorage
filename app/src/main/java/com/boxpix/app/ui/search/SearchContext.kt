package com.boxpix.app.ui.search

import com.boxpix.app.ui.explorer.ExplorerViewModel.FolderRef
import javax.inject.Inject
import javax.inject.Singleton

/** The folder the search was opened from (its filter chip is removable). */
@Singleton
class SearchContext @Inject constructor() {
    var folder: FolderRef? = null
}
