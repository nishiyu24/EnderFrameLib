# EnderFrameLib 機能拡張プラン（Track P: 新しい機能・ホスト・配布）

> **更新日**: 2026-11-28（リファクタリングの計画書 [`ENGINE_REARCHITECTURE_PLAN.md`](ENGINE_REARCHITECTURE_PLAN.md) の §3.0b・§3.9 から Track P1〜P5 を移して分離した。同日 **P3 を「脱 LWJGL・純 Java のホストと OS のメディア」に作り直し**〔旧 P3 の AWT 窓ホストは撤回〕、**P6「Three.js 本家レベルの 3D と HTML の融合」**を新設した）
> **対象**: エンジン（`:core` 系）に足す機能・ホスト・配布。エンジン内部のデータ構造の刷新（本流 Phase M0〜M6）はリファクタリングの計画書が扱い、本書はその段（M3b・M4・M6）を前提として参照するだけにする。
> **ルール**: リファクタリングの計画書の §5（8 原則）をそのまま適用する（キャッシュの鍵・プロセスグローバルな可変状態の禁止・クラスの規模・旧経路の同時削除・判定は絶対の上限と外から見た正しさ・計画書は状態表）。本書に固有の追加ルールは §5。
> **この文書の書き方**: 状態表であり作業ログではない。§0 と §4 を作業のたびに上書きし、経緯はコミットの本文と [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md)（付録 5.A に 1 行）へ書く。

---

## 0. 現在地（2026-11-28。作業のたびに上書きする）

| 項目 | 状態 |
| :--- | :--- |
| 完了 | **P1a**（書体の目録。2026-11-25）・**P4-1**（リポジトリの Core 主体化。MOD を `mod/` へ分離。2026-11-27） |
| 進行中 | なし（P1b は計測の道具 `:core:coldStartAb` まで） |
| 次の一手 | 本流の前提が要らない準備から: P1b のゲートの判定・P3b-1（純 Java の分離器・MP3・Opus。画面への組み込みは M3b の後）・P3b-2 の OS のデコーダのスパイク・P6b の 3D 版 WPT に使う Three.js 公式 example の選定 |
| 止まっている条件 | 本流の M3b（P2・P3a）・M4（P3c 以降・P4-2・P6a）・M6（P5） |
| 一時的に無い機能 | なし（P3 で OS ごとに GLFW から切り替える間に開く穴は、ここに書く） |

---

## 1. 位置づけと方針

### 1.1 位置づけ（2026-11-25 確定。2026-11-28 に 3 を追加）

`:core` 系は Minecraft に依存しない汎用の HTML/CSS レイアウト・描画エンジンであり、MOD はその利用者の 1 つにすぎない（依存は MOD → Core の一方向）。目標は **JavaFX・Compose Multiplatform Desktop と並ぶ Java の汎用 UI 基盤**とする。勝ち筋の順序は次のとおり。
1. **先に強い分野を取る**（M6 まで）: 既存の GL/Vulkan の描画ループへの埋め込み（LWJGL・libGDX・jMonkeyEngine・Minecraft 等。JavaFX が苦手とする領域）と、決定的なヘッドレス描画。
2. **M6 の後に汎用ツールキットへ広げる**（群 C・Track P5）: JavaFX に対して欠けているもの（アクセシビリティ・状態バインディング・高機能部品・OS 統合）を埋める。
3. **3D と HTML の融合**（群 D・Track P6）: Three.js 本家と同じ水準の 3D を Java の API と HTML のタグの両方から使え、さらに HTML の部品を 3D の中で深度つきで操作できる（ブラウザの Three.js では作りにくい領域）。

### 1.2 純 Java の定義と Java の版（2026-11-28 確定）

- **純 Java**: エンジンの配布物に JNI のネイティブ（`.dll`/`.so`/`.dylib`）を同梱せず、LWJGL・JNA に依存しない。OS が元から持つ共有ライブラリ（`user32`・`opengl32`・`vulkan-1`・Media Foundation・`libwayland-client`・`libEGL`・`libxkbcommon`・GStreamer・Cocoa・Metal・VideoToolbox）は呼んでよい。
- **Java の版の線引き**: MOD（Minecraft 1.21.1 = Java 21）が使うモジュールは `--release 21` を保ち、それ以外は Java 25（LTS）を使う。FFM（`java.lang.foreign`）は Java 22 で正式になったので、MOD の側では使わない。

| 区分 | モジュール・機能 | Java | OS を呼ぶ手段 |
| :--- | :--- | :---: | :--- |
| MOD が使う | `:core`（ソフトウェア描画・書体・動画の分離器と同期・MP3/Opus/GIF）・`:core-gl` の `GlDevice`（GL の関数を口 `GlApi` 越しに呼ぶ）・OS のデコーダの呼び方（口 `NativeCall` 越し） | 21 | なし（OS を呼ぶのは口の向こう） |
| MOD の中だけ（`:mod`） | `GlApi` の LWJGL 版・`NativeCall` の JNA 版 | 21 | Minecraft が同梱する LWJGL・JNA（ネイティブを足さない） |
| 単体アプリだけ | 窓のホスト（Win32・Wayland・X11・Cocoa）・`GlApi`/`NativeCall` の FFM 版・`:core-vulkan`・`:core-metal` | 25 | FFM |

