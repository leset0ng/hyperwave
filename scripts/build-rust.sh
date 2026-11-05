#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/30.0.15729638}"
case "$(uname -s)" in
    Darwin) NDK_HOST=darwin-x86_64 ;;
    Linux) NDK_HOST=linux-x86_64 ;;
    *) echo "Set up an Android NDK host toolchain for this OS." >&2; exit 1 ;;
esac
LINKER="$NDK_DIR/toolchains/llvm/prebuilt/$NDK_HOST/bin/aarch64-linux-android33-clang"
if [[ ! -x "$LINKER" ]]; then echo "Android NDK linker missing: $LINKER" >&2; exit 1; fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$LINKER"
cargo build --locked --release --target aarch64-linux-android -p hyperwave-dsp
mkdir -p app/src/main/jniLibs/arm64-v8a
cp target/aarch64-linux-android/release/libhyperwave_dsp.so app/src/main/jniLibs/arm64-v8a/
