package app.archivefetcher.data

import app.archivefetcher.addon.ConsoleInfo
import java.io.IOException
import java.net.URLEncoder
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Where the box art of a game lives: the libretro-thumbnails project, one GitHub repository per system, with
 * pictures named like the No-Intro / Redump files (`Super Mario World (USA).png`).
 */
object CoverSources {
    private const val BASE = "https://raw.githubusercontent.com/libretro-thumbnails"

    /** Characters libretro replaces with `_` in thumbnail file names. */
    private val replaced = Regex("""[&*/:`<>?\\|"]""")
    /** Starts with a letter or digit, so `.` and `..` can never leave the libretro-thumbnails organisation. */
    private val safeRepository = Regex("""[A-Za-z0-9][A-Za-z0-9_.\-]{0,79}""")

    /**
     * The repository for each shared console id whose libretro name differs from the one the addons use.
     * Systems libretro has no pictures for are simply absent, so they are never asked for.
     */
    private val byConsoleId = mapOf(
        "nintendo-pokemon-mini" to "Nintendo_-_Pokemon_Mini",
        "sega-master-system" to "Sega_-_Master_System_-_Mark_III",
        "sega-mega-drive" to "Sega_-_Mega_Drive_-_Genesis",
        "sega-cd" to "Sega_-_Mega-CD_-_Sega_CD",
        "nec-pc-engine" to "NEC_-_PC_Engine_-_TurboGrafx_16",
        "nec-pc-engine-cd" to "NEC_-_PC_Engine_CD_-_TurboGrafx-CD",
        "panasonic-3do" to "The_3DO_Company_-_3DO",
        "arcade-mame" to "MAME",
        "arcade-fbneo" to "FBNeo_-_Arcade_Games",
        "arcade-sega-naomi" to "Sega_-_Naomi",
        "arcade-sega-naomi-2" to "Sega_-_Naomi_2",
        "pc-dos" to "DOS",
        "pc-doom" to "DOOM",
    )

    /** The libretro-thumbnails repositories, so a console named like one of them finds its pictures. */
    private val knownRepositories = setOf(
        "Amstrad_-_CPC", "Amstrad_-_GX4000", "Arduboy_Inc_-_Arduboy", "Atari_-_2600", "Atari_-_5200", "Atari_-_7800",
        "Atari_-_8-bit_Family", "Atari_-_Jaguar", "Atari_-_Lynx", "Atari_-_ST", "Bandai_-_WonderSwan",
        "Bandai_-_WonderSwan_Color", "Casio_-_Loopy", "Casio_-_PV-1000", "Coleco_-_ColecoVision", "Commodore_-_64",
        "Commodore_-_Amiga", "Commodore_-_CD32", "Commodore_-_CDTV", "Commodore_-_PET", "Commodore_-_Plus-4",
        "Commodore_-_VIC-20", "DOOM", "DOS", "Emerson_-_Arcadia_2001", "Entex_-_Adventure_Vision",
        "Epoch_-_Super_Cassette_Vision", "FBNeo_-_Arcade_Games", "Fairchild_-_Channel_F", "Funtech_-_Super_Acan",
        "GCE_-_Vectrex", "GamePark_-_GP32", "Hartung_-_Game_Master", "LeapFrog_-_Leapster_Learning_Game_System",
        "MAME", "Magnavox_-_Odyssey2", "Mattel_-_Intellivision", "Microsoft_-_MSX", "Microsoft_-_MSX2",
        "Microsoft_-_Xbox", "Microsoft_-_Xbox_360", "NEC_-_PC-88", "NEC_-_PC-98", "NEC_-_PC-FX",
        "NEC_-_PC_Engine_-_TurboGrafx_16", "NEC_-_PC_Engine_CD_-_TurboGrafx-CD", "NEC_-_PC_Engine_SuperGrafx",
        "Nintendo_-_Family_Computer_Disk_System", "Nintendo_-_GameCube", "Nintendo_-_Game_Boy",
        "Nintendo_-_Game_Boy_Advance", "Nintendo_-_Game_Boy_Color", "Nintendo_-_Nintendo_3DS",
        "Nintendo_-_Nintendo_3DS_Digital", "Nintendo_-_Nintendo_64", "Nintendo_-_Nintendo_64DD",
        "Nintendo_-_Nintendo_DS", "Nintendo_-_Nintendo_DSi", "Nintendo_-_Nintendo_Entertainment_System",
        "Nintendo_-_Pokemon_Mini", "Nintendo_-_Satellaview", "Nintendo_-_Sufami_Turbo",
        "Nintendo_-_Super_Nintendo_Entertainment_System", "Nintendo_-_Virtual_Boy", "Nintendo_-_Wii",
        "Nintendo_-_Wii_U", "Philips_-_CD-i", "Philips_-_Videopac", "RCA_-_Studio_II", "SNK_-_Neo_Geo",
        "SNK_-_Neo_Geo_CD", "SNK_-_Neo_Geo_Pocket", "SNK_-_Neo_Geo_Pocket_Color", "Sega_-_32X", "Sega_-_Dreamcast",
        "Sega_-_Game_Gear", "Sega_-_Master_System_-_Mark_III", "Sega_-_Mega-CD_-_Sega_CD",
        "Sega_-_Mega_Drive_-_Genesis", "Sega_-_Naomi", "Sega_-_Naomi_2", "Sega_-_PICO", "Sega_-_SG-1000",
        "Sega_-_Saturn", "Sharp_-_X1", "Sharp_-_X68000", "Sinclair_-_ZX_81", "Sinclair_-_ZX_Spectrum",
        "Sony_-_PlayStation", "Sony_-_PlayStation_2", "Sony_-_PlayStation_3", "Sony_-_PlayStation_3_Downloadable",
        "Sony_-_PlayStation_4", "Sony_-_PlayStation_Portable", "Sony_-_PlayStation_Vita",
        "Spectravideo_-_SVI-318_-_SVI-328", "The_3DO_Company_-_3DO", "Thomson_-_MOTO", "Tiger_-_Game.com",
        "VTech_-_CreatiVision", "VTech_-_V.Smile", "Watara_-_Supervision"
    )