- **性能の考え方**: 脱 LWJGL は速さのためではない（窓の自由度・動く OS・ネイティブの同梱 0・書体の解析の安全性のため）。見込みは次のとおりで、判定は段ごとの絶対の上限と交互 A/B で行い、旧実装（FreeType・GLFW・`lunex_video.dll`）との比は記録だけにする（リファクタリングの計画書 §5 のルール 6）。
  - GL・Vulkan の呼び出し: FFM は JNI と同等。RHI がパスとバッチの単位で呼ぶので 1 フレームに数百回で、フレームの時間では見分けられない。
  - 書体: 温まった後は FreeType と同等、起動直後はやや遅い見込み。字形はアトラスに残るので毎フレームの費用ではない。単体アプリは AOT キャッシュ（JEP 483/515）で緩和する。
  - 動画: 重い復号は OS のデコーダ（ハードウェア）に任せる。純 Java の H.264・VP9 の復号器は作らない。
  - 窓: 自前の窓の手続きで移動・リサイズ中も描ける（GLFW では OS がメッセージループを握って止まる）。

---

## 2. 優先順位表（Track P の唯一の表）

| 群 | トラック | タイトル | 前提条件 | 完了時の定量的な成果 | 状態 |
| :--- | :--- | :--- | :---: | :--- | :--- |
| **A. 性能** | **P1** | コールド起動の短縮 | なし | P1a: 書体の読み込み `243 → 24 ms`。P1b: コールド起動 `≤ 200 ms` | P1a 完了・P1b 計測の道具まで |
| **A. 性能** | **P2** | 並列レイアウト・描画スレッドの分離 | M3b 完了 | `voidwave.html open` `9.15 → ≤ 5.5 ms`・`varied-list-2000 fontsize`（4 スレッド・実書体）`10.76 → ≤ 5.0 ms` | 未着手 |
| **B. ホストと配布** | **P3** | 脱 LWJGL・純 Java のホストと OS のメディア（P3a〜P3h） | P3a: M3b 完了／P3b: なし（画面への組み込みは M3b の後）／P3c〜P3h: M4 完了 | エンジンのモジュールで `org.lwjgl`・`com.sun.jna` が `0`・同梱のネイティブ `0`・MOD が使うモジュールは Java 21 のまま・Win32 / Wayland / X11 / Cocoa の窓を 144Hz・MP4 / WebM / MP3 / GIF を Windows・macOS・Linux で再生 | 未着手（2026-11-28 に作り直し。旧 P3 の AWT 窓ホストは撤回） |
| **B. ホストと配布** | **P4** | リポジトリの Core 主体化・分割・独立名への改名 | P4-1: M2c の完了の後（旧経路の削除で `:core` の API が落ち着いてから）／P4-2: M4 完了・API 確定（M6 と同時） | P4-1: `:core` 系だけのビルドで NeoForge の設定処理 0。P4-2: エンジンのリポジトリ単体で全ゲート緑・Maven Central 公開 | **P4-1 完了**（2026-11-27）・P4-2 未着手（前提 M4） |
| **C. 汎用ツールキット化** | **P5** | JavaFX と並ぶ汎用 UI 基盤へ（P5a〜P5f） | P5f の測定基盤: なし／他: M6 完了（P5d は P3 も） | 業務アプリ相当の見本がスクリーンリーダーで操作可能・JavaFX / Compose との交互 A/B の比較表を公開 | 未着手 |
| **D. 空間 UI（3D）** | **P6** | Three.js 本家レベルの 3D と HTML の融合（P6a〜P6h） | P6a: M4 完了／P6h: M3b・M4・P3b-3 | Three.js 公式 example の参照画像との一致数のラチェット・3D の全機能が CPU / GL / Vulkan（P3h の後は Metal も）で許容差内に一致・`<ui-panel-3d>` で 3D の中の HTML を操作できる | 未着手（2026-11-28 新設） |

---

## 3. 各トラックの詳細

### 3.1 群 A. 性能

#### Track P1・P2

- **Track P1: コールド起動の短縮**（前提なし）
  - **P1a 書体の目録〔完了 2026-11-25〕**: 起動ごとの全フォルダの走査を、ディスクの書体の目録（`FontCatalog`。走査は `core/text/font/FontScanner`）に置き換えた。書体の読み込み `243 → 24 ms`、実書体での起動 `786 → 718 ms`（交互 A/B・中央値）。検証は `FontCatalogTest`。
  - **P1b JVM の冷えた起動**（計測の道具 `:core:coldStartAb` まで完了）: JDK 25 + AOT キャッシュ（JEP 483）で `540 → 228 ms`（`0.42x`）。旧 `ColdStartMain` は書体なし（`FALLBACK`）で測っていた。ゲートはコールド起動 `≤ 200 ms` で、M2c-3 の後に判定する。
