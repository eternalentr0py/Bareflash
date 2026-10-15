#!/usr/bin/env bash
# Creates a PRIVATE GitHub repo from this folder and pushes it.
# Needs the GitHub CLI, logged in once with:  gh auth login
set -euo pipefail
NAME="${1:-bareflash}"
cd "$(dirname "$0")/.."
command -v gh >/dev/null || { echo "Install the GitHub CLI first: https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || gh auth login
[ -d .git ] || { git init -q -b main && git add -A && git commit -q -m "Initial commit"; }
gh repo create "$NAME" --private --source . --remote origin --push
echo "Done. Open the Actions tab and run 'Build & Release'."
