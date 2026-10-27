package io.raftlab.cli

/** The build version of the raftlab CLI, read from the generated version resource. */
internal object RaftlabVersion {
    /** Classpath location of the generated version properties file. */
    const val RESOURCE: String = "/io/raftlab/cli/raftlab-version.properties"

    /** Version reported when the build did not provide one. */
    const val FALLBACK: String = "0.0.0-dev"

    /** The build version from [RESOURCE], or [FALLBACK] if the resource is missing or blank. */
    fun current(): String {
        val stream = RaftlabVersion::class.java.getResourceAsStream(RESOURCE)
        val props = java.util.Properties()
        stream?.use { props.load(it) }
        val version = props.getProperty("version")
        return version?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK
    }
}
