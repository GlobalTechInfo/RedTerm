package com.redtermapp.distro

data class Distro(
    val name: String,
    val displayName: String,
    val description: String,
    val baseUrl: String,
    val sha256: Map<String, String>,
    val prootArchs: List<String>,
    val installSizeMb: Int,
    val packageManager: String,
    val archOverride: Map<String, String> = emptyMap()
) {
    fun tarballUrlFor(deviceArch: String): String {
        val arch = abiToProotArch(deviceArch)
        val urlArch = archOverride[arch] ?: arch
        return baseUrl.replace("{arch}", urlArch)
    }

    fun sha256For(deviceArch: String): String {
        val arch = abiToProotArch(deviceArch)
        return sha256[arch] ?: ""
    }
}

fun abiToProotArch(abi: String): String = when {
    abi.startsWith("arm64") || abi == "aarch64" -> "aarch64"
    abi.startsWith("armeabi") || abi == "armv7" -> "arm"
    abi.startsWith("x86_64") -> "x86_64"
    abi.startsWith("x86") || abi == "i686" -> "i686"
    else -> "aarch64"
}

enum class DistroArch(val abi: String, val prootKey: String) {
    AARCH64("arm64-v8a", "aarch64"),
    ARM_V7("armeabi-v7a", "arm"),
    X86_64("x86_64", "x86_64"),
    I686("x86", "i686");
}