- **Track P2: 並列レイアウトと描画スレッドの非同期パイプライン**（前提: M3b 完了）: 不変の断片木と scene の上で、部分木単位の並列レイアウト（`LayoutTaskPool`）と、論理スレッドと描画スレッドの分離を行う。ゲート: 実文書の初回構築（`voidwave.html open` 等）`9.15 → ≤ 5.5 ms`。全要素の再スタイル（`varied-list-2000 fontsize`。4 スレッド・実書体）`10.76 → ≤ 5.0 ms`（144Hz の予算 `6.94 ms`。1 スレッドでは再スタイルだけで約 26 ms かかり、本流 M3b の範囲では届かないので 2026-11-28 に本流の計画書 §1.4 決定 K でここへ移した）。

### 3.2 群 B. ホストと配布

#### Track P3: 脱 LWJGL・純 Java のホストと OS のメディア（2026-11-28 に作り直し）

**現状（2026-11-28 の実コード）**:
- `:core` は `org.lwjgl:lwjgl`・`lwjgl-freetype` を `api` 依存で持つが、使うのは FreeType の 7 ファイル（`LoadedFace` 948 行ほか。輪郭・SDF・COLR v0/v1・可変書体・SFNT の表・合成太字）だけ。
- `:core-gl` の `GlContext` は窓なしの描画でも見えない GLFW の窓で GL のコンテキストを作る。呼ぶ関数は GL が約 90（`GlDevice`）、Vulkan が約 98（SPIR-V は事前にコンパイルした資源）、GLFW が約 30（`:core-glfw` の `StandaloneViewer` 266 行）。
- `WindowHost` SPI は `show(html, w, h)`（閉じるまで戻らない）だけで、窓を扱う口が無い。`Keys.nameProvider` は GLFW が書く `static volatile`（ルール 4 の残り）。
- 動画は `lunex_video.dll`（Windows x64 専用）を JNA（`NativeVideoApi`）で呼ぶ。GIF は純 Java（`GifAnimation`）。
- MOD の 11 ファイルが直接使う `org.lwjgl`（Minecraft の GL・入力）は P3 の対象外（MOD の GL は Minecraft が持つ）。

**段**:

