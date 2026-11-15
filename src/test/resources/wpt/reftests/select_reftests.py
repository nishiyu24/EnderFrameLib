#!/usr/bin/env python3
"""WPT の参照画像比較（reftest）の部分集合を選んで、このディレクトリへ写す（B1⑤）。

使い方（リポジトリ直下から）:
    git clone --filter=blob:none --no-checkout https://github.com/web-platform-tests/wpt.git <wpt>
    git -C <wpt> sparse-checkout set css/css-flexbox css/css-grid css/css-text
    git -C <wpt> checkout a6f29b0bedaf3f1edba7b6739127fe8e713bfcb3     # manifest.json の pinnedRevision と同じ
    python src/test/resources/wpt/reftests/select_reftests.py <wpt> [flexbox=120 grid=80 text=60]

選ぶ条件（エンジンの範囲内で、機械的に決まるもの）:
  - rel=match がちょうど 1 つ・参照が存在し、参照自体は別の参照を持たない
  - スクリプト・外部リソース（画像・フォント・@font-face・url()・外部 CSS・フレーム・フォーム部品・SVG）を使わない
  - Ahem を使わない（フォントに依存して画素が変わるため）
選んだ全候補を名前順に並べ、等間隔に n 件を取る（偏らないよう）。写し先は wpt の相対パスのまま（css/css-flexbox/...）。
WPT のライセンスは BSD 3-Clause（https://github.com/web-platform-tests/wpt/blob/master/LICENSE.md）。
"""
import os, re, shutil, sys

BAD_T = r'<script|font-face|url\(|<img|<iframe|<svg|<canvas|<object|<video|<audio|<input|<button|<select|<textarea|@import|Ahem|<link[^>]+rel=["\']?stylesheet|<embed|<frame'
BAD_R = r'<script|font-face|url\(|<img|<iframe|<svg|<canvas|Ahem|<link[^>]+rel=["\']?(match|mismatch|stylesheet)|@import|<embed'
SPECS = {'flexbox': 'css/css-flexbox', 'grid': 'css/css-grid', 'text': 'css/css-text'}


def norm(p):
    return os.path.normpath(p).replace(os.sep, '/')


def candidates(wpt, spec_dir):
    out = []
    root = os.path.join(wpt, spec_dir)
    for r, _, xs in os.walk(root):
        for x in xs:
            if not x.endswith(('.html', '.htm', '.xht', '.xhtml')):
                continue
            f = os.path.join(r, x)
            s = open(f, encoding='utf-8', errors='replace').read()
            m = re.findall(r'<link[^>]+rel=["\']?match["\']?[^>]*>', s)
            if len(m) != 1 or re.search(BAD_T, s, re.I):
                continue
            h = re.search(r'href=["\']?([^"\' >]+)', m[0]).group(1)
            rf = os.path.join(wpt, h[1:]) if h.startswith('/') else os.path.join(os.path.dirname(f), h)
            if not os.path.exists(rf):
                continue
            if re.search(BAD_R, open(rf, encoding='utf-8', errors='replace').read(), re.I):
                continue
            out.append((norm(os.path.relpath(f, wpt)), norm(os.path.relpath(rf, wpt))))
    return sorted(out)


def main():
    wpt = sys.argv[1]
    counts = {'flexbox': 120, 'grid': 80, 'text': 60}
    for a in sys.argv[2:]:
        k, v = a.split('=')
        counts[k] = int(v)
    here = os.path.dirname(os.path.abspath(__file__))
    manifest = []
    for key, spec_dir in SPECS.items():
        c = candidates(wpt, spec_dir)
        n = min(counts[key], len(c))
        picked = [c[(i * len(c)) // n] for i in range(n)] if n else []
        print(key, 'candidates', len(c), 'picked', len(picked))
        for t, r in picked:
            for rel in (t, r):
                dst = os.path.join(here, rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.copyfile(os.path.join(wpt, rel), dst)
            manifest.append(t + '\t' + r)
    with open(os.path.join(here, 'manifest.tsv'), 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('# test<TAB>reference（wpt ルートからの相対パス）。select_reftests.py が生成\n')
        fh.write('\n'.join(manifest) + '\n')
    print('manifest', len(manifest))


main()
