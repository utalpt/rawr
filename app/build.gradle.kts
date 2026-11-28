import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Identify the checked-out source in both CI and local debug builds.
val debugCommit = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.map { it.trim() }
val debugVersionName = debugCommit.map { "debug-$it" }

// The debug native build reads its version from this file instead of a -D argument: a per-commit
// cmake argument changes AGP's .cxx configuration hash and every compile command, so ccache never
// hits. Rewritten only when the commit changes; CMake re-reads it (CMAKE_CONFIGURE_DEPENDS).
val debugNativeVersionFile = layout.buildDirectory.file("rawr-native-version/debug.txt").get().asFile
debugNativeVersionFile.parentFile.mkdirs()
debugVersionName.get().let { name ->
    if (!debugNativeVersionFile.exists() || debugNativeVersionFile.readText() != name) debugNativeVersionFile.writeText(name)
}

// The native asset is the canonical input; package it without a second tracked copy.
abstract class FilmAssetsTask : DefaultTask() {
    @get:InputFile abstract val sourceFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun copyAsset() {
        val target = outputDirectory.file("spektrafilm/SpektraHanatos2025Spectra.f32").get().asFile
        target.parentFile.mkdirs()
        sourceFile.get().asFile.copyTo(target, overwrite = true)
    }
}
// Rawr 17U fork: Voice Shutter's offline speech model (Vosk small English, Apache-2.0). It is fetched at build time and its
// checksum verified, so the 41 MB zip is not tracked in the source; -Prawr.voiceModelZip=<path> uses a local copy (an offline
// build). The app unpacks it on first use (integration/VoiceModelStore.kt).
abstract class VoiceModelAssetsTask : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Input @get:org.gradle.api.tasks.Optional abstract val localZip: Property<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun fetch() {
        val dir = outputDirectory.get().asFile.resolve("voice")
        dir.deleteRecursively()
        dir.mkdirs()
        val out = dir.resolve("vosk-model.zip")
        val local = localZip.orNull
        if (local != null) {
            File(local).copyTo(out, overwrite = true)
        } else {
            URI(url.get()).toURL().openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        out.inputStream().use { s ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = s.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        check(hex == sha256.get()) { "Voice Shutter speech model checksum mismatch: $hex" }
    }
}

val voiceModelAssets = tasks.register<VoiceModelAssetsTask>("generateVoiceModelAssets") {
    url.set("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
    sha256.set("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
    providers.gradleProperty("rawr.voiceModelZip").orNull?.let { localZip.set(it) }
    outputDirectory.set(layout.buildDirectory.dir("generated/voiceModelAssets"))
}

val filmAssets = tasks.register<FilmAssetsTask>("generateFilmAssets") {
    sourceFile.set(rootProject.layout.projectDirectory.file("native/spektrafilm/assets/SpektraHanatos2025Spectra.f32"))
    outputDirectory.set(layout.buildDirectory.dir("generated/filmAssets"))
}
// Rawr 17U fork: the settings search index is generated from the settings sources at build time, so options Ed
// adds to his pages are searchable with no change here. It reads every row title literal a settings page shows
// (title = "...", label = "...", or the first argument of the row composables), follows the calls from each page
// composable (SettingsSections.kt) and the page links (OpenSection / HubRow / home entries) to build the path.
abstract class SettingsSearchIndexTask : DefaultTask() {
    @get:InputDirectory abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    private class Fn(val name: String, val body: String)

    @TaskAction fun generate() {
        val files = sourceDirectory.get().asFile.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val functions = HashMap<String, MutableList<Fn>>()
        var sectionsSource = ""
        var modelsSource = ""
        for (file in files) {
            val text = stripComments(file.readText())
            if (file.name == "SettingsSections.kt") sectionsSource = text
            if (file.name == "SettingsModels.kt") modelsSource = text
            for (fn in parseFunctions(text)) functions.getOrPut(fn.name) { mutableListOf() }.add(fn)
        }
        val sectionTitles = LinkedHashMap<String, String>()
        Regex("""enum class SettingsSection\(val title: String\)\s*\{([^}]*)\}""").find(modelsSource)?.let { m ->
            Regex("""(\w+)\("([^"]*)"\)""").findAll(m.groupValues[1]).forEach { sectionTitles[it.groupValues[1]] = it.groupValues[2] }
        }
        check(sectionTitles.isNotEmpty()) { "SettingsSection enum not found" }
        // Page composable of each section: the first call in its `SettingsSection.X -> { ... }` branch.
        val pageOf = LinkedHashMap<String, String>()
        Regex("""SettingsSection\.(\w+)\s*->\s*\{\s*(\w+)\(""").findAll(sectionsSource).forEach {
            pageOf[it.groupValues[1]] = it.groupValues[2]
        }
        check(pageOf.isNotEmpty()) { "SettingsSectionContent branches not found" }

        val callRegex = Regex("""\b([A-Za-z_]\w*)\s*(?:<[^<>()]*>)?\s*\(""")
        fun closure(root: String): List<Fn> {
            val seen = HashSet<String>()
            val out = ArrayList<Fn>()
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val name = queue.removeFirst()
                if (!seen.add(name)) continue
                val defs = functions[name] ?: continue
                for (fn in defs) {
                    out.add(fn)
                    callRegex.findAll(fn.body).forEach { call ->
                        val callee = call.groupValues[1]
                        // Another page's composable is that page's options, not this one's.
                        if (callee != name && callee in functions && callee !in pageOf.values) queue.add(callee)
                    }
                }
            }
            return out
        }

        val linkRegex = Regex("""SettingsSection\.(\w+)""")
        val homeRegex = Regex("""SettingsHomeEntry\(\s*SettingsSection\.(\w+)""")
        val titleRegexes = listOf(
            Regex("""\b(?:title|label)\s*=\s*"((?:[^"\\$]|\\[^$])*)""""),
            Regex("""\b(?:SettingsRow|SettingsSwitchRow|SettingsSelectionRow|DropdownChoiceRow|ToggleSubmenuRow|SettingsGroup)\(\s*"((?:[^"\\$]|\\[^$])*)"""")
        )

        // Page paths, breadth first from the home page.
        val homeBody = functions["SettingsHome"]?.joinToString("\n") { it.body } ?: error("SettingsHome not found")
        val path = LinkedHashMap<String, List<String>>()
        val queue = ArrayDeque<String>()
        homeRegex.findAll(homeBody).forEach { val s = it.groupValues[1]; if (s !in path) { path[s] = listOf(s); queue.add(s) } }
        val pageFunctions = HashMap<String, List<Fn>>()
        while (queue.isNotEmpty()) {
            val section = queue.removeFirst()
            val page = pageOf[section] ?: continue
            val fns = closure(page)
            pageFunctions[section] = fns
            for (fn in fns) linkRegex.findAll(fn.body).forEach {
                val target = it.groupValues[1]
                if (target in sectionTitles && target in pageOf && target !in path) {
                    path[target] = path.getValue(section) + target
                    queue.add(target)
                }
            }
        }

        // A row named like a page (a hub's link, a page's own group) is found as that page.
        val pageTitles = path.keys.map { sectionTitles.getValue(it).lowercase() }.toSet()
        val rows = LinkedHashSet<List<String>>()
        for ((section, sections) in path) {
            val where = sections.joinToString(" › ") { sectionTitles.getValue(it) }
            rows.add(listOf(sectionTitles.getValue(section), section, sections.dropLast(1).joinToString(" › ") { sectionTitles.getValue(it) }))
            for (fn in pageFunctions[section].orEmpty()) for (regex in titleRegexes) regex.findAll(fn.body).forEach {
                val title = it.groupValues[1].trim()
                // Skips code identifiers (camelCase) and one-letter labels.
                val identifier = Regex("[a-z]+[A-Z]\\w*").matches(title)
                if (title.length >= 2 && title.any { c -> c.isLetter() } && !identifier && title.lowercase() !in pageTitles) {
                    rows.add(listOf(title, section, where))
                }
            }
        }

        val out = StringBuilder()
        out.append("package com.rawr.camera.settings.ui;\n\n")
        out.append("// Generated by the generateSettingsSearchIndex task from the settings sources. Do not edit.\n")
        out.append("final class SettingsSearchIndexData {\n    private SettingsSearchIndexData() {}\n\n")
        out.append("    /** Rows of {title, SettingsSection name, page path}. */\n")
        out.append("    static final String[][] ENTRIES = {\n")
        for (row in rows) out.append("        {").append(row.joinToString(", ") { javaString(it) }).append("},\n")
        out.append("    };\n}\n")
        val target = outputDirectory.file("com/rawr/camera/settings/ui/SettingsSearchIndexData.java").get().asFile
        target.parentFile.mkdirs()
        target.writeText(out.toString())
    }

    private fun javaString(value: String): String {
        val sb = StringBuilder("\"")
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                // Kotlin escapes \" \\ \n \t \uXXXX are valid Java; \' too.
                sb.append(c).append(value[i + 1]); i += 2; continue
            }
            if (c.code < 0x20 || c.code > 0x7e) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            i++
        }
        return sb.append('"').toString()
    }

    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2; continue }
                if (c == '"') inString = false
                i++
                continue
            }
            if (c == '"') { inString = true; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) text.length else end + 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    // Index just past the string starting at [start] (a '"'), skipping templates such as "${if (a) "x" else "y"}".
    private fun skipString(text: String, start: Int): Int {
        val raw = text.startsWith("\"\"\"", start)
        var i = start + if (raw) 3 else 1
        while (i < text.length) {
            val c = text[i]
            if (!raw && c == '\\') { i += 2; continue }
            if (raw && text.startsWith("\"\"\"", i)) return i + 3
            if (!raw && c == '"') return i + 1
            if (c == '$' && i + 1 < text.length && text[i + 1] == '{') { i = skipBlock(text, i + 1); continue }
            i++
        }
        return text.length
    }

