# EnderFrameLib 動画・メディア系テスト仕様書 (Test Specification)

本ドキュメントは、EnderFrameLib における動画（`<video>`）、音声（OpenAL）、Web画像（`<img>`）、Minecraft独自メディアスキーム、およびセキュリティガードレール機能の検証項目とテスト手順を定めた仕様書です。

---

## 1. テスト環境・前提条件

| 項目 | 要件 |
|---|---|
| **Minecraft / NeoForge** | 1.21.1 / NeoForge 21.1.248+ |
| **Java バージョン** | Java 21 (x86_64) |
| **ネイティブ動画デコーダ** | Windows x86_64（同梱 `lunex_video.dll` による再生。他OSでは静止画/空描画フォールバック） |
| **オーディオエンジン** | OpenAL（LWJGL OpenAL 1.0 / 1.1） |
| **テスト用サイト/リソース** | 1. ゲーム内UI: `enderframelib:media_test`<br>2. スタンドアロンサイト: `test-site/index.html` |
| **設定項目 (Config)** | `Media` カテゴリ:<br>&bull; `Allow External Web Media` (既定: false / `mod:`・`mc:` などのローカルメディアのみ)<br>&bull; `Allowed Media Domains` (既定: 空 = 公開ホストなら許可)<br>&bull; `Block Private Network Media` (既定: true)<br>&bull; `Max Video Stream (MB)` (既定: 512 / 1回のリレー応答で転送できる上限)<br>&bull; `Enable YouTube Playback (yt-dlp)` (既定: false / 同意ゲート。外部メディアと同時に有効化すると公式 GitHub リリースから SHA-256 検証付きで自動配置) |

---

## 2. 指定テストメディア一覧

| メディア種別 | 指定URL | 内部変換 / 処理仕様 |
|---|---|---|
| **ユーザー指定動画 (Google Drive)** | `https://drive.google.com/file/d/1A3V6ozYlT82Trl-cJcM2_OFsT5RRpN0c/view?usp=drive_link` | `convertToDirectLink` により `https://drive.google.com/uc?export=download&id=1A3V6ozYlT82Trl-cJcM2_OFsT5RRpN0c` に自動変換後、Java 側で 302 リダイレクトを各ホップ検証しながら追跡する。検証済み接続からループバック・リレー (`http://127.0.0.1:<port>/v/<token>`) へストリーミングし、`lunex_video.dll` にはリレー URL だけを渡す。動画全体をディスクへ保存しない |
| **YouTube 動画** | `https://www.youtube.com/watch?v=aqz-KE-bpKQ` (Big Buck Bunny 4K 60fps) | `allowExternalMedia` と `allowYtDlp` が true の場合、公式 GitHub リリースから `<ゲームフォルダ>/enderframelib/` へ `yt-dlp` を自動配置（`SHA2-256SUMS` と照合）して実行し、ストリームURLを抽出。既に手動配置されたバイナリがあればそれを使用する。抽出 URL も Java 側で検証し、ループバック・リレー経由で再生する（一時ファイル化しない） |
| **ユーザー指定画像 (Google Drive)** | `https://drive.google.com/file/d/1pOYknV2o1R5pPi7MH0BnGxNijzWwn6O1/view?usp=sharing` | `convertToDirectLink` により直リンク化後、非同期ダウンロードされ `NativeImage` & `DynamicTexture` としてキャッシュ |

---

## 3. テスト実行手順

### 手順 A: ゲーム内GUIとしてテストスイートを開く
1. `./gradlew runClient` で開発クライアントを起動、または通常プレイ環境で起動。
2. ワールドに入り、チャット欄で以下のコマンドを実行：
   ```mcfunction
   /enderframe open @s enderframelib:media_test 1280 720
   ```
3. 画面上に「**EnderFrame Media Lab**」が表示され、最上部に「★ User Specified Media Test」として指定動画・YouTube動画・指定画像が表示されます。

