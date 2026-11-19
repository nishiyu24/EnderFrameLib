# Run the CI steps (.github/workflows/build.yml) on Linux via WSL, from Windows.
# See scripts/ci-linux.sh for what is reproduced and why.
#
#   powershell -File scripts/ci-linux.ps1            # default WSL distro (Ubuntu)
#   powershell -File scripts/ci-linux.ps1 -Distro Ubuntu
#   powershell -File scripts/ci-linux.ps1 -Full      # also costGate + shortcutMatrix (~8 min instead of ~3)
#
# Default runs build test + runGameTestServer only (what differs between Windows and Linux).
# Use -Full when the change touches shortcuts (Shortcut), allocation, or measurement baselines.
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
param([string]$Distro = "Ubuntu", [switch]$Full)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$wslRoot = (& wsl -d $Distro -- wslpath -a ($root -replace '\\', '/')).Trim()
if (-not $wslRoot) { Write-Error "wsl / distro '$Distro' not available"; exit 2 }

# Snapshot of what CI would check out: tracked + non-ignored files as git blobs (LF), built with a temporary index.
# (WSL git cannot read a git worktree, and the Windows working tree has CRLF which breaks ./gradlew on Linux.)
$tar = Join-Path $env:TEMP "efl-ci-src.tar"
$idx = Join-Path $env:TEMP "efl-ci-index"
Remove-Item $idx -ErrorAction SilentlyContinue
$env:GIT_INDEX_FILE = $idx
try {
    # Start from HEAD so tracked files that .gitignore matches (e.g. core/*.md added with -f) are kept, as in CI's checkout.
    git -C $root read-tree HEAD | Out-Null
    git -C $root add -A | Out-Null
    $tree = (git -C $root write-tree).Trim()
    git -C $root archive --format=tar -o $tar $tree
} finally {
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    Remove-Item $idx -ErrorAction SilentlyContinue
}
$wslTar = (& wsl -d $Distro -- wslpath -a ($tar.Replace('\', '/'))).Trim()

# CRLF would break the bash script when the repo is checked out with autocrlf
& wsl -d $Distro -- bash -c "sed -i 's/\r$//' '$wslRoot/scripts/ci-linux.sh' '$wslRoot/scripts/ci-summarize-tests.py' 2>/dev/null"
$fullFlag = if ($Full) { "1" } else { "0" }
& wsl -d $Distro -- env "EFL_CI_ARCHIVE=$wslTar" "EFL_CI_FULL=$fullFlag" bash "$wslRoot/scripts/ci-linux.sh"
exit $LASTEXITCODE
