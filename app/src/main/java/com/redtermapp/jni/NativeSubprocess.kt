package com.redtermapp.jni

object NativeSubprocess {
    init {
        System.loadLibrary("subprocess")
    }

    external fun createSubprocess(
        shellPath: String,
        args: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int
    ): Int

    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    external fun writeToProcess(fd: Int, data: ByteArray): Int

    external fun readFromProcess(fd: Int, buffer: ByteArray): Int

    external fun closeProcess(fd: Int)
}