| 段 | 内容 | Java | 前提 | 状態 |
| :--- | :--- | :---: | :--- | :--- |
| **P3a** | **純 Java の書体エンジン**: FreeType を置き換える。sfnt の表（cmap・glyf/loca・CFF/CFF2・fvar/gvar/avar/HVAR・COLR/CPAL・CBDT/sbix・name・OS/2・hhea/hmtx）を要るときだけ読み、面積を累積する AA のラスタライザ・SDF の生成・合成太字と斜体を持つ。既存の `GsubTable`・`KerningTable` を使う。同じ段で FreeType の経路を削除し、`:core` から `lwjgl`・`lwjgl-freetype` を外す。ゲート: WPT `≥ 170/260`・文字のゴールデンは人が違いを確かめて取り直す・不正な書体のファズで例外と過大な確保が外へ出ない・初回表示の文字の準備時間の絶対の上限（スパイクで決める） | 21 | M3b 完了（取り直しを M3b の画素の検証と重ねない） | 未着手 |
| **P3b** | **動画と音声（ブラウザ型）**: 対象は MP4（H.264/AAC）・WebM（VP8/VP9・Opus）・MP3・GIF。コンテナの分離・音と映像の同期（ビューの `Clock`）・バッファ・シーク・HTTP の範囲取得は Java で 1 つだけ書き、圧縮されたフレームの復号だけを OS のデコーダに借りる（Chrome・Firefox と同じ形。OS ごとに振る舞いが変わらない）。MP3・Opus・GIF は純 Java。音の出力は MOD が Minecraft の音（位置つきにできる）、単体アプリが `javax.sound.sampled`。段と OS の表は下の「P3b の段と OS のデコーダ」 | 21（FFM の口だけ 25） | なし（画面への組み込みは M3b の後） | 未着手 |
| **P3c** | **ホストの口と static の除去**: `WindowHost.show` を、`WindowSystem` → `PlatformWindow`（複数）→ `PresentTarget`（ソフトウェアの転送か GPU の swapchain）と、M4 の `InputController` へ入力を渡す口に置き換える。キー名の問い合わせを窓ごとにし、`Keys.nameProvider` の `static volatile` を消す。偽の `WindowSystem` を `Clock.Manual` と組み、ドラッグ領域・ポップアップ・DPI の変化をヘッドレスで確かめる | 21 | M4 完了 | 未着手 |
| **P3d** | **Win32 のホスト（FFM）**: 窓の手続きを upcall で受け、`WM_SIZE`・`WM_PAINT` の中で描く。`WM_NCHITTEST` を scene の当たり判定と CSS の `app-region: drag / no-drag` で答える（HTML のタイトルバー・リサイズの縁）。枠なし・DWM の透過と背景・Per-Monitor DPI v2・IMM32 の IME（候補窓をキャレットへ）・CSS の `cursor`・クリップボード（テキスト・HTML・画像）・`<select>`/メニュー/ツールチップを OS の窓として出すポップアップ・複数窓。表示はまずソフトウェア描画（`StretchDIBits`/`UpdateLayeredWindow`。描き直す範囲だけ送る）。同じ段で Windows の GLFW の経路を外す | 25 | P3c | 未着手 |
| **P3e** | **GPU の FFM 化**: GL は WGL / EGL で直接コンテキストを作る（見えない窓を使わない。窓なしは EGL の pbuffer か surfaceless）。Vulkan はローダを FFM で呼び、`VK_KHR_win32_surface`/`wayland_surface`/`xlib_surface` で窓に出す。呼び出しの部品は `scripts/GenGlBindings.java` が Khronos の XML から使う関数だけを生成する（手で直さない）。`GlDevice` は口 `GlApi` 越しに呼ぶ形にし（Java 21）、実装は FFM 版と LWJGL 版（`:mod` に置く）の 2 つだけ。`:core-gl`・`:core-vulkan` から LWJGL を外す | 21（`GlDevice`）/ 25 | P3d | 未着手 |
| **P3f** | **Linux のホスト（Wayland → X11）**: **P3f-1 Wayland**: `libwayland-client` を FFM で呼び、プロトコルは XML から生成する。枠は自分で描き（GNOME はサーバー側の枠を出さない）、移動とリサイズは `xdg_toplevel.move/resize`、ポップアップは `xdg_popup` + `xdg_positioner`（親からの相対）、`fractional-scale-v1` + `viewporter`・`text-input-v3`（IME）・`cursor-shape-v1`・`wl_data_device`・`frame` のコールバックで描く拍を合わせる。キーは `libxkbcommon`、連打は `Clock`。表示は `wl_shm`・EGL・Vulkan。コンポジタごとに使えるプロトコルを起動時に調べる。CI は Weston のヘッドレス。同じ段で Linux の GLFW の経路を外す。**P3f-2 X11**（Xfce・MATE・古い LTS。XIM・`_NET_WM_MOVERESIZE`）。P3f-2 まで、X11 だけの環境では窓を開かない（PNG の出力と埋め込みは使える） | 25 | P3e | 未着手 |
| **P3g** | **macOS のホスト（Cocoa）**: Objective-C の実行時（`objc_msgSend` ほか）を FFM で呼ぶ。呼び出しの部品は「使うメソッドと型の一覧」から生成し（型の誤りは例外でなく JVM ごと落ちるので手書きしない）、全ての呼び出しを macOS の CI で一度は通す。`NSWindow`/`NSView`・`NSTextInputClient`（IME）・retain/release と自動解放のまとまり・主スレッドの制約（jpackage の起動器で `-XstartOnFirstThread`）。表示はソフトウェア描画（`CALayer`）。**GL は飛ばす**（非推奨で 4.1 止まり・macOS だけのコンテキストの作り方が増えて後で捨てるため）。対象は Apple Silicon（macOS 26 が Intel の最後の版。Intel はできる範囲）。同じ段で `:core-glfw` を削除する（macOS の GPU 描画は P3h まで「一時的に無い機能」） | 25 | P3e | 未着手 |
| **P3h** | **Metal のバックエンド（`:core-metal`）**: RHI を対応させる（`GpuDevice` → `MTLDevice`・`GpuBuffer` → 共有メモリの `MTLBuffer`〔`contents()` へ直接書くのでコピーなし〕・`PipelineDesc` → `MTLRenderPipelineState` + `MTLDepthStencilState`・`GpuPass` → レンダーパスの記述・`GpuFrame` → 3 面のリングと `MTLSharedEvent`・表示は `CAMetalLayer`〔120Hz は `CADisplayLink`〕）。シェーダは 3D を P6b の中間表現から MSL で生成し、2D は当面ビルド時に SPIRV-Cross で変換した MSL を資源で持つ（実行時は OS の `newLibraryWithSource`。ネイティブのコンパイラを同梱しない）。命令の記録はどのスレッドからでもできる（P2 に有利）。動画は `CVMetalTextureCache` でコピーなし | 25 | P3g | 未着手 |

**P3b の段と OS のデコーダ**:

| 段 | 内容 | Java |
| :--- | :--- | :---: |
| **P3b-1** | 純 Java の部分: MP4（ISO BMFF）・WebM（Matroska）の分離器、MP3・Opus の復号器、音と映像の同期、HTTP の範囲取得（既存の `VideoStreamServer`・`SafeHttpClient` を使う。YouTube の yt-dlp は今のまま）、NV12 → RGB の変換（GPU はシェーダ、ソフトウェア描画は Java） | 21 |
| **P3b-2** | OS のデコーダ（下の表）と口 `NativeCall`（関数のポインタを呼ぶ・メモリを読み書きする・コールバックを受ける）。各 OS の呼び方は Java 21 で 1 度だけ書き、口の実装は JNA 版（`:mod`。Minecraft 1.21.1 が同梱する JNA を使う）と FFM 版（単体アプリ）の 2 つだけ。最初にスパイクで、1080p30 の H.264 と VP9 を各 OS で 1 フレームの時間内に解けることを確かめる。同じ段で `lunex_video.dll`・`NativeVideoApi` を削除し、`:core` から JNA を外す | 21 / 25 |
| **P3b-3** | ハードウェアの復号とコピーなしの受け渡し: Media Foundation に D3D11 を渡す（まず読み戻し、次に `WGL_NV_DX_interop2`）、macOS は IOSurface → GL / Metal のテクスチャ。P6 の `VideoTexture` もここを使う | 25 中心 |

