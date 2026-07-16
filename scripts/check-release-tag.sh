#!/usr/bin/env bash
set -euo pipefail

requested_tag="${1:-}"
build_file="${2:-app/build.gradle.kts}"

if [[ -z "$requested_tag" ]]; then
  echo "Usage: check-release-tag.sh <tag|--metadata> [build file]" >&2
  exit 2
fi
if [[ ! -f "$build_file" ]]; then
  echo "Build file not found: $build_file" >&2
  exit 2
fi

mapfile -t version_names < <(
  sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$build_file"
)
mapfile -t version_codes < <(
  sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$build_file"
)

if (( ${#version_names[@]} != 1 )); then
  echo "Expected one versionName in $build_file, found ${#version_names[@]}." >&2
  exit 2
fi
if (( ${#version_codes[@]} != 1 )); then
  echo "Expected one versionCode in $build_file, found ${#version_codes[@]}." >&2
  exit 2
fi

version_name="${version_names[0]}"
version_code="${version_codes[0]}"
if [[ ! "$version_name" =~ ^[0-9]+(\.[0-9]+){1,2}([.-][0-9A-Za-z]+)*$ ]]; then
  echo "versionName is not a supported release version: $version_name" >&2
  exit 2
fi
if [[ ! "$version_code" =~ ^[0-9]+$ ]] || (( version_code < 1 )); then
  echo "versionCode must be a positive integer: $version_code" >&2
  exit 2
fi

expected_tag="v${version_name}"
if [[ "$requested_tag" != "--metadata" && "$requested_tag" != "$expected_tag" ]]; then
  echo "Tag $requested_tag does not match versionName $version_name. Expected $expected_tag." >&2
  exit 1
fi

printf 'version_name=%s\n' "$version_name"
printf 'version_code=%s\n' "$version_code"
printf 'expected_tag=%s\n' "$expected_tag"
