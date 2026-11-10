package io.github.ayatinene.sonycompanion.data

/** Formats the user-visible version label, appending the build's Git revision when it is known. */
object AppVersion {
    fun label(versionName: String, gitRevision: String): String {
        val revision = gitRevision.trim()
        return if (revision.isEmpty() || revision == "unknown") versionName else "$versionName ($revision)"
    }
}
