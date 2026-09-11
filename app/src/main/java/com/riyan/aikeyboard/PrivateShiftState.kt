package com.riyan.aikeyboard

/** Manual Shift owns casing until a new sentence boundary or input session. */
internal class PrivateShiftState {
    var uppercase = false
    var locked = false
    var manual = false

    fun tap() {
        manual = true
        if (locked) {
            locked = false
            uppercase = false
        } else uppercase = !uppercase
    }

    fun hold() {
        manual = true
        locked = true
        uppercase = true
    }

    fun letterCommitted() {
        // Ignore delayed cursor callbacks for the letter just committed.
        manual = true
        if (!locked) uppercase = false
    }

    fun automatic(value: Boolean) {
        if (!manual && !locked) uppercase = value
    }

    companion object {
        fun afterPeriod(before: String) = before.trimEnd().lastOrNull() == '.'
    }
}
