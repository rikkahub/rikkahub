package com.hefengfan.hffchat.utils

import android.content.ClipData

fun ClipData.getText(): String {
    return buildString {
        repeat(itemCount) {
            append(getItemAt(it).text ?: "")
        }
    }
}
