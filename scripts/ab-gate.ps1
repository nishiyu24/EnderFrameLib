# Alternating A/B timing gate: an old commit (worktree) vs the current working tree.
# (ENGINE_REARCHITECTURE_PLAN.md operating rule 9 / DESIGN_INCREMENTAL_KERNEL.md section 4, K0 item 5)
#
# Why: absolute times drift +-40% between sessions and single runs wobble +-10..15% (plan 6-180), so a time claim is only valid as a
# SIMULTANEOUS alternating A/B: old and new run back to back, 3 rounds, the starting side flips every round, compare MEDIANS.
#
#   powershell -File scripts/ab-gate.ps1                                  # base ea4dc204, 3 rounds, large-list hover+text (GL)
#   powershell -File scripts/ab-gate.ps1 -Base HEAD~3 -Rounds 3
#   powershell -File scripts/ab-gate.ps1 -Cases "showcase:text:null,voidwave:hover:cpu" -MaxRegressionPct 2
#   powershell -File scripts/ab-gate.ps1 -Instrument                      # current side with Kernel.stats ON (cost of the instrument; base stays off)
#
# Cases are doc:scenario:backend (FineProfileMain arguments; backend null|cpu|gl|vk). Exit code 1 when any case regresses more than
# -MaxRegressionPct (median of mean ms per frame, current / base). Do not run builds or tests on the machine while it measures.
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI).
param(
    [string]$Base = "ea4dc204",
    [int]$Rounds = 3,
    [string]$Cases = "large-list:hover:gl,large-list:text:gl",
    [int]$Frames = 2000,
    [double]$MaxRegressionPct = 3.0,
    [switch]$Instrument,
    [switch]$KeepWorktree
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $env:TEMP ("efl-ab-" + ($Base -replace '[^A-Za-z0-9]', '_'))
$main = "com.nishiyu.enderframelib.bench.FineProfileMain"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
if (-not (Test-Path $java)) { $java = (Get-Command java).Source }

function Get-Classpath([string]$tree) {
    Push-Location $tree
    try {
        & .\gradlew.bat :core:profileClasspath -q | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "profileClasspath failed in $tree" }
    } finally { Pop-Location }
    $lines = Get-Content (Join-Path $tree "core\build\profile-classpath.txt") | Where-Object { $_.Trim() -ne "" }
    return ($lines -join ";")
}

function Invoke-Case([string]$cp, [string]$case, [bool]$stats) {
    $p = $case.Split(":")
    if ($p.Length -ne 3) { throw "case must be doc:scenario:backend -> $case" }
    $jargs = @("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Denderframe.threads=1", "-Xmx1g")
    if ($stats) { $jargs += "-Denderframe.kernel.stats=true" }
    $jargs += @("-cp", $cp, $main, $p[1], $p[0], "$Frames", $p[2])
    $out = & $java @jargs
    $line = $out | Where-Object { $_ -match "^RESULT " } | Select-Object -Last 1
    if (-not $line -or $line -notmatch "mean=([0-9.]+)") { throw "no RESULT line for $case`n$($out -join "`n")" }
    return [double]$Matches[1]
}

function Get-Median([double[]]$v) {
    $s = $v | Sort-Object
    $n = $s.Count
    if ($n % 2 -eq 1) { return $s[($n - 1) / 2] }
    return ($s[$n / 2 - 1] + $s[$n / 2]) / 2
}

# 1) the old tree (a detached worktree of $Base) and the current tree (this checkout, uncommitted changes included)
if (-not (Test-Path $work)) { git -C $root worktree add --detach $work $Base | Out-Null }
Write-Host "base   : $Base at $work"
Write-Host "current: $root (working tree)"
$cpBase = Get-Classpath $work
$cpCur = Get-Classpath $root

# 2) alternate: round r measures base then current when r is odd, current then base when r is even (cancels warm-up and drift)
$caseList = $Cases.Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne "" }
$res = @{}
foreach ($c in $caseList) { $res[$c] = @{ base = @(); cur = @() } }
for ($r = 1; $r -le $Rounds; $r++) {
    foreach ($c in $caseList) {
        if ($r % 2 -eq 1) {
            $res[$c].base += Invoke-Case $cpBase $c $false
            $res[$c].cur += Invoke-Case $cpCur $c ([bool]$Instrument)
        } else {
            $res[$c].cur += Invoke-Case $cpCur $c ([bool]$Instrument)
            $res[$c].base += Invoke-Case $cpBase $c $false
        }
        Write-Host ("round {0} {1}: base {2:N4} ms  current {3:N4} ms" -f $r, $c, $res[$c].base[-1], $res[$c].cur[-1])
    }
}

# 3) medians and the verdict
$bad = 0
Write-Host ""
Write-Host ("{0,-34} {1,12} {2,12} {3,9}  {4}" -f "case", "base ms", "current ms", "ratio", "verdict")
foreach ($c in $caseList) {
    $b = Get-Median $res[$c].base
    $n = Get-Median $res[$c].cur
    $pct = ($n / $b - 1.0) * 100.0
    $verdict = "ok"
    if ($pct -gt $MaxRegressionPct) { $verdict = "REGRESSION (> +$MaxRegressionPct%)"; $bad++ }
    Write-Host ("{0,-34} {1,12:N4} {2,12:N4} {3,8:N1}%  {4}" -f $c, $b, $n, $pct, $verdict)
}
if (-not $KeepWorktree) { git -C $root worktree remove --force $work | Out-Null }
if ($bad -gt 0) { exit 1 }
exit 0