| OS | 仕組み（使う層） | MP4（H.264/AAC） | WebM（VP9/Opus） | 注意 |
| :--- | :--- | :---: | :---: | :--- |
| Windows | Media Foundation（デコーダ MFT `IMFTransform`） | ◎（標準） | ○（VP9 は「VP9 Video Extensions」。大抵入っている。Opus は純 Java） | N エディションは Media Feature Pack が要る。HEVC の拡張は有料なので当てにしない |
| macOS | VideoToolbox（`VTDecompressionSession`。音は AudioToolbox） | ◎（ハードウェア） | △（VP9 は macOS 11 以降の `VTRegisterSupplementalVideoDecoderIfAvailable`。要確認） | AVFoundation（`AVPlayer`）は WebM を再生できないので使わない |
| Linux | GStreamer（`appsrc` → `decodebin` → `appsink`） | △（`gst-libav` か openh264 が要る） | ○（`gst-plugins-good`） | ハードウェアは VA-API のプラグインがあれば自動 |

OS にデコーダが無い形式は「この環境では再生できない」と表示して止める（純 Java の H.264・VP9 の復号器は作らない）。

**P3 の完了ゲート**:
- エンジンのモジュール（`:mod` を除く）で `org.lwjgl`・`com.sun.jna` の import が `0`（`ArchitectureHealthTest`）・同梱のネイティブ `0`・`:core-glfw` 削除済み。
- MOD が使うモジュールは `--release 21` でコンパイルする（Java 22 以降の API を使えば落ちる）。
- 窓 {Win32, Wayland, X11, Cocoa} × 描画 {SOFTWARE, GL, VULKAN, METAL（macOS）} の直交のテストが合格・144Hz で起動。
- MP4 / WebM / MP3 / GIF が Windows・macOS・Linux（OS のデコーダがある環境）で再生でき、音と映像のずれが 1 フレーム以内。
- 性能: GL の 1 フレーム（交互 A/B。旧実装との比は記録）・初回表示の文字の準備時間・コールド起動（P1b と合わせて `≤ 200 ms`）・1080p30 の動画の CPU 時間がそれぞれの上限内。
- 旧 P3 の AWT 窓ホストは撤回した。Swing / JavaFX のアプリへ埋め込む部品（AWT）は P5d で検討する。

#### Track P4

- **Track P4: リポジトリの Core 主体化・分割・独立名への改名**: リポジトリのルートは NeoForge のプロジェクトで、名前も MOD 由来である。
  - **P4-1（M2c の完了の後）**: 同じリポジトリの中で構成を変える。
    1. MOD を `mod/` サブプロジェクトへ移し、ルートを NeoForge 非依存の素の Gradle にする。
    2. MOD のテストが借りる `:core` のテスト出力を `testFixtures` にする。
    3. ルートの文書の主役を Core にする。
    - ゲート: 全ゲート緑・`:core` 系だけのビルドで NeoForge の設定処理 `0`。
    - **結果（2026-11-27 完了）**: ①ルートの `build.gradle` はプラグインなし（`wrapper` の設定だけ）。MOD は `mod/`（`build.gradle`・`gradle.properties`〔Minecraft・NeoForge・MOD の値〕・`src`・`run/`）。エンジンの座標は `engine_group_id`/`engine_version`/`engine_artifact_prefix`（ルートの `gradle.properties`）。`org.gradle.configureondemand=true` で、`:core:test` は root と `:core` 系だけを評価し `:mod` を評価しない（CI の build に検査の段を足した） ②`:mod` は `testFixtures(project(':core'))` だけを借りる（`GoldenFiles`・`TestBoxes`・`WptHarness`/`WptBehaviorTest`・`TransformParseTestBridge` と WPT の既知差分の台帳。公開物には載せない）。テストの資源はコアのもの（WPT・忠実度・書体・パーサのゴールデン・`machine_ui.html`）を `core/src/test/resources`、MOD だけのもの（表示リストのゴールデン・Chrome の矩形・サーバーの基準値）を `mod/src/test/resources` へ分けた。全テストの作業ディレクトリはリポジトリ直下 ③README・ARCHITECTURE・CONTRIBUTING・docs を `:mod` の構成に合わせ、`mod/README.md` を置いた。続けて文書を置き場で分けた: ルートは README・CONTRIBUTING・CLAUDE・ライセンスだけ、エンジンの文書（ARCHITECTURE・BENCHMARK・FIDELITY）は `docs/`、計画書と ARCHIVE は `docs/plan/`（旧 `core/archive/` は `docs/plan/archive/`、MOD 時代の旧 `REFACTORING_PLAN.md` は `archive/00`）、MOD の文書（導入・Headless State・ハイブリッド・コマンド・`COMMAND_GUIDE`・`TEST_SPEC_*`）は `mod/docs/`。公開物の出力先は `build/repo/`。`DocConsistencyTest` は `docs/`・`mod/docs/` の全体（`docs/plan/archive/` は原文の保存なので除く）のリンクを見る
    - **P4-2 へ持ち越す結合**（別リポジトリにすると切れるもの。今は同じリポジトリなので動く）: コアのテスト・計測が MOD の同梱の文書（`mod/src/main/resources/data/enderframelib/enderframe_ui`。showcase・voidwave ほか）を読む（約 20 ファイル。文書をコアの資料へ移す）・`ArchitectureHealthTest`/`PortabilityRatchetTest` などが MOD のソースを走査する（MOD 側の検査へ移す）・`WptManifestTest` が MOD の uievents の移植のソースを読む（M4 で uievents を `:core` へ）・MOD の `PacketBandwidthTest` が `core/src/test/resources/benchmark/machine_ui.html` を読む
  - **P4-2（M4 完了・API 確定の後。M6 と同時）**: エンジンを独立名へ改名する（パッケージ・`groupId`）。
    1. `git filter-repo` で履歴付きの別リポジトリへ切り出す。
    2. MOD は公開版に依存させる（同時に触るときは `includeBuild`）。
    3. CI はエンジン側へ移す。
    - ゲート: エンジン側のソースに `enderframelib` の名前 `0`。
    - 名前の候補（2026-11-25 の Web 検索。商標と GitHub の Organization 名は未確認なので、確定の前に再確認する）: **Weft**（候補）・**Quire**（候補）・**Facet**（次点）。Vellum（Minecraft 用の HTML の GUI ライブラリ「Vellum GUI」と重なる）と Tessera（ConsenSys の Java 製品と重なる）は不可。