> [!TIP]
> **YouTube動画の再生をテストする場合:**
> YouTube URL は外部プロセス（yt-dlp）の実行を伴うため、既定で同意ゲート（`allowYtDlp = false`）が有効になっています。
> 設定画面（Mods &rarr; EnderFrameLib &rarr; Config、または `/enderframe config`）の「**Media**」カテゴリで
> 「**Allow External Web Media**」と「**Enable YouTube Playback (yt-dlp)**」の両方を `true` にしてください。
> 両方が有効になると、公式 GitHub リリースから `yt-dlp.exe` が自動配置されます（初回のみ、SHA-256 検証付き）。
> `Allowed Media Domains` を空以外にする場合は、`youtube.com`（または `youtu.be`）と解決後の `googlevideo.com` を含めてください。
> GitHub は公式バイナリの取得専用の固定ホストを使うため、この許可ドメイン一覧へ追加する必要はありません。

### 手順 B: HUDオーバーレイとしてテスト
```mcfunction
/enderframe hud @s enderframelib:media_test
```
（解除時: `/enderframe hud @s clear`）

### 手順 C: スタンドアロンサイト（ブラウザ）で事前確認
```bash
cd test-site
python server.py
# ブラウザで http://localhost:8080 を開いて動作確認
```

---

## 4. テスト項目一覧

### カテゴリ 0: ユーザー指定メディアの検証 (U-01 〜 U-03)

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **U-01** | 指定 Google Drive 動画の再生 | `media_test.html` の先頭カードで指定動画（`1A3V6ozYlT82Trl-cJcM2_OFsT5RRpN0c`）を再生 | 共有リンクから直接ダウンロードURLへの変換が成功し、全編ダウンロードを待たずにループバック・リレー経由で再生が始まり、黒画面にならず音声が出力されること。`enderframelib/cache/video` に動画ファイルが作られないこと |
| **U-02** | YouTube 動画ストリーミング | `allowExternalMedia = true` と `allowYtDlp = true` に設定の上、YouTube動画カードを再生 | `enderframelib/yt-dlp.exe` が無ければ公式 GitHub リリースから自動配置され、`SHA2-256SUMS` と一致したバイナリだけが実行されること。`yt-dlp` により動画ストリームが正常に解決され、ループバック・リレー経由でネイティブデコーダがスムーズに再生すること。ディスクへ全編保存されないこと |
| **U-03** | 指定 Google Drive 画像の読み込み | 指定画像（`1pOYknV2o1R5pPi7MH0BnGxNijzWwn6O1`）を `<img>` で表示 | 直リンク変換後に非同期ダウンロードされ、画像本来の縦横比（Intrinsic Size）を保って鮮明に描画されること |

---

### カテゴリ 1: `<video>` 動画レンダリング & デコード

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **V-01** | 基本MP4動画の再生 | MP4動画を `<video>` で読み込む | 黒画面にならず、スムーズに映像が再生開始され、終端で停止すること。`setLoop(true)` で終端から先頭へループし、終端で `play()` すると先頭から再開すること |
| **V-02** | 解像度追従 | 480p, 720p, 1080p の動画を再生 | 解像度に関わらず適切なテクスチャサイズ（`TextureUtil.prepareImage`）でGPU転送されること |
| **V-03** | `object-fit: fill` | 幅・高さが不揃いな親ボックスに配置 | アスペクト比を無視し、ボックス全体一杯に引き伸ばされて表示されること |
| **V-04** | `object-fit: contain` | 16:9動画を正方形ボックスに配置 | アスペクト比が維持され、上下または左右に黒帯（レターボックス）が生じること |
| **V-05** | `object-fit: cover` | 16:9動画を縦長/コンパクト枠に配置 | 比率を維持したままボックスを隙間なく満たし、中央を基準に外側がクリップされること |
| **V-06** | 複数動画の同時再生 | 画面内に2つ以上の `<video>` を配置 | 同時にデコード・テクスチャ更新が行われ、クラッシュや顕著なFPS低下がないこと |
| **V-07** | 無効URLのフォールバック | 存在しないURLや404の動画URLを指定 | `isFailed=true` となり、ゲームがクラッシュせず安全に黒/空描画となること |
| **V-08** | Range シーク | 再生中に `seekTo(秒)` または Lua の `seek` を実行 | リレーが上流へ `Range` を転送し、206 応答で指定位置へ移動すること。動画全体を再取得しないこと |
| **V-09** | リレー停止後の後始末 | 動画を閉じた後に同じトークン URL へアクセス | `404 Not Found` となり、上流への追加取得が発生しないこと |