    /**
     * The repository with this console's box art: the known one for its id, else the one its name matches, else the
     * one its addon names (so an addon cannot change the pictures of a console the app already knows). Null when
     * there is none, so no request is made for pictures that cannot exist.
     */
    fun repositoryFor(console: ConsoleInfo): String? {
        byConsoleId[console.id]?.let { return it }
        val fromName = console.name.replace(" / ", " - ").replace(' ', '_')
        if (fromName in knownRepositories) return fromName
        val named = console.thumbnails?.trim()?.replace(" / ", " - ")?.replace(' ', '_') ?: return null
        return named.takeIf { safeRepository.matches(it) }
    }

    fun boxArt(console: ConsoleInfo, fileName: String): String? {
        val repository = repositoryFor(console) ?: return null
        val name = replaced.replace(RomNaming.stemOf(fileName), "_")
        return "$BASE/${encode(repository)}/master/Named_Boxarts/${encode(name)}.png"
    }

    private fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /**
     * The address a link file points to, or null when [body] is not one. Many pictures in libretro-thumbnails are
     * links to another picture (the European box pointing to the American one); GitHub serves those as a line of
     * text with the target's name. Only a sibling in the same folder of the same repository is accepted.
     */
    fun linkTarget(from: HttpUrl, body: String): HttpUrl? {
        val target = body.trim()
        if (target.isEmpty() || target.length > MAX_LINK_LENGTH || '\n' in target || !target.endsWith(".png")) return null
        val name = target.removePrefix("./").removePrefix("../Named_Boxarts/")
        if ('/' in name || '\\' in name) return null
        return from.newBuilder().setPathSegment(from.pathSize - 1, name).build()
    }

    const val MAX_LINK_LENGTH = 512

    private val languageGroup = Regex("""\s*\((?:[A-Z][a-z](?:[,+][A-Z][a-z])*)\)""")
    private val firstGroup = Regex("""^([^(\[]*\([^)]*\))""")

    /**
     * Other names the same box may have, tried in order when the exact one is missing: without the language tags
     * (`Game (Japan) (En)` → `Game (Japan)`), then just the title and the first region (`Game (USA) (Rev 1)` →
     * `Game (USA)`), the way RetroArch falls back.
     */
    fun fallbackNames(name: String): List<String> {
        val withoutLanguages = languageGroup.replace(name, "").trim()
        val titleAndRegion = firstGroup.find(name)?.groupValues?.get(1)?.trim()
        return listOfNotNull(withoutLanguages, titleAndRegion).filter { it.isNotEmpty() && it != name }.distinct()
    }

    /** The same picture address with another file name. */
    fun withName(url: HttpUrl, name: String): HttpUrl = url.newBuilder().setPathSegment(url.pathSize - 1, "$name.png").build()
}

/**
 * Follows the link files of libretro-thumbnails, so the picture they point to is shown (and cached under the
 * address that was asked for). At most [MAX_HOPS] links are followed, and only on raw.githubusercontent.com.
 */
class CoverLinkInterceptor(private val host: String = RAW_HOST) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = fetch(chain, request)
        if (response.code != HTTP_NOT_FOUND || request.url.host != host) return response
        val name = request.url.pathSegments.last().removeSuffix(".png")
        // Each answer is closed before the next request: OkHttp allows only one open response per call.
        for (other in CoverSources.fallbackNames(name)) {
            response.close()
            response = fetch(chain, request.newBuilder().url(CoverSources.withName(request.url, other)).build())
            if (response.code != HTTP_NOT_FOUND) return response
        }
        return response
    }

    /** One picture, following libretro's link files. */
    private fun fetch(chain: Interceptor.Chain, original: Request): Response {
        var request = original
        var response = chain.proceed(request)
        repeat(MAX_HOPS) {
            if (!response.isSuccessful || request.url.host != host || isImage(response)) return response
            val body = response.peekBody(CoverSources.MAX_LINK_LENGTH.toLong() + 1).string()
            val target = CoverSources.linkTarget(request.url, body) ?: return response
            response.close()
            request = request.newBuilder().url(target).build()
            response = chain.proceed(request)
        }
        if (!isImage(response)) {
            response.close()
            throw IOException("cover link chain too long")
        }
        return response
    }

    private fun isImage(response: Response): Boolean = response.body?.contentType()?.type == "image"

    companion object {
        const val RAW_HOST = "raw.githubusercontent.com"
        private const val MAX_HOPS = 3
        private const val HTTP_NOT_FOUND = 404
    }
}