### 3.3 群 C. 汎用ツールキット化

#### Track P5

- **Track P5: JavaFX と並ぶ汎用 UI 基盤へ**（前提: P5f の測定基盤は随時。それ以外は M6 完了。P5d は P3 も）。順序は P5a → P5b → P5c → P5d で、P5e・P5f は並行して進める。
  - **P5a アクセシビリティ**: DOM と ARIA から、役割・名前・状態のアクセシビリティツリーを不変の木として作る。それを OS の支援技術（Windows UI Automation・macOS NSAccessibility・Linux AT-SPI）へ出す。
  - **P5b Java 側の状態バインディングと部品モデル**: 型付きスタイル API（`01edc0dc`）を延長し、Java の状態と画面を結ぶ。
  - **P5c 高機能部品**: 仮想化した一覧・表・木（M3b のチャンク単位のカリングの上に作る）・日付選択・分割ペイン。ゲート: 10 万行の表のスクロールで 144Hz。
  - **P5d OS 統合**（P3 の窓のホストの上）: ネイティブのメニュー・ファイルダイアログ・ドラッグ＆ドロップ（クリップボードの形式と複数窓は P3d〜P3g で入れる）。Swing / JavaFX のアプリへ埋め込む部品（AWT）もここで検討する。
  - **P5e 複雑な文字体系**: アラビア文字・インド系文字の字形処理とフォールバックの到達度を棚卸しして埋める。ゲート: 該当する WPT と参照画像テストをラチェットに足す。
  - **P5f 比較測定と公開物**: 同じ画面を JavaFX と Compose Desktop でも作り、起動・保持ヒープ・フレーム時間を交互 A/B で比べる。比較の主張と公開は M6 の後に行う。
  - **完了ゲート**: 業務アプリ相当の見本（フォーム・表・木・メニュー・ダイアログ）が Windows / macOS / Linux でスクリーンリーダーから操作でき、JavaFX・Compose との交互 A/B の比較表を公開している。

### 3.4 群 D. 空間 UI（3D）

#### Track P6: Three.js 本家レベルの 3D と HTML の融合（2026-11-28 新設）

**現状（2026-11-28 の実コード）**:
- 公開の `api.scene3d.Scene3DController` は int のノード番号で命令する形（形は箱と球・glTF、追加ライト 8 灯まで、Bloom、オービット）。
- Three.js 風の API（`Object3D`・`Mesh`・`Material` ほか）は `core/demo/java/.../demo/three` だけにあり配布物に入らない（S5 で降格）。`StandaloneView` の内部に依存し、`System.nanoTime` を読み（時刻の注入の規則に反する）、hover を 16 ms の `setInterval` でポーリングする。
- 土台: オフヒープ SoA の `Scene3DArena`・インスタンスのバッチ・GGX の PBR・BVH のレイキャスト・Bloom・ソフトウェアのラスタライザ（`SoftwareScene3DRasterizer`）と RHI のレンダラ（`Scene3DRenderer`）。一致は許容差つき（`Scene3DSoftwareParityTest`: ソフトウェアと GL は差の平均 `< 0.5`・差が 2 を超える画素 `< 0.5%`・最大差 `≤ 24`、GL と Vulkan は最大差 `≤ 4`）。
- 穴: `<ui-panel-3d>` はタグの登録だけ・glTF のアニメーション・スキン・モーフが無い・テクスチャはベースカラーだけ・照明を表示空間（sRGB）で計算している・正投影・影・IBL・法線マップ・霧・線・点が無い・変更の検知がノードごとの `FLAG_DIRTY` と `changeCount` の合算の 2 本立て。

