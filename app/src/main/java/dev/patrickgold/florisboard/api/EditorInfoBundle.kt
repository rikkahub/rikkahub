/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.api

import android.os.Parcel
import android.os.Parcelable

/**
 * Snapshot of the currently focused editor, returned to remote agents over AIDL.
 *
 * Only carries fields useful for deciding *what* to type and *whether* typing
 * is safe. The raw [android.view.inputmethod.EditorInfo] is intentionally not
 * exposed: it may include bundles owned by the host app.
 */
data class EditorInfoBundle(
    val packageName: String?,
    val fieldHint: String?,
    val inputType: Int,
    val imeOptions: Int,
    val isPassword: Boolean,
    val isMultiLine: Boolean,
    val selectionStart: Int,
    val selectionEnd: Int,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(packageName)
        dest.writeString(fieldHint)
        dest.writeInt(inputType)
        dest.writeInt(imeOptions)
        dest.writeInt(if (isPassword) 1 else 0)
        dest.writeInt(if (isMultiLine) 1 else 0)
        dest.writeInt(selectionStart)
        dest.writeInt(selectionEnd)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<EditorInfoBundle> = object : Parcelable.Creator<EditorInfoBundle> {
            override fun createFromParcel(source: Parcel): EditorInfoBundle = EditorInfoBundle(
                packageName = source.readString(),
                fieldHint = source.readString(),
                inputType = source.readInt(),
                imeOptions = source.readInt(),
                isPassword = source.readInt() != 0,
                isMultiLine = source.readInt() != 0,
                selectionStart = source.readInt(),
                selectionEnd = source.readInt(),
            )

            override fun newArray(size: Int): Array<EditorInfoBundle?> = arrayOfNulls(size)
        }
    }
}
