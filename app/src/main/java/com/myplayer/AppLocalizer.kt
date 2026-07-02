package com.myplayer

import android.content.Context
import org.json.JSONObject

/** A selectable UI language: its [code] (persisted, e.g. "en") and the [labelKey] whose translation
 *  is shown in the picker. Both come from the `_lang` block of `assets/i18n.json`, so adding a
 *  language is a data-only change. */
data class LanguageOption(val code: String, val labelKey: String)

/** Loads UI translations from `assets/i18n.json` and resolves strings at runtime. The English text is
 *  itself the key — [lw] returns the key verbatim for [DEFAULT_LANGUAGE], so untranslated strings (and
 *  the default locale) need no entries. The list of languages is built from the file's `_lang` block
 *  and system-ISO-to-internal code aliases from its `_alias` block, so both form dynamically —
 *  adding a language is a data-only change; on Android `JSONObject` keeps insertion order, so
 *  `_lang` order is the picker order. */
object AppLocalizer {
    const val DEFAULT_LANGUAGE = "en"
    private const val LANG_BLOCK = "_lang"
    private const val ALIAS_BLOCK = "_alias"

    private var languages: List<LanguageOption> = emptyList()
    // System ISO code -> internal code, for the codes that differ (Ukrainian is ISO "uk", stored
    // here as "ua"); from the `_alias` block.
    private var isoAliases: Map<String, String> = emptyMap()
    // key -> (languageCode -> translation); the default language is absent (lw falls back to the key).
    private var translations: Map<String, Map<String, String>> = emptyMap()
    private var loaded = false

    /** Reads and caches `i18n.json` once. Cheap enough to call on every launch before the first UI. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = context.applicationContext.assets.open("i18n.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val langs = mutableListOf<LanguageOption>()
        val aliases = HashMap<String, String>()
        val trans = HashMap<String, Map<String, String>>()
        for (key in root.keys()) {
            val obj = root.getJSONObject(key)
            when (key) {
                LANG_BLOCK ->
                    for (code in obj.keys()) langs.add(LanguageOption(code, obj.getString(code)))
                ALIAS_BLOCK ->
                    for (code in obj.keys()) aliases[code] = obj.getString(code)
                else -> {
                    val byLang = HashMap<String, String>()
                    for (code in obj.keys()) byLang[code] = obj.getString(code)
                    trans[key] = byLang
                }
            }
        }
        languages = langs
        isoAliases = aliases
        translations = trans
        loaded = true
    }

    /** The selectable languages, in file order; empty until [ensureLoaded]. */
    fun languageOptions(): List<LanguageOption> = languages

    /** The device language when a translation for it is offered, else [DEFAULT_LANGUAGE]. Used as
     *  the default before the user picks a language (and for a stale stored code). */
    fun systemLanguage(): String {
        val code = java.util.Locale.getDefault().language.let { isoAliases[it] ?: it }
        return languages.firstOrNull { it.code == code }?.code ?: DEFAULT_LANGUAGE
    }

    /** Translates [key] (the English text) into [languageCode], falling back to the key itself for the
     *  default language or any missing translation. */
    fun lw(key: String, languageCode: String): String {
        if (languageCode == DEFAULT_LANGUAGE) return key
        return translations[key]?.get(languageCode) ?: key
    }
}
