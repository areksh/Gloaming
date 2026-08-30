package sh.arek.gloaming.ui

/**
 * How the app names the build it is.
 *
 * @param name the version as a person reads it, from the build's `versionName`.
 * @param code the build number Android compares to decide whether an update is
 *   one, from `versionCode`. Two builds of the same name are not necessarily
 *   the same build, and this is what tells them apart.
 * @param commit the commit the build was made from, short. Empty where there
 *   was none to be had - a source archive, or a machine without git - in which
 *   case it is left out rather than guessed at. It is the only one of these
 *   that identifies the source: a version name does not move between releases,
 *   so every test build for a month carries the same one.
 * @param modified whether that checkout had uncommitted changes in it, which
 *   makes the commit the one the build is *not*: nothing can reproduce it and
 *   nothing can be diffed against it.
 * @param debug whether this is a debuggable build, which is nobody's copy of
 *   the release it shares a name with: unshrunk, signed with the debug key, and
 *   different in exactly the ways that matter when the line is being quoted
 *   into a report about something not working.
 */
fun versionLine(
    name: String,
    code: Int,
    commit: String,
    modified: Boolean,
    debug: Boolean,
): String {
    val parts = buildList {
        add("build $code")
        if (commit.isNotBlank()) add(if (modified) "$commit with changes" else commit)
        if (debug) add("debug build")
    }
    return "Gloaming $name (${parts.joinToString(", ")})"
}
