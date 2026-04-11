package com.hefengfan.hffchat.data.event

sealed class AppEvent {
    data class Speak(val text: String) : AppEvent()
}
