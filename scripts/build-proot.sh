#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/build/proot-android}"
OUTPUT_DIR="${OUTPUT_DIR:-$ROOT_DIR/sandbox/src/main/jniLibs}"
PROOT_GIT_URL="${PROOT_GIT_URL:-https://github.com/termux/proot.git}"
PROOT_REF="${PROOT_REF:-master}"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
ABI_LIST="${ABI_LIST:-arm64-v8a x86_64}"
TALLOC_SOURCE_URL="${TALLOC_SOURCE_URL:-https://download.samba.org/pub/talloc/talloc-${TALLOC_VERSION}.tar.gz}"

log() {
  printf '[build-proot] %s\n' "$*"
}

fail() {
  printf '[build-proot] error: %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1"
}

detect_ndk() {
  if [[ -n "${NDK:-}" ]]; then
    printf '%s\n' "$NDK"
    return
  fi

  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    printf '%s\n' "$ANDROID_NDK_HOME"
    return
  fi

  local sdk_root=""
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_root="$ANDROID_SDK_ROOT"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_root="$ANDROID_HOME"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    sdk_root="$HOME/Library/Android/sdk"
  elif [[ -d "$HOME/Android/Sdk" ]]; then
    sdk_root="$HOME/Android/Sdk"
  fi

  if [[ -n "$sdk_root" && -d "$sdk_root/ndk" ]]; then
    local latest_ndk
    latest_ndk="$(find "$sdk_root/ndk" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
    if [[ -n "$latest_ndk" ]]; then
      printf '%s\n' "$latest_ndk"
      return
    fi
  fi

  fail "找不到 Android NDK。请设置 NDK 或 ANDROID_NDK_HOME。"
}

detect_toolchain_prebuilt() {
  local ndk_dir="$1"
  local prebuilt_root="$ndk_dir/toolchains/llvm/prebuilt"
  [[ -d "$prebuilt_root" ]] || fail "NDK 缺少 llvm toolchain: $prebuilt_root"

  local first_dir
  first_dir="$(find "$prebuilt_root" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
  [[ -n "$first_dir" ]] || fail "找不到 NDK prebuilt toolchain 目录"
  printf '%s\n' "$first_dir"
}

download_and_extract() {
  local url="$1"
  local archive_path="$2"
  local dest_dir="$3"
  local marker_dir="$4"

  if [[ -d "$marker_dir" ]]; then
    return
  fi

  mkdir -p "$dest_dir" "$(dirname "$archive_path")"
  if [[ ! -f "$archive_path" ]]; then
    log "下载 $(basename "$archive_path")"
    curl -L --fail --retry 3 "$url" -o "$archive_path"
  fi

  log "解压 $(basename "$archive_path")"
  tar -xzf "$archive_path" -C "$dest_dir"
}

sync_git_repo() {
  local repo_url="$1"
  local repo_ref="$2"
  local repo_dir="$3"

  if [[ ! -d "$repo_dir/.git" ]]; then
    log "克隆 $(basename "$repo_dir")"
    git clone "$repo_url" "$repo_dir"
  fi

  pushd "$repo_dir" >/dev/null
  log "同步 $(basename "$repo_dir")@$repo_ref"
  git fetch --all --tags --prune
  git checkout "$repo_ref"
  popd >/dev/null
}

