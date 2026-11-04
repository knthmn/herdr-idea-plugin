package dev.knthmn.herdr.core

data class HcVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<HcVersion> {
    override fun compareTo(other: HcVersion): Int =
        compareValuesBy(this, other, HcVersion::major, HcVersion::minor, HcVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /** The oldest herdr-core release that the plugin supports. */
        val MINIMUM = HcVersion(0, 1, 16)

        /** True when [latest] is newer than [installed] and is not the [skipped] release. */
        fun isUpdate(installed: HcVersion?, latest: HcVersion?, skipped: String = ""): Boolean =
            installed != null &&
                latest != null &&
                latest > installed &&
                latest.toString() != skipped

        private val PATTERN = Regex("""(?:^|\s)v?(\d+)\.(\d+)\.(\d+)(?:[-+.\s]|$)""")

        /** Reads `hc 0.1.17`, `0.1.17` or `0.1.17-rc.1`. */
        fun parse(text: String): HcVersion? =
            PATTERN.find(text.trim())?.destructured?.let { (major, minor, patch) ->
                runCatching { HcVersion(major.toInt(), minor.toInt(), patch.toInt()) }.getOrNull()
            }
    }
}
