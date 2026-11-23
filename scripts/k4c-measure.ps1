# GL + Arial matrix, 3 runs each (K4c). ASCII only. Output: results file path in $env:TEMP\k4c.txt
$root = Split-Path -Parent $PSScriptRoot
$cp = ((Get-Content (Join-Path $root "core\build\profile-classpath.txt")) | Where-Object { $_.Trim() -ne "" }) -join ";"
$java = (Get-Command java).Source
$out = Join-Path $env:TEMP "k4c.txt"
Remove-Item $out -ErrorAction SilentlyContinue
$cases = @(
 "inherited varied-list-2000 1500 gl","child varied-list-2000 1500 gl","fontsize varied-list-2000 600 gl","scroll varied-list-2000 3000 gl",
 "hover large-list 3000 gl","style large-list 3000 gl",
 "text large-list-2000 3000 gl","hover large-list-2000 3000 gl","scroll large-list-2000 3000 gl","child large-list-2000 1500 gl","fontsize large-list-2000 600 gl",
 "scroll showcase 3000 gl","hover showcase 3000 gl","text showcase 3000 gl","style showcase 3000 gl","open showcase 100 gl","hover showcase 1500 cpu",
 "hover voidwave.html 2000 gl","hover surface_test.html 2000 gl","hover animation_test.html 2000 gl","scroll voidwave.html 2000 gl","text voidwave.html 2000 gl"
)
foreach ($c in $cases) {
  for ($r = 1; $r -le 3; $r++) {
    $a = $c.Split(" ")
    $line = & $java "-Dprofile.font=Arial" -cp $cp com.nishiyu.enderframelib.bench.FineProfileMain $a[0] $a[1] $a[2] $a[3] 2>&1 | Select-String -Pattern "^RESULT" | Select-Object -First 1
    "$c | run$r | $line" | Add-Content $out
  }
}
"DONE" | Add-Content $out
