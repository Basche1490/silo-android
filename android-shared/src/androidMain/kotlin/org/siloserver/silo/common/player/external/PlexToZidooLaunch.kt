package org.siloserver.silo.common.player.external

/**
 * Builds the launch URI for PlexToZidoo's "ZDMC" mode.
 *
 * PlexToZidoo (https://github.com/bowlingbeeg/PlexToZidoo) is a small Android
 * app that sits between a media client and the native Zidoo player. When the
 * URI it is opened with carries `PlexToZidoo-*` query parameters it skips its
 * Plex lookup entirely: it removes those parameters, applies the path
 * substitution rules configured in PlexToZidoo's own settings (server path to
 * an SMB/NFS location, plus credentials), and starts the Zidoo player at the
 * requested position. That is the contract the ZidooPlexMod Kodi add-on uses,
 * and this class produces the same shape.
 *
 * What PlexToZidoo reads, and how:
 * - `PlexToZidoo-ViewOffset`: start position in milliseconds (an Int).
 * - `PlexToZidoo-Title`: title the Zidoo player shows, URL-decoded.
 * - `PlexToZidoo-AudioIndex`: Zidoo `audio_idx`, 0-based.
 * - `PlexToZidoo-SubtitleIndex`: Zidoo `subtitle_idx`, 1-based.
 * - `PlexToZidoo-Path`: the file path as the media server knows it, URL-decoded
 *   and then matched against the user's substitution rules.
 *
 * Each value is matched with `[^&]+` and decoded with `URLDecoder`, so values
 * must be non-empty and percent-encoded. A literal `+` would decode to a space,
 * so [percentEncode] never emits one.
 */
object PlexToZidooLaunch {
    /** PlexToZidoo's application id. */
    const val PACKAGE_NAME = "com.hpn789.plextozidoo"

    /** The activity that handles the launch. It is exported. */
    const val ACTIVITY_CLASS = "com.hpn789.plextozidoo.Play"

    private const val PARAM_PREFIX = "PlexToZidoo-"

    data class Request(
        /** The file path exactly as Silo reports it; PlexToZidoo's rules match against this. */
        val serverFilePath: String,
        val title: String,
        val startPositionMs: Long,
        /** Zidoo `audio_idx` (0-based), or null to let the Zidoo player choose. */
        val audioIndex: Int? = null,
        /** Zidoo `subtitle_idx` (1-based), or null to let the Zidoo player choose. */
        val subtitleIndex: Int? = null,
    )

    /**
     * Base of every launch URI. PlexToZidoo plays the base only when none of
     * its substitution rules match the `Path` parameter, so the Zidoo player
     * then fails on a name that says why, rather than playing something else.
     *
     * The base is deliberately not the file path. PlexToZidoo skips encoding
     * the substituted path whenever the base contains `/mnt/nfs/`, so a server
     * path under `/mnt/nfs/` would reach the Zidoo player half-encoded. It is
     * also not a `file://` URI: on apps targeting Android 7 and later, sending
     * one to another app throws `FileUriExposedException`.
     */
    const val UNMAPPED_PLACEHOLDER = "/silo/no-path-substitution-matched"

    /** The `ACTION_VIEW` data string for [request]. */
    fun buildUri(request: Request): String {
        val params = StringBuilder()

        fun add(name: String, value: String) {
            params.append(if (params.isEmpty()) '?' else '&')
                .append(PARAM_PREFIX)
                .append(name)
                .append('=')
                .append(value)
        }

        add("ViewOffset", request.startPositionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toString())
        request.title.trim().takeIf { it.isNotEmpty() }?.let { add("Title", percentEncode(it, keepSlash = false)) }
        request.audioIndex?.takeIf { it >= 0 }?.let { add("AudioIndex", it.toString()) }
        request.subtitleIndex?.takeIf { it >= 1 }?.let { add("SubtitleIndex", it.toString()) }
        add("Path", percentEncode(request.serverFilePath, keepSlash = true))

        return UNMAPPED_PLACEHOLDER + params
    }

    /**
     * Percent-encodes [value] as UTF-8, leaving only RFC 3986 unreserved
     * characters (and `/` when [keepSlash]) as they are.
     */
    internal fun percentEncode(value: String, keepSlash: Boolean): String {
        val out = StringBuilder(value.length + 16)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val unreserved = (c >= 'a'.code && c <= 'z'.code) ||
                (c >= 'A'.code && c <= 'Z'.code) ||
                (c >= '0'.code && c <= '9'.code) ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved || (keepSlash && c == '/'.code)) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[c shr 4])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
