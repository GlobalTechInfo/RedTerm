package com.redtermapp.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object ProotInstaller {
    private const val TAG = "ProotInstaller"
    private const val PROOT_BIN = "proot"

    private fun getAbi(): String {
        val abis = android.os.Build.SUPPORTED_64_BIT_ABIS
        if (abis.isNotEmpty()) {
            return when (abis[0]) {
                "arm64-v8a" -> "arm64-v8a"
                "x86_64" -> "x86_64"
                else -> abis[0]
            }
        }
        val abis32 = android.os.Build.SUPPORTED_32_BIT_ABIS
        if (abis32.isNotEmpty()) {
            return when (abis32[0]) {
                "armeabi-v7a" -> "armeabi-v7a"
                "x86" -> "x86"
                else -> abis32[0]
            }
        }
        return "arm64-v8a"
    }

    private fun zipEntryPath(): String {
        val abi = getAbi()
        // NDK stores libs under lib/<abi>/lib*.so inside the APK
        return "lib/$abi/libproot.so"
    }

    fun getProotPath(context: Context): String? {
        // 1. nativeLibraryDir (system-extracted jniLibs)
        val nativePath = "${context.applicationInfo.nativeLibraryDir}/libproot.so"
        val nativeFile = File(nativePath)
        if (nativeFile.canExecute()) {
            Log.i(TAG, "Found proot at nativeLibraryDir: $nativePath")
            return nativePath
        }
        if (nativeFile.exists()) {
            nativeFile.setExecutable(true, false)
            if (nativeFile.canExecute()) return nativePath
        }

        // 2. Already extracted to code_cache
        val cachePath = "${context.codeCacheDir}/$PROOT_BIN/$PROOT_BIN"
        val cacheFile = File(cachePath)
        if (cacheFile.canExecute()) {
            Log.i(TAG, "Found proot at code_cache: $cachePath")
            return cachePath
        }

        // 3. Extract from APK zip to code_cache
        try {
            val apkPath = context.applicationInfo.sourceDir
            Log.i(TAG, "Extracting proot from APK: $apkPath [entry=${zipEntryPath()}]")
            val destDir = File(context.codeCacheDir, PROOT_BIN)
            destDir.mkdirs()

            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry(zipEntryPath())
                    ?: run {
                        Log.e(TAG, "Entry not found in APK: ${zipEntryPath()}")
                        // List available lib entries for debugging
                        val libEntries = zip.entries().asSequence()
                            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                            .map { it.name }
                            .toList()
                        Log.e(TAG, "Available lib entries: $libEntries")
                        return@getProotPath null
                    }

                zip.getInputStream(entry).use { input ->
                    val tmpFile = File(destDir, "$PROOT_BIN.tmp")
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                    tmpFile.setReadable(true, false)
                    tmpFile.setWritable(false)
                    tmpFile.setExecutable(true, false)
                    cacheFile.delete()
                    if (tmpFile.renameTo(cacheFile)) {
                        cacheFile.setExecutable(true, false)
                        if (cacheFile.canExecute()) {
                            Log.i(TAG, "Extracted proot to: $cachePath")
                            return cachePath
                        } else {
                            Log.e(TAG, "Extracted but not executable: $cachePath")
                        }
                    } else {
                        // rename failed, try using tmp directly
                        if (tmpFile.canExecute()) {
                            val altPath = tmpFile.absolutePath
                            Log.i(TAG, "Using tmp file: $altPath")
                            return altPath
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract proot from APK", e)
        }

        return null
    }

    fun isInstalled(context: Context): Boolean {
        return getProotPath(context) != null
    }

    fun install(context: Context): Boolean {
        return getProotPath(context) != null
    }
}