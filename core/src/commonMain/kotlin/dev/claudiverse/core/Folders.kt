package dev.claudiverse.core

/* Folder rules for New seat: titles from folders, and the machine's whitelist of allowed roots. */

/**
 * A seat title from a folder: its last segment, kept to what the host agent
 * accepts ([A-Za-z0-9._-], at most 64), so the default never fails validation.
 */
fun seatTitleFor(path: String): String =
    path.trimEnd('/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "-").trim('-', '.').take(64)

/** One breadcrumb: its label and the folder it opens. */
data class Crumb(val label: String, val path: String)

/**
 * Breadcrumbs for [path], starting at the allowed root that holds it (labelled
 * with its full path): anything above a root is outside the whitelist and would
 * only be refused. With no root holding it, every segment is a crumb.
 */
fun crumbs(path: String, roots: List<String>): List<Crumb> {
    val root = rootOf(path, roots)
    val base = root ?: ""
    val rest = path.removePrefix(base).split('/').filter { it.isNotEmpty() }
    val out = mutableListOf<Crumb>()
    if (root != null) out += Crumb(root, root)
    var at = if (root == null && path.startsWith("/")) "" else base
    for (seg in rest) {
        at = "$at/$seg"
        out += Crumb(seg, if (root == null && !path.startsWith("/")) at.removePrefix("/") else at)
    }
    return out
}

/** The deepest of [roots] that holds [path], or null when none does. */
fun rootOf(path: String, roots: List<String>): String? = roots.map { it.trimEnd('/') }
    .filter { it.isNotEmpty() && (path == it || path.startsWith("$it/")) }
    .maxByOrNull { it.length }

/** [path] without a trailing slash (the root "/" stays). */
fun normalFolder(path: String): String = if (path.length > 1) path.trimEnd('/') else path

/** Whether [folder] is [root] or inside it, by whole path segments: /a/bc is not inside /a/b. */
fun isInside(folder: String, root: String): Boolean = folder == root || folder.startsWith(if (root.endsWith("/")) root else "$root/")

