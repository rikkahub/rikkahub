package me.rerere.rikkahub.data.export

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.ImageUtils
import me.rerere.rikkahub.utils.base64Encode

object SillyTavernCharacterCardPngSerializer : ExportSerializer<SillyTavernCharacterCardExportData> {
    override val type: String = "st_character_card_png"

    override fun export(data: SillyTavernCharacterCardExportData): ExportData {
        return ExportData(type = type, data = JsonPrimitive(getExportFileName(data)))
    }

    override fun getMimeType(data: SillyTavernCharacterCardExportData): String = "image/png"

    override fun getExportFileName(data: SillyTavernCharacterCardExportData): String {
        val name = data.assistant.stCharacterData?.name
            ?.takeIf { it.isNotBlank() }
            ?: data.assistant.name.ifBlank { "character-card" }
        return "${sanitizeExportName(name, "character-card")}.png"
    }

    override fun exportToBytes(context: Context, data: SillyTavernCharacterCardExportData): ByteArray {
        val json = SillyTavernCharacterCardSerializer.exportToJson(data).base64Encode()
        val basePng = loadBaseCardPngBytes(context, data.assistant)
        return ImageUtils.embedTavernCharacterMetaIntoPngBytes(basePng, json)
    }

    override fun import(context: Context, uri: Uri): Result<SillyTavernCharacterCardExportData> {
        return Result.failure(UnsupportedOperationException("Character card PNG serializer does not support import"))
    }
}
