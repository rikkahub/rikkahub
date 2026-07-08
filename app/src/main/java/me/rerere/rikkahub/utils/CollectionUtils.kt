package me.rerere.rikkahub.utils

fun <E> Collection<E>.checkDifferent(
    other: Collection<E>,
    eq: (E, E) -> Boolean,
): Pair<List<E>, List<E>> {
    val added = other.filter { e ->
        this.none { eq(it, e) }
    }
    val removed = this.filter { e ->
        other.none { eq(it, e) }
    }
    return added to removed
}

/**
 * Returns a new list with [first] placed at index 0, preserving the original order
 * of all other elements. Useful for ensuring the current image appears first in
 * a preview gallery.
 */
fun <T> List<T>.reorderWithFirst(first: T): List<T> = buildList {
    add(first)
    addAll(this@reorderWithFirst.filter { it != first })
}
