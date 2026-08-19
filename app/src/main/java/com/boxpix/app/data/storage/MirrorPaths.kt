package com.boxpix.app.data.storage

/**
 * SPEC §5 disk conventions: the app's bookkeeping folders (.trash, .thumbs,
 * later .meta) are dot-prefixed mirrors living at the root of the tree the
 * path belongs to — the actual tree root when it is writable (fake), the
 * first path segment (the disk) when the root is virtual (real box, where
 * mkdir at "/" answers access_denied).
 */
object MirrorPaths {

    const val TRASH_DIR = ".trash"
    const val THUMBS_DIR = ".thumbs"
    const val META_DIR = ".meta"

    /**
     * Root of an app dir (.meta, .trash, .thumbs) for the tree containing
     * [treePath] — "/Archive 1/.meta" on a virtual-root box, "/.meta" otherwise.
     */
    fun appRootDirFor(treePath: String, dirName: String, canCreateAtRoot: Boolean): String {
        if (canCreateAtRoot) return "/$dirName"
        val rooted = treePath.startsWith("/")
        val disk = treePath.split('/').firstOrNull { it.isNotEmpty() } ?: return "/$dirName"
        return "${if (rooted) "/" else ""}$disk/$dirName"
    }

    /**
     * Mirror directory of [originalParent] under [mirrorName], e.g.
     * "/Archive 1/Photos" → "/Archive 1/.thumbs/Photos" (virtual root) or
     * "/Photos" → "/.thumbs/Photos" (writable root). The input's leading-slash
     * convention is preserved.
     */
    fun mirrorDirFor(originalParent: String, mirrorName: String, canCreateAtRoot: Boolean): String {
        val rooted = originalParent.startsWith("/")
        val segments = originalParent.split('/').filter { it.isNotEmpty() }
        if (canCreateAtRoot) {
            val rest = segments.joinToString("/")
            return if (rest.isEmpty()) "/$mirrorName" else "/$mirrorName/$rest"
        }
        val disk = segments.firstOrNull() ?: return "/$mirrorName"
        val prefix = if (rooted) "/" else ""
        val rest = segments.drop(1).joinToString("/")
        return if (rest.isEmpty()) {
            "$prefix$disk/$mirrorName"
        } else {
            "$prefix$disk/$mirrorName/$rest"
        }
    }

    /** Sidecar thumbnail path for a media file: <mirror-of-parent>/<name>.webp */
    fun thumbPathFor(mediaDisplayPath: String, canCreateAtRoot: Boolean): String {
        val parent = mediaDisplayPath.trimEnd('/').substringBeforeLast('/', "")
            .ifEmpty { if (mediaDisplayPath.startsWith("/")) "/" else "" }
        val name = mediaDisplayPath.trimEnd('/').substringAfterLast('/')
        val dir = mirrorDirFor(parent, THUMBS_DIR, canCreateAtRoot)
        return "$dir/$name.webp"
    }
}
