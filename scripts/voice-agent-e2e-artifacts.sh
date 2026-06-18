#!/usr/bin/env bash

safe_voice_trace_id() {
  local trace_id="$1"
  [[ "$trace_id" =~ ^[A-Za-z0-9._-]+$ ]] &&
    [[ "$trace_id" != "." ]] &&
    [[ "$trace_id" != ".." ]] &&
    [[ "$trace_id" != "latest-trace-id.txt" ]]
}

resolve_app_artifact_dir() {
  local temp_path
  local trace_id
  temp_path="$(mktemp "$LOG_DIR/latest-trace-id.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  if adb_exec_out_to_file "$temp_path" run-as "$PACKAGE" cat "$APP_LATEST_TRACE_ID_PATH"; then
    trace_id="$(tr -d '\r\n' < "$temp_path")"
    rm -f "$temp_path"
    if [[ -n "$trace_id" ]] && safe_voice_trace_id "$trace_id"; then
      printf '%s/%s' "$APP_ARTIFACT_BASE_DIR" "$trace_id"
      return 0
    fi
  else
    rm -f "$temp_path"
  fi
  printf '%s' "$APP_ARTIFACT_BASE_DIR"
}

app_artifact_path() {
  local artifact_dir="$1"
  local artifact_name="$2"
  printf '%s/%s' "$artifact_dir" "$artifact_name"
}

pull_optional_app_artifact() {
  local artifact_dir="$1"
  local artifact_name="$2"
  local local_path="$3"
  local app_path
  app_path="$(app_artifact_path "$artifact_dir" "$artifact_name")"
  umask 077
  mkdir -p "$(dirname "$local_path")"
  local temp_path
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  if adb_exec_out_to_file "$temp_path" run-as "$PACKAGE" cat "$app_path" &&
    [[ -s "$temp_path" ]]; then
    mv -f "$temp_path" "$local_path"
    chmod 600 "$local_path"
    return 0
  fi
  rm -f "$temp_path"
  temp_path="$(mktemp "$LOG_DIR/report-artifact.XXXXXX")"
  register_report_temp_file "$temp_path"
  chmod 600 "$temp_path"
  printf 'missing' > "$temp_path"
  mv -f "$temp_path" "$local_path"
  chmod 600 "$local_path"
}

clear_app_artifact_files() {
  local artifact_names=("$@")
  local artifact_name
  local trace_dir
  trace_dir="$(resolve_app_artifact_dir)"
  if [[ "$trace_dir" != "$APP_ARTIFACT_BASE_DIR" ]]; then
    for artifact_name in "${artifact_names[@]}"; do
      adb_cmd shell "run-as $PACKAGE rm -f $trace_dir/$artifact_name" >/dev/null 2>&1 || true
    done
  fi
  for artifact_name in "${artifact_names[@]}"; do
    adb_cmd shell "run-as $PACKAGE rm -f $APP_ARTIFACT_BASE_DIR/$artifact_name" >/dev/null 2>&1 || true
  done
  adb_cmd shell "run-as $PACKAGE rm -f $APP_LATEST_TRACE_ID_PATH" >/dev/null 2>&1 || true
}
