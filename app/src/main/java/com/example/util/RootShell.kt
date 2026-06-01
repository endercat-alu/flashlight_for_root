package com.example.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object RootShell {
    private const val TAG = "RootShell"
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    /**
     * Checks if Root (superuser) is available on the device.
     */
    suspend fun checkRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val paths = arrayOf(
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        var suExists = false
        for (path in paths) {
            if (File(path).exists()) {
                suExists = true
                break
            }
        }

        // Always verify su execution to be 100% sure
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = BufferedWriter(OutputStreamWriter(process.outputStream))
            val isr = BufferedReader(InputStreamReader(process.inputStream))
            os.write("id\n")
            os.write("exit\n")
            os.flush()
            val output = isr.readLine() ?: ""
            val exitValue = process.waitFor()
            suExists = exitValue == 0 && (output.contains("uid=0") || output.contains("root"))
        } catch (e: Exception) {
            Log.e(TAG, "Root check execution failed", e)
        }
        suExists
    }

    /**
     * Starts a persistent `su` session.
     */
    @Synchronized
    fun startPersistentShell(): Boolean {
        if (process != null) return true
        return try {
            val p = Runtime.getRuntime().exec("su")
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            reader = BufferedReader(InputStreamReader(p.inputStream))
            Log.d(TAG, "Persistent root shell started successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent root shell", e)
            false
        }
    }

    /**
     * Executes a background write stream command quickly (ideal for smooth slider sweeps).
     */
    @Synchronized
    fun writeCommand(command: String): Boolean {
        if (process == null) {
            val ok = startPersistentShell()
            if (!ok) return false
        }
        val w = writer ?: return false
        return try {
            w.write(command + "\n")
            w.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write command: $command", e)
            close()
            false
        }
    }

    /**
     * Executes a command and waits for output utilizing a delimiter.
     */
    @Synchronized
    fun readCommandOutput(command: String): String? {
        if (process == null) {
            val ok = startPersistentShell()
            if (!ok) {
                return executeOneShot(command)
            }
        }
        val w = writer ?: return null
        val r = reader ?: return null
        val delimiter = "CMD_END_MARKER"
        return try {
            w.write("$command; echo '$delimiter'\n")
            w.flush()
            val sb = StringBuilder()
            var line: String?
            while (true) {
                line = r.readLine()
                if (line == null || line.trim() == delimiter) {
                    break
                }
                sb.append(line).append("\n")
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed read command output, trying fallback", e)
            close()
            executeOneShot(command)
        }
    }

    private fun executeOneShot(command: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val r = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (r.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            p.waitFor()
            sb.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "OneShot execution failed for: $command", e)
            null
        }
    }

    /**
     * Terminate active root shell.
     */
    @Synchronized
    fun close() {
        try {
            writer?.write("exit\n")
            writer?.flush()
            process?.destroy()
        } catch (e: Exception) {
            // ignore
        } finally {
            process = null
            writer = null
            reader = null
            Log.d(TAG, "Persistent root shell closed.")
        }
    }
}
