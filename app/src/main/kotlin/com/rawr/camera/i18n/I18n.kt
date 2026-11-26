package com.rawr.camera.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Rawr 17U fork: UI translations.
 *
 * The English text is the key. A language file is a flat JSON object {"English text": "translation"};
 * keys starting with "@" are metadata (@language, @code, @note). Bundled files are assets/i18n/<code>.json,
 * an imported file is kept as filesDir/i18n/custom.json. The template (every key, empty values) is generated
 * at build time from the sources into assets/i18n/template.json, so new strings show up in an export by
 * themselves. Missing keys fall back to English.
 *
 * [tr] reads Compose state, so composables that call it recompose when the language changes.
 */
object I18n {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val CUSTOM = "custom"

    data class Language(val code: String, val name: String)

    /** Base translations bundled in the APK (machine-made, corrected through import/export). */
    val bundled = listOf(
        Language("pt", "Português"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("de", "Deutsch"),
        Language("it", "Italiano"),
        Language("ru", "Русский"),
        Language("uk", "Українська"),
        Language("pl", "Polski"),
        Language("tr", "Türkçe"),
        Language("bn", "বাংলা"),
    )

    private const val PREFS = "rawr_i18n"
    private const val KEY_LANGUAGE = "language"
    private val SEPARATORS = listOf(" · ", " › ", " / ", ": ")

    var language by mutableStateOf(SYSTEM)
        private set
    private var table by mutableStateOf<Map<String, String>>(emptyMap())
    private var upper: Map<String, String> = emptyMap()
    @Volatile private var loadedFor: String? = null

    /** Loads the saved choice once per process (and again if the system language changed). */
    fun init(context: Context) {
        val app = context.applicationContext
        val code = prefs(app).getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM
        if (loadedFor == resolvedKey(code)) return
        load(app, code)
    }

    fun select(context: Context, code: String) {
        val app = context.applicationContext
        prefs(app).edit().putString(KEY_LANGUAGE, code).apply()
        load(app, code)
    }

    /** Display name of a language code, for the settings row. */
    fun displayName(context: Context, code: String): String = when (code) {
        SYSTEM -> "System"
        ENGLISH -> "English"
        // The imported file's own name could repeat a bundled one ("Русский"): mark it as imported.
        CUSTOM -> customName(context)?.let { "$it · ${tr("Imported")}" } ?: tr("Imported file")
        else -> bundled.firstOrNull { it.code == code }?.name ?: code
    }

    /** The codes the Language row offers: system, English, the bundled ones and an imported file if there is one. */
    fun choices(context: Context): List<String> =
        listOf(SYSTEM, ENGLISH) + bundled.map { it.code } + (if (customFile(context.applicationContext).isFile) listOf(CUSTOM) else emptyList())

    fun tr(text: String): String {
        val t = table
        if (t.isEmpty() || text.isEmpty()) return text
        t[text]?.let { return it }
        // Capture-strip labels are the settings labels in capitals: "SHADOWS" uses the "Shadows" entry.
        if (text.any { it.isLetter() } && text == text.uppercase(Locale.ROOT)) upper[text]?.let { return it }
        // Sentences joined at run time: a known first sentence and a translatable rest.
        var cut = text.indexOf(". ")
        while (cut > 0) {
            val head = t[text.substring(0, cut + 1)]
            if (head != null) {
                val tail = text.substring(cut + 2)
                val rest = tr(tail).takeIf { it != tail } ?: t[" $tail"]?.trimStart()
                if (rest != null || tail.none { it.isLetter() }) return "$head ${rest ?: tail}"
            }
            cut = text.indexOf(". ", cut + 1)
        }
        // Joined values ("Photo · DNG", "Look › Film") translate piece by piece.
        for (separator in SEPARATORS) {
            if (separator in text) {
                val parts = text.split(separator)
                val translated = parts.map { tr(it) }
                // Only when every worded piece has a translation, so a line never ends up half translated.
                val complete = parts.indices.all { i -> translated[i] != parts[i] || parts[i].none { it.isLetter() } }
                if (complete) return translated.joinToString(separator)
            }
        }
        return text
    }

    fun trOrNull(text: String?): String? = text?.let(::tr)

    /** Imports a language file; returns the number of translated keys. Throws on an empty or invalid file. */
    fun importFile(context: Context, json: String): Int {
        val map = parse(json)
        require(map.isNotEmpty()) { "No translations in this file" }
        val file = customFile(context)
        file.parentFile?.mkdirs()
        file.writeText(json)
        select(context, CUSTOM)
        return map.size
    }

    /**
     * Every key of the template with the current translation (empty where there is none), for editing
     * and importing back. English (or no translation) gives the bare template.
     */
    fun exportJson(context: Context): String {
        val app = context.applicationContext
        val keys = templateKeys(app)
        val current = table
        val out = JSONObject()
        val code = if (language == SYSTEM) systemCode() else language
        out.put("@language", if (current.isEmpty()) "" else displayName(app, language))
        out.put("@code", if (current.isEmpty()) "" else code)
        out.put("@note", "Rawr UI translation. Keys are the English text; fill or fix the values and import the file in Settings > System > Language. Empty values stay English.")
        (keys + current.keys.filterNot { it in keys }.sorted()).forEach { key -> out.put(key, current[key] ?: "") }
        return out.toString(1)
    }

    fun exportFileName(): String {
        val code = if (language == SYSTEM) systemCode() else language
        return if (table.isEmpty()) "rawr-language-template.json" else "rawr-language-$code.json"
    }

    internal fun parse(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.startsWith("@")) continue
            val value = obj.optString(key, "")
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    internal fun install(map: Map<String, String>) {
        table = map
        upper = buildMap<String, String> {
            map.forEach { (key, value) ->
                val capitals = key.uppercase(Locale.ROOT)
                if (capitals != key) putIfAbsent(capitals, value.uppercase(Locale.getDefault()))
            }
        }
    }

    private fun load(app: Context, code: String) {
        language = code
        val map = when (code) {
            ENGLISH -> emptyMap()
            // An imported file sits on top of the bundled translation of its @code, so texts added to the app
            // after the file was exported still come out translated instead of in English.
            CUSTOM -> customFile(app).takeIf { it.isFile }?.let { file ->
                runCatching {
                    val json = file.readText()
                    val code = JSONObject(json).optString("@code")
                    val base = if (bundled.any { it.code == code }) asset(app, code) else emptyMap()
                    base + parse(json)
                }.getOrNull()
            } ?: emptyMap()
            SYSTEM -> systemCode().let { lang -> if (bundled.any { it.code == lang }) asset(app, lang) else emptyMap() }
            else -> asset(app, code)
        }
        install(map)
        loadedFor = resolvedKey(code)
    }

    private fun resolvedKey(code: String) = if (code == SYSTEM) "$SYSTEM:${systemCode()}" else code

    private fun systemCode(): String = Locale.getDefault().language

    private fun asset(app: Context, code: String): Map<String, String> =
        runCatching { app.assets.open("i18n/$code.json").bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyMap())

    private fun templateKeys(app: Context): List<String> =
        runCatching {
            val obj = app.assets.open("i18n/template.json").bufferedReader().use { JSONObject(it.readText()) }
            buildList<String> { obj.keys().forEach { if (!it.startsWith("@")) add(it) } }
        }.getOrDefault(emptyList())

    private fun customName(app: Context): String? =
        runCatching { JSONObject(customFile(app).readText()).optString("@language").takeIf { it.isNotBlank() } }.getOrNull()

    private fun customFile(app: Context) = File(app.filesDir, "i18n/custom.json")

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Short form for call sites: tr("Shutter"). */
fun tr(text: String): String = I18n.tr(text)
