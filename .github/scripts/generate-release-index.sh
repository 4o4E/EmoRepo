#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 8 ]]; then
  echo "用法: generate-release-index.sh <versionName> <versionCode> <tag> <commit> <publishedAt> <owner/repo> <签名证书SHA-256> <产物目录>" >&2
  exit 2
fi

version_name="$1"
version_code="$2"
tag="$3"
commit="$4"
published_at="$5"
repository="$6"
signing_certificate_sha256="$7"
output_dir="$8"
artifacts='[]'

[[ "$version_code" =~ ^[0-9]+$ ]] || { echo "versionCode 必须是正整数" >&2; exit 1; }
[[ "$signing_certificate_sha256" =~ ^[0-9a-f]{64}$ ]] || {
  echo "签名证书 SHA-256 必须是 64 位小写十六进制" >&2
  exit 1
}

abis=(universal arm64-v8a armeabi-v7a x86 x86_64)
for abi in "${abis[@]}"; do
  file_name="EmoRepo-${version_name}-${abi}.apk"
  file_path="$output_dir/$file_name"
  [[ -f "$file_path" ]] || { echo "缺少发布产物: $file_path" >&2; exit 1; }
  size="$(stat -c '%s' "$file_path")"
  sha256="$(sha256sum "$file_path" | awk '{print $1}')"
  download_url="https://github.com/${repository}/releases/download/${tag}/${file_name}"
  artifacts="$(jq \
    --arg abi "$abi" \
    --arg fileName "$file_name" \
    --argjson size "$size" \
    --arg sha256 "$sha256" \
    --arg downloadUrl "$download_url" \
    '. + [{abi: $abi, fileName: $fileName, size: $size, sha256: $sha256, downloadUrl: $downloadUrl}]' \
    <<<"$artifacts")"
done

jq -n \
  --argjson schemaVersion 1 \
  --arg applicationId "top.e404.emorepo" \
  --arg channel "release" \
  --arg tag "$tag" \
  --arg versionName "$version_name" \
  --argjson versionCode "$version_code" \
  --argjson minimumSdk 24 \
  --arg commit "$commit" \
  --arg publishedAt "$published_at" \
  --arg releaseUrl "https://github.com/${repository}/releases/tag/${tag}" \
  --arg signingCertificateSha256 "$signing_certificate_sha256" \
  --argjson artifacts "$artifacts" \
  '{
    schemaVersion: $schemaVersion,
    applicationId: $applicationId,
    channel: $channel,
    tag: $tag,
    versionName: $versionName,
    versionCode: $versionCode,
    minimumSdk: $minimumSdk,
    commit: $commit,
    publishedAt: $publishedAt,
    releaseUrl: $releaseUrl,
    signingCertificateSha256: $signingCertificateSha256,
    artifacts: $artifacts
  }' > "$output_dir/release-index.json"

jq empty "$output_dir/release-index.json"
