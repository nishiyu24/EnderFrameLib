# Enable the repository git hooks (.githooks/pre-push). Run once per clone.
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
$root = Split-Path -Parent $PSScriptRoot
git -C $root config core.hooksPath .githooks
Write-Output "core.hooksPath = $(git -C $root config core.hooksPath)"
Write-Output "pre-push runs: gradlew build -x test.  Add EFL_PREPUSH_LINUX=1 to also run the Linux CI steps via WSL."
