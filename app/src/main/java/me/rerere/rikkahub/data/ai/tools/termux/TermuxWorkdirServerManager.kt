package me.rerere.rikkahub.data.ai.tools.termux

import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val TAG = "TermuxWorkdirServer"

data class TermuxWorkdirServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 9090,
    val error: String? = null,
)

class TermuxWorkdirServerManager(
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val termuxCommandManager: TermuxCommandManager,
) {
    private val _state = MutableStateFlow(TermuxWorkdirServerState())
    val state: StateFlow<TermuxWorkdirServerState> = _state.asStateFlow()

    fun start(
        port: Int,
        workdir: String,
    ) {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, port = port, error = null)
        appScope.launch {
            val script = buildStartScript(port = port, workdir = workdir)
            val result = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", script),
                        workdir = TERMUX_HOME_PATH,
                        background = true,
                        timeoutMs = 10_000L,
                        label = "RikkaHub workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Start failed", e)
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = e.message ?: e.javaClass.name,
                )
                return@launch
            }

            if (result.exitCode == 0) {
                _state.value = TermuxWorkdirServerState(
                    isRunning = true,
                    isLoading = false,
                    port = port,
                    error = null,
                )
            } else {
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = formatError(result),
                )
            }
        }
    }

    fun stop() {
        if (_state.value.isLoading) return
        _state.value = _state.value.copy(isLoading = true, error = null)
        appScope.launch {
            val expectedPort = settingsStore.settingsFlow.value.termuxWorkdirServerPort
            val result = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", buildStopScript(expectedPort = expectedPort)),
                        workdir = TERMUX_HOME_PATH,
                        background = true,
                        timeoutMs = 30_000L,
                        label = "RikkaHub stop workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Stop failed", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message ?: e.javaClass.name)
                return@launch
            }

            if (result.exitCode == 0) {
                _state.value = _state.value.copy(isRunning = false, isLoading = false, error = null)
            } else {
                _state.value = _state.value.copy(isLoading = false, error = formatError(result))
            }
        }
    }

    fun restart(
        port: Int,
        workdir: String,
    ) {
        if (_state.value.isLoading) return
        val oldPort = _state.value.port
        _state.value = _state.value.copy(isLoading = true, port = port, error = null)
        appScope.launch {
            runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", buildStopScript(expectedPort = oldPort)),
                        workdir = TERMUX_HOME_PATH,
                        background = true,
                        timeoutMs = 30_000L,
                        label = "RikkaHub stop workdir http server",
                    )
                )
            }.onFailure { e ->
                Log.w(TAG, "Restart stop failed, continue", e)
            }
            val startResult = runCatching {
                termuxCommandManager.run(
                    TermuxRunCommandRequest(
                        commandPath = TERMUX_BASH_PATH,
                        arguments = listOf("-lc", buildStartScript(port = port, workdir = workdir)),
                        workdir = TERMUX_HOME_PATH,
                        background = true,
                        timeoutMs = 30_000L,
                        label = "RikkaHub workdir http server",
                    )
                )
            }.getOrElse { e ->
                Log.e(TAG, "Restart start failed", e)
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = e.message ?: e.javaClass.name,
                )
                return@launch
            }

            if (startResult.exitCode == 0) {
                _state.value = TermuxWorkdirServerState(
                    isRunning = true,
                    isLoading = false,
                    port = port,
                    error = null,
                )
            } else {
                _state.value = TermuxWorkdirServerState(
                    isRunning = false,
                    isLoading = false,
                    port = port,
                    error = formatError(startResult),
                )
            }
        }
    }

    private fun buildStartScript(port: Int, workdir: String): String {
        val safeWorkdir = workdir.replace("'", "'\"'\"'")
        return """
            set -e

            STATE_DIR="${'$'}HOME/.rikkahub"
            PID_FILE="${'$'}STATE_DIR/workdir_http_server.pid"
            PORT_FILE="${'$'}STATE_DIR/workdir_http_server.port"
            SERVE_DIR='$safeWorkdir'
            PORT=$port

            mkdir -p "${'$'}STATE_DIR"

            if ! command -v python3 >/dev/null 2>&1; then
              echo "python3 not found. In Termux: pkg install python"
              exit 127
            fi

            is_workdir_server_cmdline() {
              _cmdline="${'$'}1"
              _port="${'$'}2"
              _padded=" ${'$'}{_cmdline} "
              case "${'$'}_padded" in
                *" -m http.server ${'$'}_port "*"--bind 127.0.0.1 "*)
                  return 0
                  ;;
                *)
                  return 1
                  ;;
              esac
            }

            is_workdir_server_pid() {
              _pid="${'$'}1"
              _port="${'$'}2"
              [ -n "${'$'}_pid" ] || return 1
              [ -n "${'$'}_port" ] || return 1
              [ -r "/proc/${'$'}_pid/cmdline" ] || return 1
              _cmdline="${'$'}(cat "/proc/${'$'}_pid/cmdline" 2>/dev/null | tr '\0' ' ')"
              [ -n "${'$'}_cmdline" ] || return 1
              is_workdir_server_cmdline "${'$'}_cmdline" "${'$'}_port"
            }

            find_running_workdir_server_pid_by_port() {
              _port="${'$'}1"
              [ -n "${'$'}_port" ] || return 1
              for _p in /proc/[0-9]*; do
                _pid="${'$'}{_p#/proc/}"
                [ -r "${'$'}_p/cmdline" ] || continue
                _cmdline="${'$'}(cat "${'$'}_p/cmdline" 2>/dev/null | tr '\0' ' ')"
                [ -n "${'$'}_cmdline" ] || continue
                if is_workdir_server_cmdline "${'$'}_cmdline" "${'$'}_port"; then
                  echo "${'$'}_pid"
                  return 0
                fi
              done
              return 1
            }

            find_running_workdir_server_pid_by_ps() {
              _port="${'$'}1"
              [ -n "${'$'}_port" ] || return 1
              while read -r _user _pid _ppid _c _stime _tty _time _cmdline; do
                if [ "${'$'}_pid" = "PID" ]; then
                  continue
                fi
                [ -n "${'$'}_cmdline" ] || continue
                if is_workdir_server_cmdline " ${'$'}{_cmdline} " "${'$'}_port"; then
                  echo "${'$'}_pid"
                  return 0
                fi
              done < <(ps -ef 2>/dev/null)
              return 1
            }

            kill_pid() {
              _pid="${'$'}1"
              if [ -z "${'$'}_pid" ]; then
                return 0
              fi
              if ! kill -0 "${'$'}_pid" 2>/dev/null; then
                return 0
              fi
              kill "${'$'}_pid" 2>/dev/null || true
              sleep 0.2
              if kill -0 "${'$'}_pid" 2>/dev/null; then
                kill -9 "${'$'}_pid" 2>/dev/null || true
                sleep 0.2
              fi
              if kill -0 "${'$'}_pid" 2>/dev/null; then
                return 1
              fi
              return 0
            }

            if [ -f "${'$'}PID_FILE" ]; then
              OLD_PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
              OLD_PORT="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"
              if [ -n "${'$'}OLD_PID" ] && kill -0 "${'$'}OLD_PID" 2>/dev/null; then
                if [ -n "${'$'}OLD_PORT" ] && is_workdir_server_pid "${'$'}OLD_PID" "${'$'}OLD_PORT"; then
                  if [ "${'$'}OLD_PORT" = "${'$'}PORT" ]; then
                    echo "ALREADY_RUNNING ${'$'}OLD_PID ${'$'}OLD_PORT"
                    exit 0
                  fi
                  if ! kill_pid "${'$'}OLD_PID"; then
                    echo "FAILED_TO_STOP_OLD_PID ${'$'}OLD_PID ${'$'}OLD_PORT"
                    exit 1
                  fi
                else
                  echo "STALE_PID_FILE ${'$'}OLD_PID ${'$'}OLD_PORT"
                fi
              fi
              rm -f "${'$'}PID_FILE" "${'$'}PORT_FILE"
            fi

            EXISTING_PID="${'$'}(find_running_workdir_server_pid_by_port "${'$'}PORT" 2>/dev/null || true)"
            if [ -z "${'$'}EXISTING_PID" ]; then
              EXISTING_PID="${'$'}(find_running_workdir_server_pid_by_ps "${'$'}PORT" 2>/dev/null || true)"
            fi
            if [ -n "${'$'}EXISTING_PID" ]; then
              echo "${'$'}EXISTING_PID" > "${'$'}PID_FILE"
              echo "${'$'}PORT" > "${'$'}PORT_FILE"
              echo "ALREADY_RUNNING ${'$'}EXISTING_PID ${'$'}PORT"
              exit 0
            fi

            cd "${'$'}SERVE_DIR"
            nohup python3 -m http.server "${'$'}PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
            NEW_PID="${'$'}!"
            echo "${'$'}NEW_PID" > "${'$'}PID_FILE"
            echo "${'$'}PORT" > "${'$'}PORT_FILE"

            sleep 0.2
            if kill -0 "${'$'}NEW_PID" 2>/dev/null; then
              echo "STARTED ${'$'}NEW_PID ${'$'}PORT"
              exit 0
            fi

            echo "FAILED_TO_START"
            exit 1
        """.trimIndent()
    }

    private fun buildStopScript(expectedPort: Int): String {
        return """
            set -e

            STATE_DIR="${'$'}HOME/.rikkahub"
            PID_FILE="${'$'}STATE_DIR/workdir_http_server.pid"
            PORT_FILE="${'$'}STATE_DIR/workdir_http_server.port"
            EXPECTED_PORT=$expectedPort

            is_workdir_server_cmdline() {
              _cmdline="${'$'}1"
              _port="${'$'}2"
              _padded=" ${'$'}{_cmdline} "
              case "${'$'}_padded" in
                *" -m http.server ${'$'}_port "*"--bind 127.0.0.1 "*)
                  return 0
                  ;;
                *)
                  return 1
                  ;;
              esac
            }

            is_workdir_server_pid() {
              _pid="${'$'}1"
              _port="${'$'}2"
              [ -n "${'$'}_pid" ] || return 1
              [ -n "${'$'}_port" ] || return 1
              [ -r "/proc/${'$'}_pid/cmdline" ] || return 1
              _cmdline="${'$'}(cat "/proc/${'$'}_pid/cmdline" 2>/dev/null | tr '\0' ' ')"
              [ -n "${'$'}_cmdline" ] || return 1
              is_workdir_server_cmdline "${'$'}_cmdline" "${'$'}_port"
            }

            find_running_workdir_server_pids_by_port() {
              _port="${'$'}1"
              [ -n "${'$'}_port" ] || return 1
              for _p in /proc/[0-9]*; do
                _pid="${'$'}{_p#/proc/}"
                [ -r "${'$'}_p/cmdline" ] || continue
                _cmdline="${'$'}(cat "${'$'}_p/cmdline" 2>/dev/null | tr '\0' ' ')"
                [ -n "${'$'}_cmdline" ] || continue
                if is_workdir_server_cmdline "${'$'}_cmdline" "${'$'}_port"; then
                  echo "${'$'}_pid"
                fi
              done
            }

            find_running_workdir_server_pids_by_ps() {
              _port="${'$'}1"
              [ -n "${'$'}_port" ] || return 1
              ps -ef 2>/dev/null | while read -r _user _pid _ppid _c _stime _tty _time _cmdline; do
                if [ "${'$'}_pid" = "PID" ]; then
                  continue
                fi
                [ -n "${'$'}_cmdline" ] || continue
                if is_workdir_server_cmdline " ${'$'}{_cmdline} " "${'$'}_port"; then
                  echo "${'$'}_pid"
                fi
              done
            }

            kill_pid() {
              _pid="${'$'}1"
              if [ -z "${'$'}_pid" ]; then
                return 0
              fi
              if ! kill -0 "${'$'}_pid" 2>/dev/null; then
                return 0
              fi
              kill "${'$'}_pid" 2>/dev/null || true
              sleep 0.2
              if kill -0 "${'$'}_pid" 2>/dev/null; then
                kill -9 "${'$'}_pid" 2>/dev/null || true
                sleep 0.2
              fi
              if kill -0 "${'$'}_pid" 2>/dev/null; then
                return 1
              fi
              return 0
            }

            PID="${'$'}(cat \"${'$'}PID_FILE\" 2>/dev/null || true)"
            PORT="${'$'}(cat \"${'$'}PORT_FILE\" 2>/dev/null || true)"

            if [ -z "${'$'}PORT" ]; then
              PORT="${'$'}EXPECTED_PORT"
            fi

            PID_IS_SERVER=0
            if [ -n "${'$'}PID" ] && kill -0 "${'$'}PID" 2>/dev/null; then
              if [ -n "${'$'}PORT" ]; then
                if is_workdir_server_pid "${'$'}PID" "${'$'}PORT"; then
                  PID_IS_SERVER=1
                else
                  echo "STALE_PID_FILE ${'$'}PID ${'$'}PORT"
                  PID=""
                fi
              else
                CMDLINE="${'$'}(cat "/proc/${'$'}PID/cmdline" 2>/dev/null | tr '\0' ' ')"
                PADDED=" ${'$'}{CMDLINE} "
                if [ -n "${'$'}CMDLINE" ] && [[ "${'$'}PADDED" == *" -m http.server "*"--bind 127.0.0.1 "* ]]; then
                  PID_IS_SERVER=1
                  PREV2=""
                  PREV1=""
                  for ARG in ${'$'}CMDLINE; do
                    if [ "${'$'}PREV2" = "-m" ] && [ "${'$'}PREV1" = "http.server" ]; then
                      case "${'$'}ARG" in
                        *[!0-9]*)
                          ;;
                        *)
                          PORT="${'$'}ARG"
                          break
                          ;;
                      esac
                    fi
                    PREV2="${'$'}PREV1"
                    PREV1="${'$'}ARG"
                  done
                else
                  echo "STALE_PID_FILE ${'$'}PID"
                  PID=""
                fi
              fi
            fi

            if [ -n "${'$'}PID" ] && [ "${'$'}PID_IS_SERVER" -eq 1 ]; then
              if ! kill_pid "${'$'}PID"; then
                echo "FAILED_TO_STOP_PID ${'$'}PID"
                exit 1
              fi
            fi

            STOPPED_PIDS=""
            if [ -n "${'$'}PORT" ]; then
              for _pid in ${'$'}(find_running_workdir_server_pids_by_port "${'$'}PORT" 2>/dev/null || true); do
                if ! kill_pid "${'$'}_pid"; then
                  echo "FAILED_TO_STOP_PID ${'$'}_pid"
                  exit 1
                fi
                STOPPED_PIDS="${'$'}STOPPED_PIDS ${'$'}_pid"
              done
              for _pid in ${'$'}(find_running_workdir_server_pids_by_ps "${'$'}PORT" 2>/dev/null || true); do
                if ! kill_pid "${'$'}_pid"; then
                  echo "FAILED_TO_STOP_PID ${'$'}_pid"
                  exit 1
                fi
                STOPPED_PIDS="${'$'}STOPPED_PIDS ${'$'}_pid"
              done

              REMAINING_PID=""
              for _pid in ${'$'}(find_running_workdir_server_pids_by_port "${'$'}PORT" 2>/dev/null || true); do
                REMAINING_PID="${'$'}_pid"
                break
              done
              if [ -z "${'$'}REMAINING_PID" ]; then
                for _pid in ${'$'}(find_running_workdir_server_pids_by_ps "${'$'}PORT" 2>/dev/null || true); do
                  REMAINING_PID="${'$'}_pid"
                  break
                done
              fi
              if [ -n "${'$'}REMAINING_PID" ]; then
                echo "FAILED_TO_STOP_PORT ${'$'}PORT PID ${'$'}REMAINING_PID"
                exit 1
              fi
            fi

            rm -f "${'$'}PID_FILE" "${'$'}PORT_FILE"
            echo "STOPPED ${'$'}{STOPPED_PIDS# }"
            exit 0
        """.trimIndent()
    }

    private fun formatError(result: TermuxResult): String {
        return buildString {
            val stderr = result.stderr.trim()
            val stdout = result.stdout.trim()
            val errMsg = result.errMsg?.trim()
            if (stderr.isNotBlank()) append(stderr)
            if (errMsg.isNullOrBlank().not()) {
                if (isNotEmpty()) append('\n')
                append(errMsg)
            }
            if (stdout.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stdout)
            }
            if (isEmpty()) append("Exit code: ${result.exitCode}")
        }
    }

    companion object {
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
    }
}
