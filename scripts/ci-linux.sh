#!/usr/bin/env bash
# GitHub Actions の Linux 環境をローカルに再現する。build.yml（検査）と full-tests.yml（全テスト）の両方を合わせた手順
# （build test + runGameTestServer）なので、CI より厳しい。全テストの確認はここが担う。
#
# なぜ要るか: 開発は Windows で行うが、CI は ubuntu-latest。書体・GPU(OpenGL/Vulkan の有無)・パス・ファイルの実行ビットが
# 違うため、Windows で全件緑でも CI だけ赤くなる（ルート JAR のサービスファイル重複、書体幅に依るテスト、OS 別の合格一覧など）。
# push する前にこれを回せば、同じ失敗をローカルで見られる。
#
# 使い方:
#   WSL / Linux:  scripts/ci-linux.sh
#   Windows:      powershell -File scripts/ci-linux.ps1        （WSL の Ubuntu 上でこのスクリプトを呼ぶ）
#
# CI との揃え方:
#   - 追跡されているファイルと、無視されていない未追跡ファイルだけを作業ディレクトリへ写す（CI の checkout と同じ内容。
#     .gitignore の基準画像・build 出力・run/ などは持ち込まない）
#   - 表示環境を外す（DISPLAY / WAYLAND_DISPLAY を unset）。CI には GPU も画面も無いので、GL・Vulkan のテストは中断（skip）になる
#   - JDK 21（Temurin）。無ければ ~/.cache/efl-jdk へ取得する
#   - 実行する Gradle コマンドも CI と同じ: ./gradlew build test --stacktrace
#     （ただし CI が外す実時間のテスト @Tag("timing") は、ここでは流す。共有ランナーより空いた開発機で速さの回帰を見る）
set -u

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${EFL_CI_DIR:-$HOME/.cache/efl-ci/work}"
JDK_HOME_DIR="${EFL_JDK_DIR:-$HOME/.cache/efl-jdk}"

need_jdk=1
if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 'version "21'; then
  need_jdk=0
fi
if [ "$need_jdk" = 1 ]; then
  jdk="$(ls -d "$JDK_HOME_DIR"/jdk-21* 2>/dev/null | head -1)"
  if [ -z "$jdk" ]; then
    echo "[ci-linux] JDK 21 を取得します: $JDK_HOME_DIR"
    mkdir -p "$JDK_HOME_DIR" || exit 1
    curl -sfL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$JDK_HOME_DIR/jdk.tgz" || { echo "[ci-linux] JDK の取得に失敗"; exit 1; }
    tar xzf "$JDK_HOME_DIR/jdk.tgz" -C "$JDK_HOME_DIR" || exit 1
    rm -f "$JDK_HOME_DIR/jdk.tgz"
    jdk="$(ls -d "$JDK_HOME_DIR"/jdk-21* | head -1)"
  fi
  export JAVA_HOME="$jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "[ci-linux] 作業ディレクトリへ同期: $WORK"
mkdir -p "$WORK" || exit 1

# CI の checkout と同じ内容にする: 追跡 + 無視されない未追跡のファイルを、git の blob（改行は LF）で取り出す。
# Windows の作業ツリーは CRLF なので、そのまま写すと gradlew が `bad interpreter` になるなど、CI と別物になる。
# スナップショットは一時的な index に `git add -A` した木から `git archive` で作る（本物の index・コミットには触れない）。
# Windows 側（ci-linux.ps1）が作った tar を EFL_CI_ARCHIVE で渡せる（git worktree は WSL の git から読めないため）
archive="${EFL_CI_ARCHIVE:-}"
if [ -z "$archive" ]; then
  archive="$WORK/../src.tar"
  tmpindex="$(mktemp -u)"
  # HEAD から始める（.gitignore に当たるが追跡されているファイル〔-f で足した core/*.md など〕も CI の checkout と同じく入れる）
  tree="$(cd "$REPO" && GIT_INDEX_FILE="$tmpindex" git read-tree HEAD && GIT_INDEX_FILE="$tmpindex" git add -A && GIT_INDEX_FILE="$tmpindex" git write-tree)" || { echo "[ci-linux] git でスナップショットを作れません"; exit 1; }
  (cd "$REPO" && git archive --format=tar "$tree") > "$archive" || exit 1
  rm -f "$tmpindex"
fi
# 前回の成果物（build/ と .gradle/）は残して、ソースだけ入れ替える
find "$WORK" -mindepth 1 -maxdepth 1 ! -name build ! -name .gradle -exec rm -rf {} +
tar xf "$archive" -C "$WORK" || { echo "[ci-linux] スナップショットの展開に失敗"; exit 1; }

cd "$WORK" || exit 1
chmod +x ./gradlew
# テスト結果は毎回作り直す（前回の失敗が残って見えないように）
find . -path '*/build/test-results' -prune -exec rm -rf {} + 2>/dev/null

echo "[ci-linux] ./gradlew build test --stacktrace  （DISPLAY なし = GPU なし。CI と同じ）"
# XDG_RUNTIME_DIR も外す（WSLg では wayland-0 のソケットが既定の場所にあり、WAYLAND_DISPLAY を外しても GLFW が繋がって GL のテストが走ってしまう）
env -u DISPLAY -u WAYLAND_DISPLAY -u XDG_RUNTIME_DIR ./gradlew build test --stacktrace --console=plain --continue 2>&1 | tee "$WORK/../last.log"
status=${PIPESTATUS[0]}

# build.yml の "Cost gate"。確保量と経路の回数の回帰ゲート（時間は見ない）。テストの成否に関わらず回して、結果は合否に足す
echo "[ci-linux] ./gradlew :core:costGate --stacktrace"
env -u DISPLAY -u WAYLAND_DISPLAY -u XDG_RUNTIME_DIR ./gradlew :core:costGate --stacktrace --console=plain 2>&1 | tee -a "$WORK/../last.log"
gate=${PIPESTATUS[0]}
[ "$status" = 0 ] && status=$gate

# full-tests.yml の夜間ジョブ "shortcut-matrix"（A6）。近道スイッチの単独オフ＋ペアワイズで、表示リストと画素が既定と一致するか
echo "[ci-linux] ./gradlew :core:shortcutMatrix --stacktrace"
env -u DISPLAY -u WAYLAND_DISPLAY -u XDG_RUNTIME_DIR ./gradlew :core:shortcutMatrix --stacktrace --console=plain 2>&1 | tee -a "$WORK/../last.log"
matrix=${PIPESTATUS[0]}
[ "$status" = 0 ] && status=$matrix

# CI の 2 つ目のステップ（build.yml の "Game test server"）。専用サーバーを 1 回走らせてセッション寿命を確認する。build が通ったときだけ
if [ "$status" = 0 ]; then
  echo "[ci-linux] ./gradlew runGameTestServer --stacktrace"
  env -u DISPLAY -u WAYLAND_DISPLAY -u XDG_RUNTIME_DIR ./gradlew runGameTestServer --stacktrace --console=plain 2>&1 | tee -a "$WORK/../last.log"
  status=${PIPESTATUS[0]}
fi

echo
echo "[ci-linux] ===== 失敗したテスト ====="
python3 "$REPO/scripts/ci-summarize-tests.py" --plain "$WORK" || true
if [ "$status" = 0 ]; then
  echo "[ci-linux] OK（CI と同じ手順で全て成功）"
else
  echo "[ci-linux] 失敗（終了コード $status）。作業ディレクトリ: $WORK"
fi
exit "$status"
