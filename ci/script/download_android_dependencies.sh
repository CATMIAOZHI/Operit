#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <jvm|full> <destination-directory>" >&2
  exit 2
fi

profile="$1"
destination="$2"
case "$profile" in
  jvm|full) ;;
  *)
    echo "unsupported Android dependency profile: $profile" >&2
    exit 2
    ;;
esac

rm -rf "$destination"
mkdir -p "$destination"

download() {
  local file_name="$1"
  local expected_sha="$2"
  curl --fail --location --retry 3 --connect-timeout 30 --max-time 300 \
    "https://github.com/CATMIAOZHI/Operit/releases/download/deps-v1/$file_name" \
    --output "$destination/$file_name"
  test -s "$destination/$file_name"
  printf '%s  %s\n' "$expected_sha" "$destination/$file_name" | sha256sum --check
}

download "libs.zip" "a9cf963c2bfac4ddaa0e47f66c0adc0fa9084e71c2733d3f8dd84a33957b92d2"

if [[ "$profile" == "full" ]]; then
  download "models.zip" "8645d7d56266518e284e672ef9c017013238bec6d057abc1cd0b74686c1efbb8"
  download "jniLibs.zip" "8b973cc604041d6a6801a2040bab8e4f4faf7c7041cba16b6524100a866e99b3"
fi
