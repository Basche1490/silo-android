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
 * requested position. When no rule matches, the Zidoo player opens what is
 * left of the URI once the parameters are removed. The ZidooPlexMod Kodi
 * add-on leaves Plex's streaming link there, which is why it plays without
 * any substitution rules; this class leaves Silo's stream there the same way.
 *
 * What PlexToZidoo reads, and how:
 * - `PlexToZidoo-ViewOffset`: start position in milliseconds (an Int).
 * - `PlexToZidoo-Title`: title the Zidoo player shows, URL-decoded.
 * - `PlexToZidoo-AudioIndex`: Zidoo `audio_idx`, 0-based.
 * - `PlexToZidoo-SubtitleIndex`: Zidoo `subtitle_idx`, 1-based.
 * - `PlexToZidoo-Path`: the file path as the media server knows it, URL-decoded
 *   and then matched against the user's substitution rules.
 * - `PlexToZidoo-PathMapped`: any value; play the rest of the URI as it is,
 *   without substitution. Used when Silo has no file path to offer.
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

    /** PlexToZidoo's settings, where its path substitution rules live. Its launcher activity, so exported. */
    const val SETTINGS_ACTIVITY_CLASS = "com.hpn789.plextozidoo.SettingsActivity"

    private const val PARAM_PREFIX = "PlexToZidoo-"

    data class Request(
        /**
         * The file path exactly as Silo reports it; PlexToZidoo's rules match
         * against this. Null when the server doesn't share paths with this account.
         */
        val serverFilePath: String?,
        val title: String,
        val startPositionMs: Long,
        /** Zidoo `audio_idx` (0-based), or null to let the Zidoo player choose. */
        val audioIndex: Int? = null,
        /** Zidoo `subtitle_idx` (1-based), or null to let the Zidoo player choose. */
        val subtitleIndex: Int? = null,
        /**
         * What the Zidoo player opens when no substitution rule matches: Silo's
         * stream of the original file, normally the local relay's address
         * ([ZidooStreamRelay]). Null leaves [UNMAPPED_PLACEHOLDER] there instead.
         */
        val streamUrl: String? = null,
    )

    /**
     * Base of a launch URI that has no [Request.streamUrl]. PlexToZidoo plays
     * the base only when none of its substitution rules match the `Path`
     * parameter, so the Zidoo player then fails on a name that says why,
     * rather than playing something else.
     *
     * The base is deliberately not the file path. PlexToZidoo skips encoding
     * the substituted path whenever the base contains `/mnt/nfs/`, so a server
     * path under `/mnt/nfs/` would reach the Zidoo player half-encoded. It is
     * also not a `file://` URI: on apps targeting Android 7 and later, sending
     * one to another app throws `FileUriExposedException`.
     */
    const val UNMAPPED_PLACEHOLDER = "/silo/no-path-substitution-matched"

    /**
     * The `ACTION_VIEW` data string for [request]. Needs a
     * [Request.serverFilePath] or a [Request.streamUrl]; with neither there is
     * nothing to play, and this returns null.
     */
    fun buildUri(request: Request): String? {
        val path = request.serverFilePath?.takeIf { it.isNotBlank() }
        val stream = request.streamUrl?.takeIf { it.isNotBlank() }
        if (path == null && stream == null) return null
        val base = stream ?: UNMAPPED_PLACEHOLDER
        val params = StringBuilder()
        // A stream URL brings its own query string; PlexToZidoo strips its
        // parameters with either separator and leaves that query intact.
        var separator = if ('?' in base) '&' else '?'

        fun add(name: String, value: String) {
            params.append(separator)
                .append(PARAM_PREFIX)
                .append(name)
                .append('=')
                .append(value)
            separator = '&'
        }

        add("ViewOffset", request.startPositionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toString())
        request.title.trim().takeIf { it.isNotEmpty() }?.let { add("Title", percentEncode(it, keepSlash = false)) }
        request.audioIndex?.takeIf { it >= 0 }?.let { add("AudioIndex", it.toString()) }
        request.subtitleIndex?.takeIf { it >= 1 }?.let { add("SubtitleIndex", it.toString()) }
        if (path != null) {
            add("Path", percentEncode(path, keepSlash = true))
        } else {
            // No path to match rules against: play the stream as it is.
            add("PathMapped", "1")
        }

        return base + params
    }

    /** [url] with `name=value` added to its query string, [value] percent-encoded. */
    fun withQueryParameter(url: String, name: String, value: String): String =
        url + (if ('?' in url) '&' else '?') + name + '=' + percentEncode(value, keepSlash = false)

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
