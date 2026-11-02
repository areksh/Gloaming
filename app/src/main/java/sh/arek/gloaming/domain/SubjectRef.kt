package sh.arek.gloaming.domain

/**
 * What a notification is about, carried in its tap so the app opens on that
 * thing rather than on whatever tab it was last left on.
 *
 * A reference rather than the thing itself: a pass is re-derived from fresher
 * elements between the alert and the tap, and an event is worked out again,
 * so what is shown is looked up when the tap lands. Only passes and events -
 * nothing else posts a notification about one object.
 */
sealed interface SubjectRef {
    /** One pass, by its satellite and roughly when it rises. */
    data class Pass(val catalogue: Int, val risesAt: Long) : SubjectRef

    /** One event, by [EventPlanner.starKey], the key that already outlives a recalculation. */
    data class Event(val starKey: String) : SubjectRef

    companion object
}

/** The reference as text: `pass:<catalogue>:<rise>` or `event:<star key>`. */
fun SubjectRef.encode(): String = when (this) {
    is SubjectRef.Pass -> "pass:$catalogue:$risesAt"
    is SubjectRef.Event -> "event:$starKey"
}

/** The reference [text] names, or null for anything [encode] could not have written. */
fun SubjectRef.Companion.decode(text: String): SubjectRef? {
    val kind = text.substringBefore(':')
    val rest = if (':' in text) text.substringAfter(':') else return null
    return when (kind) {
        "pass" -> {
            val parts = rest.split(':')
            val catalogue = parts.getOrNull(0)?.toIntOrNull()
            val risesAt = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && catalogue != null && catalogue > 0 && risesAt != null) {
                SubjectRef.Pass(catalogue, risesAt)
            } else {
                null
            }
        }
        // A star key is free text after its kind - a comet's carries its name.
        "event" -> rest.takeIf { it.isNotBlank() }?.let { SubjectRef.Event(it) }
        else -> null
    }
}

/**
 * The subject a notification's tap asked to show, on the terms [findTargetFrom]
 * sets for Find it: nothing before setup is done, and nothing from a task
 * relaunched out of Recents, which hands its old intent back every time.
 */
fun showSubjectFrom(extra: String?, setupDone: Boolean, launchedFromHistory: Boolean): SubjectRef? =
    if (extra == null || !setupDone || launchedFromHistory) null else SubjectRef.decode(extra)
