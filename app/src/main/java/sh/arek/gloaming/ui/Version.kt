package sh.arek.gloaming.ui

/**
 * How the app names the build it is.
 *
 * @param name the version as a person reads it, from the build's `versionName`.
 * @param code the build number Android compares to decide whether an update is
 *   one, from `versionCode`. Two builds of the same name are not necessarily
 *   the same build, and this is what tells them apart.
 * @param debug whether this is a debuggable build, which is nobody's copy of
 *   the release it shares a name with: unshrunk, signed with the debug key, and
 *   different in exactly the ways that matter when the line is being quoted
 *   into a report about something not working.
 */
fun versionLine(name: String, code: Int, debug: Boolean): String {
    val build = if (debug) "build $code, debug build" else "build $code"
    return "Gloaming $name ($build)"
}
