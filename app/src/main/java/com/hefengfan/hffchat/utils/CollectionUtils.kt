package com.hefengfan.hffchat.utils

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
