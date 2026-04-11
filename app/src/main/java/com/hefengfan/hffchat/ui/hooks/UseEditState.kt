package com.hefengfan.hffchat.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun <T> useEditState(
    onUpdate: (T) -> Unit
): EditState<T> {
    var isEditing by remember { mutableStateOf(false) }
    var currentState by remember { mutableStateOf<T?>(null) }

    return object : EditState<T> {
        override var isEditing: Boolean
            get() = isEditing
            set(value) {
                isEditing = value
            }

        override var currentState: T?
            get() = currentState
            set(value) {
                currentState = value
            }

        override fun open(initialState: T) {
            isEditing = true
            currentState = initialState
        }

        override fun confirm() {
            if (currentState != null) {
                onUpdate(currentState!!)
                isEditing = false
                currentState = null
            }
        }

        override fun dismiss() {
            isEditing = false
            currentState = null
        }
    }
}

interface EditState<T> {
    var isEditing: Boolean
    var currentState: T?

    fun open(initialState: T)

    fun confirm()

    fun dismiss()
}

@Composable
fun <T> EditState<T>.EditStateContent(
    content: @Composable (value: T, updateValue: (T) -> Unit) -> Unit
) {
    if (this.isEditing) {
        this.currentState?.let {
            content(it) { newState ->
                this.currentState = newState
            }
        }
    }
}