    // Index just past the block whose opening bracket is at [open].
    private fun skipBlock(text: String, open: Int): Int {
        val opening = text[open]
        val closing = if (opening == '{') '}' else ')'
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> { i = skipString(text, i); continue }
                c == '\'' && i + 2 < text.length -> {
                    // Char literal: '{', '"', '\n'.
                    val end = text.indexOf('\'', i + if (text[i + 1] == '\\') 3 else 2)
                    if (end in (i + 2)..(i + 8)) { i = end + 1; continue }
                }
                c == opening -> depth++
                c == closing -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    private fun parseFunctions(text: String): List<Fn> {
        val out = ArrayList<Fn>()
        val header = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        var from = 0
        while (true) {
            val m = header.find(text, from) ?: break
            val paramsEnd = skipBlock(text, m.range.last)
            var i = paramsEnd
            // Skip a return type up to the body.
            while (i < text.length && text[i] != '{' && text[i] != '=' && text[i] != '\n') i++
            if (i < text.length && text[i] == '{') {
                val end = skipBlock(text, i)
                out.add(Fn(m.groupValues[1], text.substring(i, end)))
                from = i + 1 // nested local functions are found too
            } else {
                from = paramsEnd
            }
        }
        return out
    }
}
val settingsSearchIndex = tasks.register<SettingsSearchIndexTask>("generateSettingsSearchIndex") {
    sourceDirectory.set(layout.projectDirectory.dir("src/main/kotlin/com/rawr/camera/settings"))
    outputDirectory.set(layout.buildDirectory.dir("generated/settingsSearch"))
}
// Rawr 17U fork: the UI language template (assets/i18n/template.json) is generated from the UI sources at build
// time: every plain string literal that reads as UI text, with an empty value. An exported language file starts
// from it, so texts Ed or the fork add later show up for translators with no change here.
abstract class I18nTemplateTask : DefaultTask() {
    @get:InputFiles abstract val sourceDirectories: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    private class Lexer(val src: String) {
        val out = ArrayList<String>()
        private val n = src.length

        /** Code from [start]; with [toBrace] stops after the `}` that closes a `${` template. */
        fun code(start: Int, toBrace: Boolean): Int {
            var i = start
            var depth = 0
            while (i < n) {
                val c = src[i]
                when {
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) n else it }
                    src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    c == '\'' -> {
                        var j = i + 1
                        j += if (j < n && src[j] == '\\') 2 else 1
                        while (j < n && src[j] != '\'') j++
                        i = j + 1
                    }
                    src.startsWith("\"\"\"", i) -> i = chain(i + 3, raw = true)
                    c == '"' -> i = chain(i + 1, raw = false)
                    c == '{' -> { depth++; i++ }
                    c == '}' -> {
                        if (toBrace && depth == 0) return i + 1
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
            return i
        }

        /** A literal and its `+ "..."` continuations, joined into the one text the UI shows. */
        private fun chain(start: Int, raw: Boolean): Int {
            var (i, text) = string(start, raw)
            while (true) {
                var j = i
                while (j < n && src[j].isWhitespace()) j++
                if (j >= n || src[j] != '+') break
                var k = j + 1
                while (k < n && src[k].isWhitespace()) k++
                if (!src.startsWith("\"", k) || src.startsWith("\"\"\"", k)) break
                val (end, next) = string(k + 1, raw = false)
                i = end
                text = if (text == null || next == null) null else text + next
            }
            text?.let(out::add)
            return i
        }

        /** Returns the end index and the text, or null for a string template. */
        private fun string(start: Int, raw: Boolean): Pair<Int, String?> {
            val text = StringBuilder()
            var template = false
            var i = start
            while (i < n) {
                val c = src[i]
                if (raw && src.startsWith("\"\"\"", i)) {
                    var j = i + 3
                    while (j < n && src[j] == '"') j++
                    text.append(src, i, j - 3)
                    return j to text.toString().takeUnless { template }
                }
                if (!raw && c == '\\' && i + 1 < n) {
                    val e = src[i + 1]
                    if (e == 'u' && i + 5 < n) {
                        text.append(src.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                    } else {
                        text.append(when (e) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                        i += 2
                    }
                    continue
                }
                if (!raw && c == '"') return i + 1 to text.toString().takeUnless { template }
                if (c == '$' && i + 1 < n && src[i + 1] == '{') {
                    template = true
                    i = code(i + 2, toBrace = true)
                    continue
                }
                if (c == '$' && i + 1 < n && (src[i + 1].isLetter() || src[i + 1] == '_')) {
                    template = true
                    i++
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                    continue
                }
                text.append(c)
                i++
            }
            return i to null
        }
    }

    private val identifier = Regex("[a-z0-9_.:/\\-]+")
    private val word = Regex("[A-Za-z0-9_]+")
    private val letters = Regex("[A-Za-z]{2}")
    private val format = Regex("%[-0-9.]*[dfsx]")
    private val skippedPrefixes = listOf("com.", "android.", "org.", "http", "image/", "application/", "/", "#",
        "Rounded.", "Outlined.", "Filled.", "AutoMirrored.", "precision ", "uniform")

    private fun isUiText(s: String): Boolean =
        s.length >= 2 && letters.containsMatchIn(s) && !identifier.matches(s) &&
            !(word.matches(s) && ('_' in s || (s[0].isLowerCase() && s.any { it.isUpperCase() }))) &&
            skippedPrefixes.none { s.startsWith(it) } && !format.containsMatchIn(s) &&
            !('\n' in s && ('{' in s || ';' in s))

    private fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\t' -> b.append("\\t")
                c < ' ' -> b.append(String.format("\\u%04x", c.code))
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    @TaskAction fun generate() {
        val keys = sortedSetOf<String>()
        sourceDirectories.files.sortedBy { it.path }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.forEach { file ->
                val lexer = Lexer(file.readText())
                lexer.code(0, toBrace = false)
                lexer.out.filterTo(keys, ::isUiText)
            }
        }
        val target = outputDirectory.file("i18n/template.json").get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            keys.joinToString(",\n", prefix = "{\n\"@note\": \"Generated from the UI sources. Do not edit.\",\n", postfix = "\n}\n") {
                quote(it) + ": \"\""
            }
        )
    }
}
val i18nTemplate = tasks.register<I18nTemplateTask>("generateI18nTemplate") {
    sourceDirectories.from(
        layout.projectDirectory.dir("src/main/kotlin/com/rawr/camera/settings"),
        layout.projectDirectory.dir("src/main/kotlin/com/rawr/camera/ui"),
        layout.projectDirectory.dir("src/main/kotlin/com/rawr/camera/model"),
        layout.projectDirectory.dir("src/main/kotlin/com/rawr/camera/i18n")
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/i18nTemplate"))
}
// Rawr 17U: both builds install as their own app, next to the official Rawr
// (com.rawr.camera, signed by its author) and its debug build
// (com.rawr.camera.debug), so a tester can carry Ed's and this fork's at once.
// Code and namespace stay com.rawr.camera.
val releaseApplicationId = "x17u.rawr.camera"
val debugApplicationId = "x17u.rawr.camera.debug"

// Rawr 17U release number on top of Ed's version: the release workflow passes -Prawr17uRelease=N from a
// v<version>-17u.<N> tag. Release versionName <version>-17u.<N>, versionCode <Ed's code> * 100 + N, so every
// fork release installs over the previous one and the code keeps rising when Ed's does. 0: local build.
val forkRelease = providers.gradleProperty("rawr17uRelease").orNull?.toIntOrNull() ?: 0
fun forkVersionName(base: String) = if (forkRelease > 0) "$base-17u.$forkRelease" else "$base-17u"

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set(debugApplicationId)
        variant.outputs.forEach { it.versionName.set(debugVersionName) }
    }
    // Optimized build for device measurements (debug key, installs next to the debug build).
    onVariants(selector().withBuildType("benchmark")) { variant ->
        variant.applicationId.set("x17u.rawr.camera.benchmark")
    }
    onVariants(selector().withBuildType("release")) { variant ->
        variant.applicationId.set(releaseApplicationId)
        val base = requireNotNull(android.defaultConfig.versionName)
        val code = requireNotNull(android.defaultConfig.versionCode)
        variant.outputs.forEach {
            it.versionName.set(forkVersionName(base))
            it.versionCode.set(code * 100 + forkRelease)
        }
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(filmAssets, FilmAssetsTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(voiceModelAssets, VoiceModelAssetsTask::outputDirectory)
        variant.sources.java?.addGeneratedSourceDirectory(settingsSearchIndex, SettingsSearchIndexTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(i18nTemplate, I18nTemplateTask::outputDirectory)
    }
}

android {
    namespace = "com.rawr.camera"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.rawr.camera"
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = 36
        versionCode = 28
        versionName = "0.5.1"
    }

    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Register Kotlin directories in the Android source model used by AGP and IDEs.
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("debug").java.srcDir("src/debug/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("test").java.srcDir("src/uiTest/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin", "src/uiTest/kotlin")
        // Preview/test fixtures belong in debug builds, never in the release APK. Release unit
        // tests compile the same sources directly because they do not inherit the debug variant.
        getByName("debug").java.srcDir("src/fixtures/kotlin")
        getByName("testRelease").java.srcDir("src/fixtures/kotlin")
        maybeCreate("testBenchmark").java.srcDir("src/fixtures/kotlin")
    }

    // Lowest precedence: the git-ignored signing/release.properties (keystore path relative to the repo root).
    val localSigning = Properties().apply {
        rootProject.file("signing/release.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
    }
    val releaseSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { key ->
        providers.gradleProperty("rawr.signing.$key").orElse(providers.environmentVariable("RAWR_SIGNING_$key")).orNull
            ?: localSigning.getProperty("rawr.signing.$key")
    }
    require(releaseSigning.values.all { it == null } || releaseSigning.values.all { !it.isNullOrBlank() }) {
        "Provide all four RAWR_SIGNING_* values, or none for an unsigned release."
    }
    if (releaseSigning.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(requireNotNull(releaseSigning["STORE_FILE"]))
            storePassword = releaseSigning["STORE_PASSWORD"]
            keyAlias = releaseSigning["KEY_ALIAS"]
            keyPassword = releaseSigning["KEY_PASSWORD"]
        }
    }

    // Optional shared debug key (CI keeps it as a GitHub secret). Without it a debug build is signed with the
    // machine's own generated debug key, so a debug APK from another machine, or from a fresh CI runner, has a
    // different signature and cannot update an earlier install. Same lookup as release; partial configuration fails.
    val localDebugSigning = Properties().apply {
        rootProject.file("signing/debug.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
    }
    val debugSigning = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { key ->
        providers.gradleProperty("rawr.debugSigning.$key").orElse(providers.environmentVariable("RAWR_DEBUG_SIGNING_$key")).orNull
            ?: localDebugSigning.getProperty("rawr.debugSigning.$key")
    }
    require(debugSigning.values.all { it == null } || debugSigning.values.all { !it.isNullOrBlank() }) {
        "Provide all four RAWR_DEBUG_SIGNING_* values, or none to use the machine's own debug key."
    }
    if (debugSigning.values.all { it != null }) {
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file(requireNotNull(debugSigning["STORE_FILE"]))
            storePassword = debugSigning["STORE_PASSWORD"]
            keyAlias = debugSigning["KEY_ALIAS"]
            keyPassword = debugSigning["KEY_PASSWORD"]
        }
    }

    // Rawr 17U: fixed debug key (root debug.keystore, standard debug credentials): every CI
    // runner would otherwise sign with a fresh key and block installing updates. Applied after
    // Ed's shared-key option above (#138), so the fork's key wins when the file is present.
    if (rootProject.file("debug.keystore").exists()) {
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // The id itself is set in androidComponents above (debugApplicationId).
            resValue("string", "app_name", "Rawr 17U Debug")
            resValue("string", "renderer_launcher_label", "Rawr 17U Debug Renderer")
            // Native EXIF Software tag; must match BuildConfig.VERSION_NAME.
            externalNativeBuild { cmake { arguments += "-DRAWR_VERSION_FILE=${debugNativeVersionFile.absolutePath}" } }
        }
        release {
            resValue("string", "app_name", "Rawr 17U")
            resValue("string", "renderer_launcher_label", "Rawr 17U Renderer")
            versionNameSuffix = "-17u"
            // Native EXIF Software tag; must match BuildConfig.VERSION_NAME (set in androidComponents above).
            externalNativeBuild { cmake { arguments += "-DRAWR_VERSION_NAME=${forkVersionName(requireNotNull(defaultConfig.versionName))}" } }
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "SYMBOL_TABLE"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            resValue("string", "app_name", "Rawr 17U Benchmark")
            resValue("string", "renderer_launcher_label", "Rawr 17U Benchmark Renderer")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    packaging {
        // Keep dependency license metadata in the APK (Apache-2.0 requires the
        // license and notices be redistributed). pickFirsts keeps one copy to
        // avoid duplicate-resource merge failures, rather than dropping them.
        resources.pickFirsts += "/META-INF/{AL2.0,LGPL2.1}"
        // libadrenotools locates its hook .so files through ApplicationInfo.nativeLibraryDir.
        // They must be extracted there rather than loaded directly from the APK.
        jniLibs.useLegacyPackaging = true
    }
    // Film lookup tables are opened with AAssetManager.openFd and must remain uncompressed.
    // The Voice Shutter speech model is already a zip: keep it as it is (the app unpacks it itself).
    androidResources { noCompress += listOf("f32", "zip") }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        // This app intentionally supports arm64 phones; dependency upgrades are reviewed separately.
        disable += setOf("ChromeOsAbiSupport", "GradleDependency")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.media3:media3-muxer:1.10.0")
    implementation("sh.calvin.reorderable:reorderable:3.0.0")
    // Rawr 17U fork: Voice Shutter's offline speech recognizer (Apache-2.0; brings its JNA bridge, arm64 only here).
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Rawr 17U fork: Watch Remote (Wear OS Data Layer).
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // Exercise portable capture/renderer recipes on the JVM rather than Android stub methods.
    testImplementation("org.json:json:20260719")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// Keep Java compilation as strict as Kotlin compilation. There are currently no Java sources,
// but this protects future Android/application adapters from introducing unchecked warnings.
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    // AGP's generated BuildConfig starts with a file-level documentation comment,
    // which javac 23+ reports as dangling. Keep user Java strict while excluding that
    // generated-source-only diagnostic only on compilers that recognize the lint key.
    val allLint = if (JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_23)) {
        "-Xlint:all,-dangling-doc-comments"
    } else {
        "-Xlint:all"
    }
    options.compilerArgs.addAll(listOf(allLint, "-Werror"))
}

// AgxPresetSyncTest reads the native AgX preset table; rerun it when the header changes.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("native/tonemap_engine/include/tonemap/Agx.h"))
        .withPropertyName("agxPresetHeader").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Kotlin LSP's Android importer reads generated source providers and javac arguments.
// Its prepare task currently misses BuildConfig and nested test preparation (upstream #253).
// Wire the producers into that task only during language-server import; ordinary Gradle
// builds retain their task graph, and the editor keeps both main and test source sets.
if (providers.systemProperty("com.jetbrains.ls.imports.gradle").orNull == "true") {
    tasks.matching { it.name == "prepareKotlinIdeaImport" }.configureEach {
        dependsOn(tasks.matching {
            (it.name.startsWith("generate") && it.name.endsWith("BuildConfig")) ||
                it.name.startsWith("javaPreCompile") ||
                (it.name.startsWith("process") && it.name.endsWith("Resources"))
        })
    }
}
