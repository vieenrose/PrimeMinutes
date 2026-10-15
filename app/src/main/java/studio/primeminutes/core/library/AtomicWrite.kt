package studio.primeminutes.core.library

import java.io.File

/**
 * Write [text] to a sibling temp file, then rename it over this one: a process kill mid-write
 * leaves the previous content, never a truncated JSON that the reader then discards.
 */
fun File.writeTextAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) { delete(); if (!tmp.renameTo(this)) error("could not replace $path") }
}
