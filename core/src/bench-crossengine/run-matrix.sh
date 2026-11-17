#!/usr/bin/env bash
# A2 / A3 / A12 の計測を Gradle を介さずにまとめて回す（ENGINE_REARCHITECTURE_PLAN.md §5）。
#
# 使い方（リポジトリ直下で。計測中は同じマシンでビルドやテストを回さない）:
#   ./gradlew :core:profileClasspath          # build/profile-classpath.txt を作る
#   core/src/bench-crossengine/run-matrix.sh <出力ディレクトリ> [threads] [font] [real] [chrome]
#
# 段（引数に並べたものだけ回す。省略時は全部）:
#   threads : A3  ワーカー数 1 / 2 / 4 / 8 / 既定 での fontsize（:core のみ・内蔵の簡易書体）
#   font    : A2  Chrome 列のある行を、内蔵の簡易書体と実書体（Arial）の両方で（:core）
#   real    : A12 実文書 5 本 × hover / scroll / text / open（:core・実書体）
#   chrome  : A2 / A12 の Chrome 側（trace.js・run.js。書体の強制あり / なし）
#
# クラスパスは出力ディレクトリの snap/ へ複製してから使う（計測中に core/build が作り直されても影響を受けない）。
set -u
OUT="${1:?出力ディレクトリ}"; shift
STAGES="${*:-threads font real chrome}"
REPS="${REPS:-3}"
FONT="${FONT:-Arial}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
mkdir -p "$OUT"
cd "$ROOT"

if [ ! -f "$OUT/snap/cp.txt" ]; then
  rm -rf "$OUT/snap"; mkdir -p "$OUT/snap"
  CP=""; i=0
  while IFS= read -r line; do
    line="${line%$'\r'}"; [ -z "$line" ] && continue
    u="$(cygpath -u "$line" 2>/dev/null || echo "$line")"; i=$((i+1))
    if [ -d "$u" ]; then cp -r "$u" "$OUT/snap/d$i"; t="$OUT/snap/d$i"; else cp "$u" "$OUT/snap/"; t="$OUT/snap/$(basename "$u")"; fi
    CP="$CP;$(cygpath -w "$t" 2>/dev/null || echo "$t")"
  done < core/build/profile-classpath.txt
  echo "${CP#;}" > "$OUT/snap/cp.txt"
fi
CP="$(cat "$OUT/snap/cp.txt")"
MAIN=com.nishiyu.enderframelib.bench.FineProfileMain

# fine <ログ> <タグ> <JVM 引数...> -- <シナリオ 文書 フレーム数 バックエンド>
fine() {
  local log="$1" tag="$2"; shift 2
  local jvm=(); while [ "$1" != "--" ]; do jvm+=("$1"); shift; done; shift
  java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 "${jvm[@]}" -cp "$CP" $MAIN "$@" 2>&1 \
    | grep -E "^(RESULT|DOM|THREADS|FONT|GPUCOVERAGE)" | sed "s/^/[$tag] /" >> "$log"
}
frames() { case "$1" in fontsize|child|inherited|open) echo 400;; *) echo 1500;; esac; }

# A2 の行（Chrome 列のある行）: 文書:シナリオ,…:バックエンド
A2_ROWS="varied-list-2000:inherited,child,fontsize,scroll:gl large-list:hover,style:gl large-list-2000:text,hover,scroll,child,fontsize:gl showcase:scroll,hover,text,style,open,resizeexact:gl showcase:hover:cpu"
REAL_DOCS="surface_test.html voidwave.html animation_test.html media_test.html machine_ui.html"
REAL_SCENS="hover scroll text open"

for stage in $STAGES; do
  case "$stage" in
    threads)
      : > "$OUT/threads.log"
      for rep in $(seq 1 "$REPS"); do for doc in varied-list-2000 large-list-2000; do for n in 1 2 4 8 default; do
        if [ "$n" = default ]; then fine "$OUT/threads.log" "rep=$rep threads=default" -- fontsize "$doc" 400 gl
        else fine "$OUT/threads.log" "rep=$rep threads=$n" "-Denderframe.threads=$n" -- fontsize "$doc" 400 gl; fi
      done; done; done ;;
    font)
      : > "$OUT/font.log"
      for rep in $(seq 1 "$REPS"); do for row in $A2_ROWS; do
        doc="${row%%:*}"; rest="${row#*:}"; scs="${rest%%:*}"; be="${rest#*:}"
        for sc in ${scs//,/ }; do
          fine "$OUT/font.log" "rep=$rep font=fallback" -- "$sc" "$doc" "$(frames "$sc")" "$be"
          fine "$OUT/font.log" "rep=$rep font=$FONT" "-Dprofile.font=$FONT" -- "$sc" "$doc" "$(frames "$sc")" "$be"
        done
      done; done ;;
    real)
      : > "$OUT/real.log"
      for rep in $(seq 1 "$REPS"); do for doc in $REAL_DOCS; do for sc in $REAL_SCENS; do
        fine "$OUT/real.log" "rep=$rep font=$FONT" "-Dprofile.font=$FONT" -- "$sc" "$doc" "$(frames "$sc")" gl
      done; done; done ;;
    chrome)
      : > "$OUT/chrome-trace.log"
      java -Dfile.encoding=UTF-8 -cp "$CP" com.nishiyu.enderframelib.bench.Workloads varied-list-2000 "$OUT/varied-list-2000.html"
      export VARIED_FILE="$OUT/varied-list-2000.html"
      for rep in $(seq 1 "$REPS"); do
        for f in "" "$FONT"; do
          for row in $A2_ROWS; do
            doc="${row%%:*}"; rest="${row#*:}"; scs="${rest%%:*}"; be="${rest#*:}"
            mode=gpu; [ "$be" = cpu ] && mode=soft
            for sc in ${scs//,/ }; do
              case "$sc" in open|resizeexact) continue;; esac   # run.js が測る
              BENCH_FONT="$f" node "$HERE/trace.js" "$doc" "$sc" "$mode" 2>/dev/null >> "$OUT/chrome-trace.log"
            done
          done
        done
        for doc in $REAL_DOCS; do for sc in hover scroll text; do
          BENCH_FONT="$FONT" node "$HERE/trace.js" "$doc" "$sc" gpu 2>/dev/null >> "$OUT/chrome-trace.log"
        done; done
      done
      # 幅変更（relayout.styleLayout）と open（srcdoc → load → 強制レイアウト）。run.js は結果を自分のディレクトリへ書く
      cp "$HERE/res-chrome-showcase.json" "$OUT/res-chrome-showcase-committed.json" 2>/dev/null   # 追跡されている結果は上書きしない
      for f in "" "$FONT"; do BENCH_FONT="$f" node "$HERE/run.js" chrome showcase >/dev/null 2>&1; done
      for doc in $REAL_DOCS; do BENCH_FONT="$FONT" node "$HERE/run.js" chrome "$doc" >/dev/null 2>&1; done
      mv "$HERE"/res-chrome-*-"$FONT".json "$OUT/" 2>/dev/null
      cp "$HERE/res-chrome-showcase.json" "$OUT/res-chrome-showcase-default.json" 2>/dev/null
      cp "$OUT/res-chrome-showcase-committed.json" "$HERE/res-chrome-showcase.json" 2>/dev/null ;;
  esac
  echo "stage $stage done: $(date +%H:%M:%S)" >> "$OUT/progress.txt"
done
echo "all done" >> "$OUT/progress.txt"
