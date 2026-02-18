package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen) {
        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        backStack.add(screen)
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
