#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "用法: collect-apks.sh <debug|release> <versionName> <输出目录>" >&2
  exit 2
fi

build_type="$1"
version_name="$2"
output_dir="$3"
input_dir="app/build/outputs/apk/${build_type}"

[[ -d "$input_dir" ]] || { echo "APK 输出目录不存在: $input_dir" >&2; exit 1; }
rm -rf "$output_dir"
mkdir -p "$output_dir"

abis=(universal arm64-v8a armeabi-v7a x86 x86_64)
for abi in "${abis[@]}"; do
  mapfile -t candidates < <(find "$input_dir" -maxdepth 1 -type f \
    -name "*-${abi}-${build_type}.apk" -print | sort)
  if [[ ${#candidates[@]} -ne 1 ]]; then
    echo "${abi} 应有且仅有一个 APK，实际为 ${#candidates[@]}" >&2
    find "$input_dir" -maxdepth 1 -type f -name '*.apk' -print >&2
    exit 1
  fi
  cp "${candidates[0]}" "$output_dir/EmoRepo-${version_name}-${abi}.apk"
done

(
  cd "$output_dir"
  sha256sum EmoRepo-*.apk > SHA256SUMS
)
