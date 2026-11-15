#!/usr/bin/env python3
"""JUnit の結果 XML（各モジュールの build/test-results/test/*.xml）から、失敗したテストを集めて出力する。

CI では GitHub のエラー注釈（::error）と、ジョブのサマリー（$GITHUB_STEP_SUMMARY）へ書く。
注釈はログを取得する権限が無くても API（check-runs/<id>/annotations）から読めるので、失敗の原因を後から調べやすい。
ローカル（--plain）では標準出力へ並べるだけ。

使い方: ci-summarize-tests.py [--plain] [ルートディレクトリ]
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MAX_ANNOTATIONS = 40  # GitHub は 1 ステップあたり 10 件までしか表示しないが、API には残る。多すぎる出力は切る


def collect(root):
    failures, totals = [], {"tests": 0, "failed": 0, "skipped": 0}
    pattern = os.path.join(root, "**", "build", "test-results", "*", "*.xml")
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in suite.iter("testcase"):
            totals["tests"] += 1
            if case.find("skipped") is not None:
                totals["skipped"] += 1
            bad = case.find("failure")
            if bad is None:
                bad = case.find("error")
            if bad is not None:
                totals["failed"] += 1
                msg = (bad.get("message") or (bad.text or "")).strip().replace("\n", " ")
                failures.append((case.get("classname", "?"), case.get("name", "?"), msg[:400]))
    return failures, totals


def main(argv):
    plain = "--plain" in argv
    args = [a for a in argv[1:] if not a.startswith("--")]
    root = args[0] if args else "."
    failures, totals = collect(root)
    line = f"tests={totals['tests']} failed={totals['failed']} skipped={totals['skipped']}"
    if plain:
        print(line)
        for cls, name, msg in failures:
            print(f"  FAILED {cls.rsplit('.', 1)[-1]} > {name}\n         {msg}")
        return 0
    for cls, name, msg in failures[:MAX_ANNOTATIONS]:
        text = f"{cls} > {name}: {msg}".replace("%", "%25").replace("\r", "").replace("\n", "%0A")
        print(f"::error title=Test failed::{text}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"### Test results\n\n{line}\n\n")
            if failures:
                f.write("| class | test | message |\n|---|---|---|\n")
                for cls, name, msg in failures:
                    f.write(f"| {cls.rsplit('.', 1)[-1]} | {name} | {msg.replace('|', '/')} |\n")
    print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
