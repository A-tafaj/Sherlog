package com.sherlog.filter

/**
 * Predefined filter profiles. A preset only sets the text include/exclude
 * lists; tag/PID/level/time selections the user made are left alone.
 */
data class Preset(
    val name: String,
    val excludeTexts: List<String> = emptyList(),
    val includeTexts: List<String> = emptyList(),
) {
    fun applyTo(state: FilterState): FilterState =
        state.copy(excludeTexts = excludeTexts, includeTexts = includeTexts)

    companion object {
        /**
         * Combines several presets into one exclude/include pair.
         *
         * Includes are unioned — they are OR-matched, so selecting Crash and
         * Network yields "crash lines or network lines", and everything else
         * falls out because a non-empty include list is a whitelist.
         *
         * Excludes are unioned too, minus any term a selected preset also
         * whitelists: Network excludes `CCodec`/`Audio` while Video includes
         * them, and without this an exclude would silently veto the other
         * preset's includes. The check is substring-based and
         * case-insensitive, matching how the engine compares them, so
         * excluding `Audio` also stands down for an `AudioTrack` include.
         */
        fun merge(presets: Collection<Preset>): Merged {
            val includes = presets.flatMap { it.includeTexts }.distinct()
            val excludes = presets.flatMap { it.excludeTexts }.distinct()
                .filterNot { ex -> includes.any { it.contains(ex, ignoreCase = true) } }
            return Merged(excludeTexts = excludes, includeTexts = includes)
        }

        val NETWORK = Preset(
            name = "Network Debug",
            excludeTexts = listOf("adbd", "CCodec", "Audio", "Surface", "OpenGL", "BufferQueue"),
            includeTexts = listOf(
                "OkHttp", "Retrofit", "DnsResolver", "NetworkMonitor", "ConnectivityService",
            ),
        )

        val CRASH = Preset(
            name = "Crash Debug",
            includeTexts = listOf("FATAL EXCEPTION", "AndroidRuntime", "Exception", "Caused by", "StackTrace"),
        )

        val VIDEO = Preset(
            name = "Video Debug",
            includeTexts = listOf("CCodec", "MediaCodec", "Camera", "Audio"),
        )

        val ALL = listOf(NETWORK, CRASH, VIDEO)

        fun byName(name: String): Preset? = ALL.firstOrNull { it.name == name }
    }

    /** The exclude/include lists produced by combining selected presets. */
    data class Merged(val excludeTexts: List<String>, val includeTexts: List<String>)
}