---

### カテゴリ 2: オーディオ & 音響空間 (OpenAL)

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **A-01** | 2Dスクリーン音声 (HUD/GUI) | GUI画面またはHUDで音声付き動画を再生 | `AL_SOURCE_RELATIVE = 1` となり、プレイヤーの視線や座標に関わらず左右均等のクリアな音で聴こえること |
| **A-02** | 3D空間音響 (ブロック画面) | `WebScreenBlockEntity` に動画画面を設置し、プレイヤーを移動させる | `AL_SOURCE_RELATIVE = 0` となり、画面から離れると減衰し、左右に向きを変えるとパンニング（左右定位）すること |
| **A-03** | 外部スピーカー連動 | `getLinkedSpeakers()` でスピーカー座標を指定 | 画面本体だけでなく、指定した各スピーカー位置から分散して音声が出力されること |
| **A-04** | 音声なし動画の処理 | 音声トラックの存在しない動画を再生 | `hasAudio == 0` が正常検知され、OpenALバッファ確保を行わずに映像のみ再生されること |
| **A-05** | 音声フォーマット自動取得 | 44.1kHz / 48kHz、Mono / Stereo の各音声を再生 | `AL_FORMAT_MONO16` または `AL_FORMAT_STEREO16` が適切に選択され、音割れやピッチ異常がないこと |

---

### カテゴリ 3: 動画再生制御 API

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **C-01** | 一時停止 / 再開 | `VideoTexture.setPaused(true / false)` を呼ぶ | 映像フレーム更新および OpenAL の `alSourcePause` / `alSourcePlay` が正しく切り替わること |
| **C-02** | 再生速度変更 (ピッチ連動) | `setPlaybackSpeed(0.5F / 1.5F / 2.0F)` を実行 | 動画デコード速度と OpenAL の `AL_PITCH` が連動して変化すること |
| **C-03** | シーク操作 | `seekTo(seconds)` を実行 | 指定した秒数へ映像が即座にジャンプし、OpenALの残留バッファが破棄されて音がズレないこと |
| **C-04** | 音量制御 | `setVolume(0.0F ~ 1.0F)` を実行 | OpenAL の `AL_GAIN` に値が反映され、音量が滑らかに変化すること |
| **C-05** | Lua `play` / `pause` | 要素 API `el:play()` / `el:pause()` を実行 | それぞれ再開・一時停止し、`isPaused()` の戻り値が一致すること |
| **C-06** | Lua `togglePause` | `el:togglePause()` を連続実行 | 一時停止と再開が交互に切り替わり、映像・音声が追従すること |
| **C-07** | Lua `stop` | 再生中に `el:stop()` を実行 | 一時停止して再生位置が 0 秒へ戻り、再度 `play()` で先頭から再生できること |
| **C-08** | Lua `setVolume` / `setMuted` | `el:setVolume(0.5)`、`el:setMuted(true/false)` を実行 | 音量が 0.5 になり、ミュート中は `AL_GAIN` が 0、解除で元の音量へ戻ること |
| **C-09** | Lua `setLoop` | `el:setLoop(true)` / `el:setLoop(false)` を実行 | 終端でループする / 停止するが切り替わること |
| **C-10** | 再生位置・長さ取得 | `el:getCurrentTime()` / `el:getDuration()` を実行 | 現在位置と動画長が秒単位の数値で返り、`seek` 後に現在位置が更新されること |
| **C-11** | ハンドル確定前のコマンド | 動画ロード直後に `pause` / `setLoop` / `setVolume` を実行 | ネイティブハンドル確定後に状態が再適用され、コマンドが失われないこと |
| **C-12** | Lua 要素 API の状態取得 | `isPaused` / `isMuted` / `isLooping` / `getVolume` を実行 | 直前の設定値と一致する値を返すこと |
| **C-13** | 終端からの再生 | 動画を最後まで再生した後に `el:play()` を実行 | 終端では `isPaused()` が true を返し、`play()` で先頭（0秒）へ戻って再生が再開されること |

