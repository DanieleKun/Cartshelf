package app.archivefetcher.addon

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The addon format understood by this app; see the specification in the addon project. */
object AddonSpec {
    const val VERSION = 1
    const val SOURCE_ARCHIVE_ITEM = "archive.org-item"
    const val EXPAND_ZIP = "zip"
    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_CATEGORIES = 100
    const val MAX_CONSOLES = 500
    const val MAX_COLLECTIONS = 10_000
    const val MAX_COLLECTIONS_PER_CONSOLE = 2_000
}

@Serializable
data class AddonManifest(
    val spec: Int,
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val homepage: String? = null,
    val categories: List<ManifestCategory> = emptyList(),
) {
    val consoleCount: Int get() = categories.sumOf { it.consoles.size }
    val collectionCount: Int get() = categories.sumOf { category -> category.consoles.sumOf { it.collections.size } }
}

@Serializable
data class ManifestCategory(val id: String, val name: String, val consoles: List<ManifestConsole> = emptyList())

@Serializable
data class ManifestConsole(val id: String, val name: String, val collections: List<ManifestCollection> = emptyList())

@Serializable
data class ManifestCollection(val title: String, val source: ManifestSource, val format: String? = null)

@Serializable
data class ManifestSource(val type: String, val id: String? = null, val path: String? = null, val expand: String? = null)

/** Why an addon could not be added or refreshed. The UI turns the kind into a localized message. */
class AddonException(val kind: Kind, val detail: String? = null) : Exception(detail) {
    enum class Kind { InvalidUrl, InsecureUrl, TooLarge, NewerSpec, Invalid, Network }
}

/** Only https addresses are accepted; local http hosts are allowed in debug builds so addons can be tested. */
object AddonUrl {
    private val localHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private const val MAX_LENGTH = 2000

    fun parse(text: String, allowLocalHttp: Boolean): String {
        val url = text.trim().toHttpUrlOrNull() ?: throw AddonException(AddonException.Kind.InvalidUrl)
        val allowed = url.isHttps || (allowLocalHttp && url.host in localHosts)
        if (!allowed) throw AddonException(AddonException.Kind.InsecureUrl)
        val normalized = url.toString()
        if (normalized.length > MAX_LENGTH) throw AddonException(AddonException.Kind.InvalidUrl)
        return normalized
    }
}

/** Parses and validates a manifest. The rules mirror the reference validator of the addon project. */
object ManifestParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val addonId = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val slug = Regex("^[a-z0-9][a-z0-9-]{0,59}$")
    private val archiveId = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")
    private val format = Regex("^[a-z0-9]{1,8}$")

    /** Returns the manifest without collections whose source type this app does not know. */
    fun parse(text: String): AddonManifest {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw invalid("the file is not a JSON object")

        val specValue = root["spec"] as? JsonPrimitive
        val spec = specValue?.takeUnless { it.isString }?.intOrNull ?: throw invalid("spec: must be an integer")
        if (spec > AddonSpec.VERSION) throw AddonException(AddonException.Kind.NewerSpec)

        val manifest = try {
            json.decodeFromJsonElement<AddonManifest>(root)
        } catch (e: SerializationException) {
            throw invalid(e.message?.lineSequence()?.firstOrNull() ?: "unreadable manifest")
        } catch (e: IllegalArgumentException) {
            throw invalid(e.message?.lineSequence()?.firstOrNull() ?: "unreadable manifest")
        }
        return validate(manifest)
    }

    private fun invalid(message: String) = AddonException(AddonException.Kind.Invalid, message)

    private fun check(condition: Boolean, message: () -> String) {
        if (!condition) throw invalid(message())
    }

    private fun validate(manifest: AddonManifest): AddonManifest {
        check(manifest.spec == AddonSpec.VERSION) { "spec: must be ${AddonSpec.VERSION}" }
        check(addonId.matches(manifest.id)) { "id: must match ${addonId.pattern}" }
        check(manifest.name.isNotBlank() && manifest.name.length <= 60) { "name: 1-60 characters" }
        check(manifest.version.isNotBlank() && manifest.version.length <= 32) { "version: 1-32 characters" }
        check((manifest.description?.length ?: 0) <= 400) { "description: longer than 400 characters" }
        manifest.homepage?.let { check(it.startsWith("https://") && it.length <= 300) { "homepage: must be an https:// URL" } }
        check(manifest.categories.isNotEmpty()) { "categories: must not be empty" }
        check(manifest.categories.size <= AddonSpec.MAX_CATEGORIES) { "categories: more than ${AddonSpec.MAX_CATEGORIES}" }

        val categoryIds = HashSet<String>()
        val consoleIds = HashSet<String>()
        var consoles = 0
        var collections = 0
        val categories = manifest.categories.mapIndexed { c, category ->
            val where = "categories[$c]"
            check(slug.matches(category.id)) { "$where.id: must match ${slug.pattern}" }
            check(categoryIds.add(category.id)) { "$where.id: duplicate category id '${category.id}'" }
            check(category.name.isNotBlank() && category.name.length <= 60) { "$where.name: 1-60 characters" }
            category.copy(consoles = category.consoles.mapIndexed { k, console ->
                val consoleWhere = "$where.consoles[$k]"
                consoles++
                check(slug.matches(console.id)) { "$consoleWhere.id: must match ${slug.pattern}" }
                check(consoleIds.add(console.id)) { "$consoleWhere.id: duplicate console id '${console.id}'" }
                check(console.name.isNotBlank() && console.name.length <= 80) { "$consoleWhere.name: 1-80 characters" }
                check(console.collections.size <= AddonSpec.MAX_COLLECTIONS_PER_CONSOLE) {
                    "$consoleWhere.collections: more than ${AddonSpec.MAX_COLLECTIONS_PER_CONSOLE}"
                }
                collections += console.collections.size
                console.copy(collections = console.collections.mapIndexedNotNull { i, collection ->
                    validateCollection(collection, "$consoleWhere.collections[$i]")
                })
            })
        }
        check(consoles <= AddonSpec.MAX_CONSOLES) { "consoles: more than ${AddonSpec.MAX_CONSOLES} in total" }
        check(collections <= AddonSpec.MAX_COLLECTIONS) { "collections: more than ${AddonSpec.MAX_COLLECTIONS} in total" }
        return manifest.copy(categories = categories)
    }

    /** Returns null for collections with a source type from a newer spec, so they are skipped. */
    private fun validateCollection(collection: ManifestCollection, where: String): ManifestCollection? {
        check(collection.title.isNotBlank() && collection.title.length <= 160) { "$where.title: 1-160 characters" }
        collection.format?.let { check(format.matches(it)) { "$where.format: must match ${format.pattern}" } }
        if (collection.source.type != AddonSpec.SOURCE_ARCHIVE_ITEM) return null
        val id = collection.source.id
        check(id != null && archiveId.matches(id)) { "$where.source.id: must be an Internet Archive identifier" }
        collection.source.path?.let { path ->
            check(path.length <= 300 && isSafePath(path)) { "$where.source.path: must be a relative path without '..'" }
        }
        collection.source.expand?.let {
            check(it == AddonSpec.EXPAND_ZIP) { "$where.source.expand: must be \"${AddonSpec.EXPAND_ZIP}\"" }
        }
        return collection
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && '\u0000' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
