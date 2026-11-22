package app.archivefetcher.data

/** How the per-console sub-folders of the library folder are named. */
enum class FrontendLayout(val id: String) {
    /** ES-DE, ArkOS, ROCKNIX: short system names such as `gbc` or `psx`. */
    EsDe("esde"),

    /** Batocera and Knulli. */
    Batocera("batocera"),

    /** The full console name, for frontends without a fixed convention. */
    Names("names");

    companion object {
        fun fromId(id: String?): FrontendLayout = entries.firstOrNull { it.id == id } ?: Names
    }
}

/** Folder names used by the common handheld/retro frontends, keyed by the console ids of the addon spec. */
object FrontendLayouts {
    private val common = mapOf(
        "nintendo-fds" to "fds", "nintendo-nes" to "nes", "nintendo-snes" to "snes", "nintendo-64" to "n64",
        "nintendo-64dd" to "n64dd", "nintendo-gb" to "gb", "nintendo-gbc" to "gbc", "nintendo-gba" to "gba",
        "nintendo-virtual-boy" to "virtualboy", "nintendo-pokemon-mini" to "pokemini", "nintendo-ds" to "nds",
        "nintendo-dsi" to "nds", "nintendo-wii" to "wii", "nintendo-wii-u" to "wiiu",
        "sony-ps1" to "psx", "sony-ps2" to "ps2", "sony-psp" to "psp", "sony-ps3" to "ps3", "sony-vita" to "psvita",
        "microsoft-xbox" to "xbox", "microsoft-xbox360" to "xbox360",
        "sega-master-system" to "mastersystem", "sega-mega-drive" to "megadrive", "sega-32x" to "sega32x",
        "sega-game-gear" to "gamegear", "sega-cd" to "segacd", "sega-saturn" to "saturn", "sega-dreamcast" to "dreamcast",
        "atari-2600" to "atari2600", "atari-5200" to "atari5200", "atari-7800" to "atari7800",
        "nec-pc-fx" to "pcfx", "nec-pc-98" to "pc98", "snk-neo-geo-cd" to "neogeocd", "panasonic-3do" to "3do",
        "fujitsu-fm-towns" to "fmtowns", "arcade-mame" to "mame", "arcade-fbneo" to "fbneo", "arcade-sega-naomi" to "naomi",
    )

    private val esDe = common + mapOf(
        "nintendo-3ds" to "n3ds", "nintendo-gamecube" to "gc", "atari-jaguar" to "atarijaguar",
        "atari-jaguar-cd" to "atarijaguarcd", "nec-pc-engine" to "tg16", "nec-pc-engine-cd" to "tg-cd",
        "philips-cdi" to "cdimono1",
    )

    private val batocera = common + mapOf(
        "nintendo-3ds" to "3ds", "nintendo-gamecube" to "gamecube", "atari-jaguar" to "jaguar",
        "nec-pc-engine" to "pcengine", "nec-pc-engine-cd" to "pcenginecd", "philips-cdi" to "cdi",
    )

    /** Name of the sub-folder for a console; consoles without a known short name fall back to their full name. */
    fun folderName(layout: FrontendLayout, consoleId: String, consoleName: String): String {
        val short = when (layout) {
            FrontendLayout.EsDe -> esDe[consoleId]
            FrontendLayout.Batocera -> batocera[consoleId]
            FrontendLayout.Names -> null
        }
        return short ?: RomNaming.safeFileName(consoleName)
    }
}
