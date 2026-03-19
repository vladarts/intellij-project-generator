package com.github.vladarts.idegen.generate

data class ProjectEntry(
    /** Dot-joined path segments, e.g. "org.sub.repo" */
    val name: String,
    /** Absolute path on disk, e.g. "~/dev/github.com/org/repo" */
    val directory: String,
    /** Git clone URL; null for raw directory entries */
    val cloneUrl: String?,
    /** "Git" or null for raw entries */
    val vcsType: String?,
    val fastForward: Boolean = false
)