apply_proot_android_patches() {
  local proot_src="$1"
  local ashmem_memfd_src="$proot_src/src/extension/ashmem_memfd/ashmem_memfd.c"
  local gnumakefile="$proot_src/src/GNUmakefile"

  [[ -f "$ashmem_memfd_src" ]] || fail "找不到 PRoot 源文件: $ashmem_memfd_src"
  [[ -f "$gnumakefile" ]] || fail "找不到 PRoot 构建文件: $gnumakefile"

  if ! grep -q '^#include <string\.h>' "$ashmem_memfd_src"; then
    log "应用 Android 兼容补丁: ashmem_memfd.c"

    local tmp_file
    tmp_file="$(mktemp)"

    awk '
      BEGIN { inserted = 0 }
      /^#include <stdlib\.h>/ {
        print
        print "#include <string.h>    /* strcmp(3), memset(3), */"
        inserted = 1
        next
      }
      { print }
      END {
        if (!inserted) {
          exit 1
        }
      }
    ' "$ashmem_memfd_src" >"$tmp_file" || {
      rm -f "$tmp_file"
      fail "无法为 $ashmem_memfd_src 注入 string.h"
    }

    mv "$tmp_file" "$ashmem_memfd_src"
  fi

  if ! grep -q '^READELF  ?= readelf' "$gnumakefile"; then
    log "应用 Android 兼容补丁: GNUmakefile readelf/check flags"

    local tmp_file
    tmp_file="$(mktemp)"

    awk '
      BEGIN {
        inserted_readelf = 0
        inserted_check_compile = 0
      }
      /^OBJDUMP  \?= \$\(CROSS_COMPILE\)objdump$/ {
        print
        print "READELF  ?= readelf"
        inserted_readelf = 1
        next
      }
      /^CHECK_RESULTS  = \$\(foreach feature,\$\(CHECK_FEATURES\),\.check_\$\(feature\)\.res\)$/ {
        print
        print "CHECK_CFLAGS  = $(filter-out -Werror=implicit-function-declaration,$(CFLAGS)) -Wno-error=implicit-function-declaration"
        next
      }
      /^CHECK_FEATURES = process_vm seccomp_filter$/ {
        print "CHECK_FEATURES = seccomp_filter"
        next
      }
      /^\.check_%\.o: \.check_%\.c$/ {
        print
        getline
        print "\t-$(CC) $(CPPFLAGS) $(CHECK_CFLAGS) -MD -c $(SRC)$< -o $@ $(silently)"
        inserted_check_compile = 1
        next
      }
      /^loader\/loader-info\.c: loader\/loader$/ {
        print
        getline
        print "\t$(READELF) -s $< | awk -f loader/loader-info.awk > $@"
        next
      }
      { print }
      END {
        if (!inserted_readelf || !inserted_check_compile) {
          exit 1
        }
      }
    ' "$gnumakefile" >"$tmp_file" || {
      rm -f "$tmp_file"
      fail "无法为 $gnumakefile 应用构建兼容补丁"
    }

    mv "$tmp_file" "$gnumakefile"
  fi
}

configure_arch() {
  local abi="$1"

  case "$abi" in
    arm64-v8a)
      TARGET_TRIPLE="aarch64-linux-android"
      TARGET_ARCH="aarch64"
      TARGET_API=21
      ;;
    x86_64)
      TARGET_TRIPLE="x86_64-linux-android"
      TARGET_ARCH="x86_64"
      TARGET_API=21
      ;;
    armeabi-v7a)
      TARGET_TRIPLE="armv7a-linux-androideabi"
      TARGET_ARCH="armv7a"
      TARGET_API=16
      ;;
    x86)
      TARGET_TRIPLE="i686-linux-android"
      TARGET_ARCH="i686"
      TARGET_API=16
      ;;
    *)
      fail "不支持的 ABI: $abi"
      ;;
  esac
}

make_getconf_mock() {
  local mock_dir="$1"
  mkdir -p "$mock_dir"
  cat >"$mock_dir/getconf" <<'EOF'
#!/usr/bin/env bash
case "${1:-}" in
  LFS_CFLAGS|LFS64_CFLAGS|XBS5_LP64_OFF64_CFLAGS)
    exit 0
    ;;
esac
exit 1
EOF
  chmod +x "$mock_dir/getconf"
}

build_talloc_static() {
  local abi="$1"
  local toolchain="$2"
  local talloc_src="$3"
  local build_root="$4"

  configure_arch "$abi"

  local install_root="$build_root/root-$abi/root"
  local static_root="$build_root/static-$abi/root"
  local cross_answers="$build_root/cross-answers-$abi.txt"
  local mock_dir="$build_root/target-mock-bin-$abi"
  local talloc_object="$build_root/talloc-$abi.o"

  mkdir -p "$install_root" "$static_root/include" "$static_root/lib"
  make_getconf_mock "$mock_dir"

  export AR="$toolchain/bin/llvm-ar"
  export AS="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang"
  export CC="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang"
  export CXX="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang++"
  export LD="$toolchain/bin/ld"
  export RANLIB="$toolchain/bin/llvm-ranlib"
  export STRIP="$toolchain/bin/llvm-strip"
  export OBJCOPY="$toolchain/bin/llvm-objcopy"
  export OBJDUMP="$toolchain/bin/llvm-objdump"

  cat >"$cross_answers" <<EOF
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF

  pushd "$talloc_src" >/dev/null
  make distclean >/dev/null 2>&1 || true
  PATH="$mock_dir:$PATH" ./configure \
    "--prefix=$install_root" \
    --disable-rpath \
    --disable-python \
    --cross-compile \
    --cross-answers="$cross_answers"

  "$CC" \
    ${CFLAGS:-} \
    -D__STDC_WANT_LIB_EXT1__=1 \
    -I"$talloc_src/bin/default" \
    -I"$talloc_src" \
    -I"$talloc_src/lib/replace" \
    -c "$talloc_src/talloc.c" \
    -o "$talloc_object"

  "$AR" rcs "$static_root/lib/libtalloc.a" "$talloc_object"
  cp -f talloc.h "$static_root/include/"
  popd >/dev/null
}

