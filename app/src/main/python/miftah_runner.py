"""Miftah skill-script runner (bundled with the app, not user code).

Runs one skill script with captured stdout/stderr and returns a JSON
string: {"code": int, "stdout": str, "stderr": str}.

Execution rules enforced here:
- Working directory is the skill's sandbox dir; sys.argv[0] is the script.
- The environment is scrubbed of anything that looks like a credential
  before user code runs (defense in depth: the app never puts provider
  keys or service credentials in the process environment anyway, and no
  Java/Android bridge objects are passed to the script — stdio only).
- SystemExit is honored as the exit code; any other exception prints a
  traceback to stderr and exits 1.
"""

import io
import json
import os
import runpy
import sys
import traceback

_SCRUB_MARKERS = ("API_KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL")


def _scrub_environment():
    for key in list(os.environ.keys()):
        upper = key.upper()
        if any(marker in upper for marker in _SCRUB_MARKERS):
            del os.environ[key]


def run_script(script_path, args_json, working_dir):
    try:
        args = json.loads(args_json) if args_json else []
    except Exception:
        args = []
    if not isinstance(args, list):
        args = [str(args)]
    args = [str(a) for a in args]

    _scrub_environment()

    out = io.StringIO()
    err = io.StringIO()
    old_stdout, old_stderr = sys.stdout, sys.stderr
    old_argv = sys.argv
    old_cwd = os.getcwd()
    old_path = list(sys.path)
    code = 0
    try:
        os.chdir(working_dir)
        if working_dir not in sys.path:
            sys.path.insert(0, working_dir)
        sys.argv = [script_path] + args
        sys.stdout = out
        sys.stderr = err
        runpy.run_path(script_path, run_name="__main__")
    except SystemExit as e:
        if e.code is None:
            code = 0
        elif isinstance(e.code, int):
            code = e.code
        else:
            print(e.code, file=err)
            code = 1
    except BaseException:
        traceback.print_exc(file=err)
        code = 1
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        sys.argv = old_argv
        sys.path[:] = old_path
        try:
            os.chdir(old_cwd)
        except Exception:
            pass
    return json.dumps({"code": code, "stdout": out.getvalue(), "stderr": err.getvalue()})
