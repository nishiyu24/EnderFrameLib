#!/usr/bin/env bash
# FineProfileMain を doc × シナリオ × バックエンドで REPS 回ずつ回し、fine.log に溜める（R0: 3 回実行で中央値・最小・最大を出す）。
# 使い方（リポジトリ直下で）:
#   REPS=3 core/src/bench-crossengine/run-fine.sh fine.log
# 対象は MATRIX 環境変数（"doc:scenario,scenario:backend,backend ..." 形式）で上書きできる。
set -u
OUT="${1:-fine.log}"
REPS="${REPS:-3}"
FRAMES="${FRAMES:-1500}"
MATRIX="${MATRIX:-\
showcase:idle,scroll,hover,text,style,inherited,fontsize,child,open:null,gl,cpu \
large-list:scroll,hover,text,style,inherited,fontsize,child,open:null,gl,cpu \
large-list-2000:scroll,hover,text,style,child,inherited,fontsize,relayout:null,gl}"
: > "$OUT"
for rep in $(seq 1 "$REPS"); do
  for entry in $MATRIX; do
    doc="${entry%%:*}"; rest="${entry#*:}"; scs="${rest%%:*}"; bes="${rest#*:}"
    for be in ${bes//,/ }; do
      for sc in ${scs//,/ }; do
        ./gradlew :core:profile -Pprofile.main=com.nishiyu.enderframelib.bench.FineProfileMain \
          -Pprofile.args="$sc $doc $FRAMES $be" --console=plain 2>&1 \
          | grep -E "^(RESULT|DOM|ITEMS|ACCOUNTED|PATCH|DAMAGE|GL |stage|[a-z.()+-]+ +[0-9]+ +[0-9.]+ )" \
          | sed "s/^/[rep=$rep $doc $sc $be] /" >> "$OUT"
      done
    done
  done
done
echo "done: $(grep -c '\] RESULT' "$OUT") runs"