**設計の原則**:
1. **世界は 1 つ**: Java の API・HTML のタグ・glTF が同じ `Scene3DWorld` に書く。`Object3D` は番号と世代のハンドルで、別の木を持たない。`Scene3DController` と `demo.three` は P6a で削除する（ルール 7）。
2. **変更の入口は 1 つ**: 3D の変更はビューの `ChangeLedger` に「どの世界が変わったか」として載せる。ノードごとの `FLAG_DIRTY` と `changeCount` の合算は廃止し、変換は位相順にまとめて計算し直す（費用は先に測る）。
3. **時刻は注入**: アニメーションと `animate` はビューの `Clock` とフレームの拍で回す。ヘッドレスでも同じ絵が出る。
4. **イベントは scene の当たり判定に統合**: 3D の物体をイベントの対象にし、`SceneHitTest` から pointerenter / leave / click を届ける（泡立ちは 物体 → `<scene-3d>` → DOM）。ポーリングは廃止する。
5. **シェーダは中間表現から生成**: 材質とライトはノードの中間表現（Three.js の TSL に相当）で書き、GL は GLSL、Vulkan は Java から直接出す SPIR-V（ネイティブのコンパイラが要らない。利用者のノードの材質も実行時に通る）、Metal は MSL、ソフトウェアは中間表現の解釈器へ生成する。機能の数 × バックエンドの数を手書きしない。
6. **全バックエンドで一致（ソフトウェアは参照レンダラ）**: ソフトウェアは機能が同じで速さを問わない参照レンダラにし、3D の新機能は CPU・GL・Vulkan（P3h の後は Metal も）に同じ段で入れる。一致は機能ごとの許容差で見る。ハードウェアに任せると食い違う所（IBL のミップの選び方・PCF のカーネル・影の深度の補正・トーンマップ・sRGB の符号化）は GPU の側でも明示的な同じ計算にし、MSAA の差は縁の画素だけに許容差を置く。ソフトウェアの結果は決まった値にする（`StrictMath` か自前の多項式・タイルの並列は並び順に依らない）。CI では GPU のテストが中断されるので、CI で走る 3D の検査はソフトウェアだけになる（全機能を CI で守るための条件）。
7. **正しさの物差しは Three.js 公式 example（3D 版の WPT）**: 公式 example から 50〜100 本を選び、本物の Three.js（ヘッドレスの Chrome。`core/src/bench-crossengine` の取り回しを使う）で描いた画像を参照画像にする。同じシーンを Java の API で組み、許容差つきで比べ、合格数をラチェットで固定する。example のモデル・画像はライセンスを確かめてから取り込む。

**段**:

| 段 | 内容 | 前提 | 状態 |
| :--- | :--- | :--- | :--- |
| **P6a** | **公開 API の土台**: `api.scene3d` に Three.js と同じ名前の object model（`Scene`・`Object3D`・`Group`・`Mesh`・`PerspectiveCamera`・`OrthographicCamera`・各種ライト・`BufferGeometry`/`BufferAttribute`・`Material`・`Texture`・`Raycaster`・Vector / Quaternion / Euler / Matrix / Color〔中身は JOML〕）。原則 1〜4 を入れる。定常のフレームの確保 0 B。`Scene3DController`・`demo.three` を削除し、`ApiSignatureTest` を更新する | M4 完了 | 未着手 |
| **P6b** | **シェーダの中間表現と生成器**（GLSL・SPIR-V・ソフトウェアの解釈器）・3D 版の WPT の仕組みと最初の example の一覧と目標の合格数・線形の色空間（HDR）と出力の符号化（3D のゴールデンを一度取り直す） | P6a | 未着手 |
| **P6c** | **材質と環境**: Basic・Lambert・Phong・Standard・Physical（clearcoat・sheen・transmission・iridescence・anisotropy）・Toon・Matcap・Normal・Depth、glTF の PBR のテクスチャ全部（法線・MR・AO・発光）、環境マップと PMREM・HDR・霧・トーンマップ 6 種（Linear・Reinhard・Cineon・ACES・AgX・Neutral） | P6b | 未着手 |
| **P6d** | **ライトと影**: Ambient・Hemisphere・Directional・Point・Spot・RectArea（LTC）・LightProbe、影（PCF・PCFSoft・VSM、点光源はキューブ）・CSM | P6c | 未着手 |
| **P6e** | **形と物体**: 全ての基本形（Box〜TorusKnot・Lathe・Extrude・Shape・Tube・多面体・Edges・Wireframe）・複数の材質のグループ・InstancedMesh（インスタンスごとの色）・BatchedMesh・Line / LineSegments / LineLoop・Points・Sprite・LOD・描画先（複数の描画先・`DepthTexture`・CubeCamera）・切り取り面 | P6b | 未着手 |
| **P6f** | **アニメーションと読み込み**: AnimationMixer・Action・クロスフェード・スケルトン・モーフ、glTF の KHR の拡張・Draco（純 Java の解凍器）・meshopt・KTX2/Basis（純 Java の変換器）・外部 URI（エンジンの取得）、OBJ/MTL・PLY・STL・HDR/EXR | P6e | 未着手 |
| **P6g** | **後処理と操作**: EffectComposer 相当（Bloom・SSAO/GTAO・FXAA/SMAA/TAA・Outline・Bokeh・OutputPass）・Orbit・Trackball・Fly・Transform・Drag の操作・Raycaster の全機能（layers・Line / Points のしきい値） | P6c | 未着手 |
| **P6h** | **本家を超える部分**: `<ui-panel-3d>`（DOM の部分木の `DisplayScene` を RHI のテクスチャに描き、深度の前後を保って 3D に置く。3D のレイ → UV → パネル内の座標 → `SceneHitTest` で、文字入力と IME まで効く。描き直す範囲は `SceneDamage`）・`VideoTexture`（P3b の OS のデコーダの出力をコピーなしで）・`CanvasTexture`（Canvas 2D）・ノードの材質の公開 API | M3b・M4・P3b-3 | 未着手 |

