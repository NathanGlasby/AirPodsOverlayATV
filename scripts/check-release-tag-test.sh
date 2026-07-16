#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd -- "$script_dir/.." && pwd)
checker="$script_dir/check-release-tag.sh"
build_file="$repo_root/app/build.gradle.kts"

metadata=$(bash "$checker" --metadata "$build_file")
expected_tag=$(printf '%s\n' "$metadata" | awk -F= '$1 == "expected_tag" { print $2 }')
version_name=$(printf '%s\n' "$metadata" | awk -F= '$1 == "version_name" { print $2 }')
version_code=$(printf '%s\n' "$metadata" | awk -F= '$1 == "version_code" { print $2 }')

if [[ -z "$expected_tag" || -z "$version_name" || -z "$version_code" ]]; then
  echo "The metadata output is incomplete." >&2
  exit 1
fi

bash "$checker" "$expected_tag" "$build_file" >/dev/null

if bash "$checker" "${expected_tag}-wrong" "$build_file" >/dev/null 2>&1; then
  echo "A mismatched tag was accepted." >&2
  exit 1
fi

if bash "$checker" "$expected_tag" "$repo_root/app/missing.gradle.kts" >/dev/null 2>&1; then
  echo "A missing build file was accepted." >&2
  exit 1
fi

echo "Release tag checks passed for $expected_tag and versionCode $version_code."