---

### カテゴリ 4: CSSスタイリング & レンダリング合成

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **S-01** | 角丸クリッピング | `<video>` に `border-radius: 16px` を適用 | 四隅が丸くクリップされ、角の外側に動画がはみ出さないこと |
| **S-02** | 正円形マスク | `border-radius: 50%` かつ正方形サイズを指定 | 動画が綺麗な円形に切り抜かれて描画されること |
| **S-03** | 不透明度ブレンド | `opacity: 0.55` を指定 | 背景と半透明合成（アルファブレンド）されて動画が再生されること |
| **S-04** | 枠線と光彩の合成 | `border: 2px solid #818cf8; box-shadow: ...` を指定 | 動画の周囲に枠線およびシャドウが重なって正常描画されること |
| **S-05** | 変形 (Transform) | `transform: scale(0.9) rotate(-3deg)` を指定 | 傾き・縮小された状態で動画フレームが追従描画されること |

---

### カテゴリ 5: `<img>` Web画像 & キャッシュシステム

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **I-01** | 透過PNG画像の取得 | アルファチャンネル付きPNGのURLを指定 | 非同期ダウンロード完了後、背景が透過して正しく描画されること |
| **I-02** | 高解像度JPEG画像の取得 | 高解像度の写真画像URLを指定 | 非同期取得後、テクスチャ登録され鮮明に表示されること |
| **I-03** | 固有サイズ自動解決 | `width` / `height` を指定せず `<img>` を配置 | ロード後に画像本来のピクセル比率（Intrinsic Size）が計算され、レイアウトに反映されること |
| **I-04** | Google Drive 共有リンク変換 | `drive.google.com/file/d/ID/view` を指定 | `convertToDirectLink` により直接ダウンロードURLに変換され取得できること |
| **I-05** | HTTPリダイレクト追跡 | 301/302/303 リダイレクトされる画像URLを指定 | リダイレクト先URLが `isUrlSafe` で検証され、正常に取得されること |
| **I-06** | LRU画像キャッシュ | 256枚以上の画像URLを順次読み込む | 256件を超えた古い画像テクスチャが `release()` され、メモリリークしないこと |

---

### カテゴリ 6: Minecraft独自メディアスキーム

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **M-01** | アイテムアイコン | `<img src="item:minecraft:netherite_sword">` | ゲーム内のネザライトの剣のアイコンが正しく描画されること |
| **M-02** | 流体テクスチャ | `<img src="fluid:minecraft:lava">` | 溶岩のアニメーションテクスチャが正しく描画されること |
| **M-03** | プレイヤースキン頭部 | `<img src="player:@self">` | 自身のスキンの顔＋アウターレイヤー（帽子）が立体的に描画されること |
| **M-04** | エンティティモデル | `<img src="entity:minecraft:creeper">` | クリーパーの3Dモデルが指定枠内にレンダリングされること |
| **M-05** | ステータス効果 | `<img src="effect:minecraft:speed">` | 移動速度上昇のエフェクトアイコンが正しく描画されること |

---