**P6 の完了ゲート**: 選んだ Three.js 公式 example の合格数が目標以上（P6b で決める）・3D の全機能が CPU / GL / Vulkan（Metal）で機能ごとの許容差内・HTML のパネルのフォームを 3D の中で操作できる（全バックエンド）・性能は GL で 1 万インスタンスを 144Hz・10 万三角形のピック `≤ 0.1 ms`・3D の定常フレームの確保 0 B（Three.js〔Chrome〕との比は記録だけ）。

---

## 4. KPI

時間の主張は交互 A/B の中央値だけ（絶対値はセッションで ±40% ずれる）。「現在」の列は作業のたびに上書きする。本流のフレーム時間とヒープはリファクタリングの計画書の §4 にある。

| 軸 | 指標 / シナリオ | 着手前 | 現在（2026-11-28） | 目標（段） |
| :--- | :--- | :---: | :---: | :---: |
| **性能** | コールド起動（`showcase`） | `546 ms`（書体なし。2026-11-24） | 書体の読み込み `243 → 24 ms`（P1a）・JDK 25 + AOT `540 → 228 ms`（P1b） | **`≤ 200 ms`**（P1b） |
| **性能** | `voidwave.html open` | `9.15 ms`（2026-11-24） | リファクタリングの計画書 §4 | **`≤ 5.5 ms`**（本流 M3b〜M5 + P2） |
| **構造** | リポジトリの構成（`:core` 系だけのタスクで設定する NeoForge のプロジェクト） | ルートが NeoForge の MOD | **0**（P4-1。`mod/`〔`:mod`〕分離） | `0`（P4-1。**達成**）→ P4-2 |
| **構造** | `org.lwjgl` / `com.sun.jna` を import するファイル（`:mod` を除くエンジンのモジュール） | — | `16` / `2`（`:core` 7 / 2・`:core-gl` 3・`:core-vulkan` 5・`:core-glfw` 1） | **`0` / `0`**（P3） |
| **構造** | 同梱のネイティブ | — | LWJGL（FreeType・GLFW・GL・Vulkan）・`lunex_video.dll` | **`0`**（P3） |
| **機能** | 動画・音声の形式 × OS | — | `lunex_video.dll` の形式を Windows x64 だけ（GIF は純 Java で全 OS） | MP4 / WebM / MP3 / GIF を Windows・macOS・Linux（P3b） |
| **機能** | 窓のホスト | — | GLFW（`:core-glfw`）だけ | Win32・Wayland・X11・Cocoa（P3d〜P3g） |
| **正しさ** | 3D: Three.js 公式 example の参照画像との一致 | — | 未選定 | 目標は P6b で決める（P6） |
| **正しさ** | 3D: CPU / GL / Vulkan の一致 | — | 許容差つき（`Scene3DSoftwareParityTest`。基本の PBR だけ） | 全機能を機能ごとの許容差で（P6） |

---

## 5. 本書に固有の運用ルール（リファクタリングの計画書 §5 に足すもの）

1. **Java の版**: MOD が使うモジュールは `--release 21` を保つ。Java 22 以降の API は単体アプリのモジュールだけで使う。
2. **OS を呼ぶ口は、処理 1 つと薄い実装 2 つだけ**: 各 OS の呼び方（GL・Media Foundation・VideoToolbox・GStreamer）は Java 21 で 1 度だけ書き、口（`GlApi`・`NativeCall`）の実装は Minecraft の同梱物を使う版（`:mod`）と FFM 版（単体アプリ）の 2 つに限る。3 つ目の実装・切り替えのフラグ・フォールバックを足さない。
3. **呼び出しの部品は生成物**: GL・Vulkan・Wayland・Objective-C の呼び出しの部品は一覧（Khronos の XML・プロトコルの XML・メソッドと型の一覧）から生成し、手で直さない（`scripts/GenStyleGroups.java` の生成物と同じ扱い）。
4. **OS ごとの切り替えと旧経路の削除は同じ段で行う**: その OS で新しいホストに切り替えたら、同じ段でその OS の GLFW の経路を外す。間に開く穴は §0 の「一時的に無い機能」に書く。
5. **3D の機能は全てのバックエンドに同じ段で入れる**: GPU だけの機能を作らない。
6. **重い処理は先にスパイク**: 書体（P3a）と動画（P3b-2）は、先にスパイクで絶対の上限を決めてから着手する。旧実装（FreeType・GLFW・`lunex_video.dll`）との比は記録だけにする。

---

## 6. 関係する文書

- リファクタリング（本流 Phase M0〜M6。Track P の段の前提）: [`ENGINE_REARCHITECTURE_PLAN.md`](ENGINE_REARCHITECTURE_PLAN.md)（§0 現在地・§3 優先順位表・§4 KPI・§5 運用ルール）。
- 経緯と持ち越す知見: [`ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md`](ENGINE_REARCHITECTURE_PLAN_ARCHIVE.md)（付録 5.A。本書の経緯もここへ 1 行で積む）。
- 文書の目次: [`../README.md`](../README.md)。
