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
    }
}