build_proot_for_apk() {
  local abi="$1"
  local toolchain="$2"
  local proot_src="$3"
  local build_root="$4"
  local output_dir="$5"

  configure_arch "$abi"

  local install_root="$build_root/root-$abi/root"
  local static_root="$build_root/static-$abi/root"
  local install_apk_root="${install_root}-apk"
  local abi_out_dir="$output_dir/$abi"

  mkdir -p "$install_apk_root/bin" "$abi_out_dir"

  export AR="$toolchain/bin/llvm-ar"
  export AS="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang"
  export CC="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang"
  export CXX="$toolchain/bin/${TARGET_TRIPLE}${TARGET_API}-clang++"
  export LD="$toolchain/bin/ld"
  export RANLIB="$toolchain/bin/llvm-ranlib"
  export STRIP="$toolchain/bin/llvm-strip"
  export OBJCOPY="$toolchain/bin/llvm-objcopy"
  export OBJDUMP="$toolchain/bin/llvm-objdump"
  export READELF="$toolchain/bin/llvm-readelf"
  export CFLAGS="-I$static_root/include -Werror=implicit-function-declaration"
  export LDFLAGS="-L$static_root/lib"
  export PROOT_UNBUNDLE_LOADER="."
  export PROOT_UNBUNDLE_LOADER_NAME="libproot-loader.so"
  export PROOT_UNBUNDLE_LOADER_NAME_32="libproot-loader32.so"

  pushd "$proot_src/src" >/dev/null
  make distclean >/dev/null 2>&1 || true
  make V=1 proot
  cp -f ./proot "$install_apk_root/bin/proot"
  cp -f ./loader/loader "$install_apk_root/bin/loader"
  if [[ -f ./loader/loader-m32 ]]; then
    cp -f ./loader/loader-m32 "$install_apk_root/bin/loader32"
  fi

  make distclean >/dev/null 2>&1 || true
  CFLAGS="$CFLAGS -DUSERLAND" make V=1 proot
  cp -f ./proot "$install_apk_root/bin/proot-userland"

  local file_name
  pushd "$install_apk_root/bin" >/dev/null
  for file_name in *; do
    [[ -f "$file_name" ]] || continue
    "$STRIP" "$file_name"
    case "$file_name" in
      lib*.so) ;;
      loader) mv -f "$file_name" "$PROOT_UNBUNDLE_LOADER_NAME" ;;
      loader32) mv -f "$file_name" "$PROOT_UNBUNDLE_LOADER_NAME_32" ;;
      *) mv -f "$file_name" "lib${file_name}.so" ;;
    esac
  done
  popd >/dev/null

  find "$abi_out_dir" -maxdepth 1 -type f -name 'libproot*.so' -delete
  cp -f "$install_apk_root/bin/"* "$abi_out_dir/"
  popd >/dev/null
}

main() {
  require_cmd curl
  require_cmd tar
  require_cmd make
  require_cmd find
  require_cmd git

  local ndk_dir
  ndk_dir="$(detect_ndk)"
  [[ -d "$ndk_dir" ]] || fail "NDK 目录不存在: $ndk_dir"

  local toolchain
  toolchain="$(detect_toolchain_prebuilt "$ndk_dir")"

  log "使用 NDK: $ndk_dir"
  log "使用 toolchain: $toolchain"
  log "构建 ABI: $ABI_LIST"

  mkdir -p "$BUILD_DIR" "$OUTPUT_DIR"

  local archive_dir="$BUILD_DIR/archives"
  local src_dir="$BUILD_DIR/src"
  local talloc_archive="$archive_dir/talloc-$TALLOC_VERSION.tar.gz"
  local proot_src="$src_dir/proot"
  local talloc_src="$src_dir/talloc-$TALLOC_VERSION"

  sync_git_repo "$PROOT_GIT_URL" "$PROOT_REF" "$proot_src"
  apply_proot_android_patches "$proot_src"
  download_and_extract "$TALLOC_SOURCE_URL" "$talloc_archive" "$src_dir" "$talloc_src"

  local abi
  for abi in $ABI_LIST; do
    log "开始构建 $abi"
    build_talloc_static "$abi" "$toolchain" "$talloc_src" "$BUILD_DIR"
    build_proot_for_apk "$abi" "$toolchain" "$proot_src" "$BUILD_DIR" "$OUTPUT_DIR"
    log "$abi 构建完成，产物输出到 $OUTPUT_DIR/$abi"
  done

  log "全部完成"
}

main "$@"
