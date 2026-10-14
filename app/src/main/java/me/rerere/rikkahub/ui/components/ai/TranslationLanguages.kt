package me.rerere.rikkahub.ui.components.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import java.util.Locale

// 翻译功能可选的目标语言，消息翻译和翻译页共用
val TranslationLanguages: List<Locale> = listOf(
    Locale.SIMPLIFIED_CHINESE,
    Locale.ENGLISH,
    Locale.TRADITIONAL_CHINESE,
    Locale.JAPANESE,
    Locale.KOREAN,
    Locale.FRENCH,
    Locale.GERMAN,
    Locale.ITALIAN,
    Locale.forLanguageTag("es-ES"),
)

// 带国旗 emoji 的语言名，Locale.displayName 拿不到 emoji
@Composable
fun translationLanguageName(locale: Locale): String = when (locale) {
    Locale.SIMPLIFIED_CHINESE -> stringResource(R.string.language_simplified_chinese)
    Locale.ENGLISH -> stringResource(R.string.language_english)
    Locale.TRADITIONAL_CHINESE -> stringResource(R.string.language_traditional_chinese)
    Locale.JAPANESE -> stringResource(R.string.language_japanese)
    Locale.KOREAN -> stringResource(R.string.language_korean)
    Locale.FRENCH -> stringResource(R.string.language_french)
    Locale.GERMAN -> stringResource(R.string.language_german)
    Locale.ITALIAN -> stringResource(R.string.language_italian)
    Locale.forLanguageTag("es-ES") -> stringResource(R.string.language_spanish)
    else -> locale.getDisplayLanguage(LocalLocale.current.platformLocale)
}
