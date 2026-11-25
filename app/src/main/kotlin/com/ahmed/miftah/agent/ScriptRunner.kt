package com.ahmed.miftah.agent

import com.ahmed.miftah.skills.SkillScripts
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Outcome of one script run, already output-truncated. */
data class ScriptRunResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val error: String? = null
)

/**
 * The narrow seam between the app and the Python runtime. The production
 * implementation is Chaquopy (see ChaquoScriptRunner.kt); JVM tests inject
 * a fake. [run] returns the runner module's raw JSON string:
 * {"code": int, "stdout": str, "stderr": str}.
 */
fun interface PythonBridge {
    fun run(scriptPath: String, args: List<String>, workingDir: String): String
}

/** Pure parsing/formatting for runner output — JVM-testable. */
object ScriptRunCore {
    const val TIMEOUT_SECONDS = 30L

    fun parse(rawJson: String): ScriptRunResult {
        val o = try { JSONObject(rawJson) } catch (e: Exception) {
            return ScriptRunResult(null, "", "", timedOut = false, error = "unparseable runner output")
        }
        return ScriptRunResult(
            exitCode = if (o.has("code")) o.optInt("code") else null,
            stdout = SkillScripts.truncate(o.optString("stdout")),
            stderr = SkillScripts.truncate(o.optString("stderr")),
            timedOut = false
        )
    }

    /** The exact text fed back to the model after a run. */
    fun formatForModel(result: ScriptRunResult): String = buildString {
        when {
            result.timedOut ->
                append("Error: script timed out after ${ScriptRunner.TIMEOUT_SECONDS}s and was abandoned.")
            result.error != null ->
                append("Error: script could not run: ").append(result.error)
            else -> {
                append("exit code: ").append(result.exitCode ?: "?")
                if (result.stdout.isNotBlank()) append("\n--- stdout ---\n").append(result.stdout)
                if (result.stderr.isNotBlank()) append("\n--- stderr ---\n").append(result.stderr)
                if (result.stdout.isBlank() && result.stderr.isBlank()) append("\n(no output)")
            }
        }
    }
}

/**
 * Runs scripts through a [PythonBridge] with a hard timeout. Each run gets
 * a fresh single-thread executor: a timed-out run is ABANDONED (its thread
 * is a daemon and is never joined), so one runaway script cannot block
 * later runs. Honest limit: an abandoned CPython call keeps executing in
 * the background until it finishes on its own — Chaquopy offers no safe
 * way to kill an in-flight Python call from another thread.
 */
class ScriptRunner(
    private val bridge: PythonBridge,
    private val timeoutSeconds: Long = ScriptRunCore.TIMEOUT_SECONDS
) {
    fun run(script: File, args: List<String>, workingDir: File): ScriptRunResult {
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "miftah-python").apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<ScriptRunResult> {
                ScriptRunCore.parse(
                    bridge.run(script.absolutePath, args, workingDir.absolutePath)
                )
            }
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                ScriptRunResult(null, "", "", timedOut = true)
            }
        } catch (e: Exception) {
            val cause = if (e is java.util.concurrent.ExecutionException) e.cause ?: e else e
            ScriptRunResult(null, "", "", timedOut = false, error = cause.message ?: "runner failure")
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        const val TIMEOUT_SECONDS = ScriptRunCore.TIMEOUT_SECONDS
    }
}