### カテゴリ 7: セキュリティ & ガードレール保護

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **G-01** | ローカルIP遮断 | `http://127.0.0.1:8080/test.png` を指定 | `isUrlSafe()` が `false` を返し、ダウンロードが即座に拒否されること |
| **G-02** | プライベートIP遮断 | `http://192.168.1.5/video.mp4` を指定 | ループバック/サイトローカルIPが遮断されること |
| **G-03** | 10MBサイズ上限ガード | 10MBを超える画像ファイルを指定 | `maxImageDownloadBytes` 到達時にストリームが中断され、メモリ枯渇が防がれること |
| **G-04** | yt-dlp 同意ゲート | YouTube URL を指定 (`allowExternalMedia = true`, `allowYtDlp = false`) | `yt-dlp` の自動ダウンロード・外部プロセス起動のどちらも行われず、安全にスキップされること |
| **G-05** | 外部メディア全体無効化 | `WebMediaManager.allowExternalMedia = false` に設定 | 全ての外部メディア（動画・画像）のダウンロード・再生が停止すること |
| **G-06** | DNS Rebinding (TOCTOU) | TTL=0 で 1 回目は公開 IP、2 回目は `127.0.0.1` を返す DNS を用意し、そのホストの URL を指定 | 検証時に解決した `InetAddress` へ直接接続し、再接続時に名前解決しないこと（接続先が切り替わらないこと） |
| **G-07** | ネイティブ動画ローダーのリダイレクト迂回 | 302 で `http://127.0.0.1/` へリダイレクトする動画 URL を指定 | Java 側で各ホップを検査し、ネイティブ DLL には `http://127.0.0.1:<relay-port>/v/<token>` だけが渡ること（生 URL・外部 URL が渡らないこと） |
| **G-08** | 外部メディアの既定値 | 新規設定ファイルでクライアントを起動 | `Allow External Web Media` が false で、外部 URL の画像・動画が取得されないこと |
| **G-09** | 許可ドメイン外の取得 | `Allowed Media Domains` に `example.com` のみ設定 | `evil.test` や `example.com.evil.test` への画像・動画取得が拒否されること |
| **G-10** | マニフェストの子リソース遮断 | `#EXTM3U` や `ffconcat` が外部 URL を参照する動画を指定 | ネイティブの `io_open` フックが子リソースの外部接続を拒否し、FFmpeg が勝手に外部ホストへ接続しないこと |
| **G-11** | 動画キャッシュを作らない | 動画を再生して画面を閉じる、または取得に失敗する URL を指定 | `enderframelib/cache/video` に一時ファイルが作られず、リレーのトークンも解放されること |
| **G-12** | ネイティブ入力ポリシー | DLL に外部 HTTPS、`file:`、UNC、相対パス、未設定ポートの loopback URL を直接渡す | すべて `handle=0` で拒否され、ローカルドライブパスまたは設定済みポートの `127.0.0.1` だけが受け付けられること |
| **G-13** | リレーポートの固定 | `setVideoProxyPort(51234)` 後に `http://127.0.0.1:9999/...` を渡す | ポート不一致として拒否されること |
| **G-14** | リレートークンの推測防止 | 動画再生中のトークン以外の `/v/...` へアクセス | `404 Not Found` になり、上流 URL や別動画へアクセスできないこと |
| **G-15** | yt-dlp 自動配置の完全性 | チェックサム不一致のバイナリ、または途中で切断されるダウンロードを用意する | `SHA2-256SUMS` と一致しないバイナリは最終パスへ移動されず、`.part` も残らないこと。既存の実行ファイルを上書きしないこと |
| **G-16** | yt-dlp 配布元の固定 | 公式 GitHub リリースの取得経路を確認する | 取得先が `github.com` / `release-assets.githubusercontent.com` などの固定ホストに限定され、`Allowed Media Domains` を変更しても任意ホストから実行ファイルを取得できないこと。`127.0.0.1` など私設網は信頼ホスト指定でも遮断されること |

---

### カテゴリ 8: ライフサイクル & メモリ管理

| ID | テスト項目 | テスト手順 | 期待される結果 (Pass基準) |
|---|---|---|---|
| **L-01** | 画面クローズ時の解放 | 動画再生中に `ESC` キーで画面を閉じる | `VideoTexture.close()` が呼ばれ、Native Handle、OpenAL Source/Buffer、およびループバック・リレーのトークンが解放されること |
| **L-02** | 自動クリーンアップ | 動画画面から離れ、1500ms 以上描画されない状態にする | `cleanup()` により `ACTIVE_VIDEOS` から削除されリソース破棄されること |
| **L-03** | ワールド切替時の全停止 | ワールドから切断（ログアウト）する | `LevelEvent.Unload` が発火し、`WebMediaManager.closeAll()` により全リソースがリセットされること |
| **L-04** | 長時間再生の安定性 | 10分以上動画を連続ループ再生する | DirectByteBuffer のリーク、クラッシュ、GCスタックが発生しないこと |
