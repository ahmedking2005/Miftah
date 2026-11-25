package com.ahmed.miftah.agent

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray

/**
 * Production [PythonBridge] backed by Chaquopy. Starts the interpreter
 * lazily on first use (no startup cost for users who never run scripts)
 * and delegates to the bundled `miftah_runner` Python module, which owns
 * stream capture, argv/cwd setup and environment scrubbing.
 *
 * No Android objects are handed to Python: the bridge passes only strings
 * (script path, JSON argv, working dir) and gets a JSON string back, so
 * skill scripts get stdio and the filesystem sandbox — nothing else.
 */
class ChaquoPythonBridge(context: Context) : PythonBridge {
    private val appContext: Context = context.applicationContext

    override fun run(scriptPath: String, args: List<String>, workingDir: String): String {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(appContext))
        }
        val module = Python.getInstance().getModule("miftah_runner")
        val argsJson = JSONArray(args).toString()
        return module.callAttr("run_script", scriptPath, argsJson, workingDir).toString()
    }
}
