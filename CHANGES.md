# Whisper to Input — Fork 改動說明（繁體中文）

原專案：j3soon/whisper-to-input（GPL-3.0，作者 Yan-Bin Diau、Johnson Sun 等，見原 LICENSE 與 README）。

## 2026-10-30 fork6 改動（版本 0.4-fork6，versionCode 10）

### 做了兩件事：鍵盤 UI redesign、潤飾 prompt 更新
設計稿來源：`whisper-input-redesign.zip`（DESIGN_NOTES.md 的 dp 與色票為準，Compose 提示改用 XML View 實作）。沒有動轉寫／潤飾管線、短句門檻（仍是 `POLISH_MIN_LENGTH = 10`）、Groq 預設值、fork5 的生命週期修正。

### 1. 鍵盤 UI redesign
- 整個面板由一個狀態列舉驅動（`Idle / Recording / Processing / Result`），畫面上哪些元件顯示、顯示什麼，全部集中在 `WhisperKeyboard.render()` 一處決定，沒有散在各處的 visibility 判斷。
- 版面（`res/layout/keyboard_view.xml`）：面板高 280dp、padding 左右 8／上 6／下 8；頂列 48dp（預覽 chip｜AI 潤飾 pill 開關｜設定）；底列 52dp 在每個狀態位置固定（左鍵｜空白｜退格（角標「長按」）｜Enter）。Android 輸入法框架會忽略根 View 的高度，所以 280dp 是掛在內層 LinearLayout 上。
- 淺色／深色色票照 DESIGN_NOTES（seed #3B5BDB）：`values/kb_colors.xml`、`values-night/kb_colors.xml`。圖示全部改成 Material Symbols Rounded 風格的 vector drawable（`kb_ic_*`），背景與按鍵形狀為 `kb_bg_*`。
- 待機：96dp 實心 primary 圓形麥克風，下方提示「點一下開始 · 按住說話」；兩側 48×48 的「語言」「紀錄」鍵取代原本的複製鍵。
- 錄音中：主鍵變 84dp 紅色停止鍵（28dp 圓角方塊）、兩圈脈動環（+9／+18dp，1.2s）、左右各 12 根波形（每 50ms 更新）、頂列紅底含計時 `0:07`；提示「放開送出 · 點擊停止 · 上滑取消」；左下鍵變「取消 ✕」。自訂 View 在 `keyboard/KeyboardViews.kt`（`WaveformView`、`PulseRingView`、`SpinnerRingView`）。
- 處理中：主鍵改 primaryContainer 底＋✦，外圈 indeterminate spinner（外擴 6dp、線寬 4dp）；下方步驟 chip 顯示「✓ 錄音　轉寫中…／✓ 轉寫　AI 潤飾中…」，依實際階段更新；潤飾關閉時不列出潤飾步驟。
- 結果卡：原文（灰、有潤飾版本時加刪除線）與 AI 潤飾（粗體）上下對照，四個動作：插入、重新潤飾、複製、復原；結果狀態時底列左鍵變麥克風。
- 手勢：點一下＝開始／停止；按住（≥400ms）＝說話、放開送出；錄音中上滑超過 48dp＝取消（放開才取消，提示會變成「放開即取消」）。
- 退格：按住 400ms 後每 50ms 連刪，按住滿 1.5s 後改為整詞刪除（`BreakIterator`，中文也會斷詞）。
- 手動／自動錄音、上次結果、空白鍵／Enter 在錄音中帶結尾空白／換行送出、重試上次錄音、切換鍵長按叫出系統輸入法選擇器、鍵盤上的潤飾開關（同一個 `POLISH_ENABLED`）都保留。

### 「語言」「紀錄」鍵實際接到什麼
- 語言：循環切換設定頁的「Language Code」（`LANGUAGE_CODE`）：auto → zh → en → auto，標籤顯示「自動／中文／English」，並跳出提示。**誠實說明：** 目前轉寫器只把這個值放在 NVIDIA NIM 的表單欄位，以及其他後端的網址參數（`&language=`），預設的 Groq 後端不一定會用到，所以這個鍵在預設設定下是否真的改變辨識語言，本版沒有驗證，也沒有為了它改轉寫請求。
- 紀錄：顯示上次結果的結果卡（原文／潤飾對照），可再插入、複製、重新潤飾；沒有紀錄時提示「尚無紀錄」。

### 結果卡動作的行為
- 轉寫完成後文字照舊在潤飾完才一次插入，並進入結果卡；這時「插入」呈灰色（已插入），「復原」可用。
- 復原：記住這次插入的文字長度，用 `deleteSurroundingText(len, 0)` 刪掉；換 App、鍵盤隱藏後再顯示會清掉這個記錄，所以不會誤刪別處的字。若游標在插入後被移動過，復原仍會刪掉游標前的那段長度，請先確認游標位置。
- 重新潤飾：對原文再呼叫一次潤飾（不管開關與短句門檻，因為是使用者明確要求），成功後更新上次紀錄與結果卡，不會自動插入；失敗提示「潤飾失敗，保留原文」。若前一次的結果還在輸入框裡，請先按「復原」再按「插入」。
- 預覽 chip 右側的小「插入」鈕：直接插入上次結果；點預覽文字＝複製上次結果（維持原行為）。

### 2. 潤飾 prompt 更新
- `DEFAULT_POLISH_PROMPT` 換成新版（與交付文字逐字比對一致），user message 改送 `<原文>` + 文字 + `</原文>`。
- `unwrap()` 會剝掉模型回傳中殘留的 `<原文>`／`</原文>`（含前後空白），並保留原本的 markdown 圍欄與外層雙引號清理。
- 舊版 prompt 遷移（`PolishPromptMigration.kt`）：prompt 版本號存在 SharedPreferences（`polish_prompt_version`，目前 2）。已存的 prompt 若是空白、或與 fork5 預設「完全相同」就改成新版預設，只做一次；使用者改過的內容不動。服務啟動與設定頁開啟時都會檢查。
- 設定頁潤飾 prompt 欄位上方新增「重設為預設」按鈕：先跳確認，確認後覆蓋欄位並立即存成新版預設。設定頁的預設值改成直接用 `DEFAULT_POLISH_PROMPT`（原本重複寫在 `strings.xml` 的字串已移除，只剩一份來源）。

### 與設計稿不同、或這版做不到的地方
- 預覽 chip 沒有「即時字幕」：目前沒有串流辨識，錄音中只顯示「錄音中…」與計時。
- 波形用的是 `MediaRecorder.maxAmplitude`（峰值，每 150ms 取一次，其間每 50ms 衰減平滑），不是真正的 RMS。
- 脈動環放大到 1.1 倍（設計筆記是 1.25），避免外圈超出 280dp 面板。
- 處理中狀態左下鍵是「取消 ✕」（設計稿畫的是切換鍵盤），因為轉寫中要能取消，這是原本就有的功能。
- 處理中的預覽 chip 沒有進度條，改顯示目前階段文字／原文。
- 重試上次錄音的按鈕改成提示列旁的「↻ 重試」小 chip。
- 舊的 `mic_*.png`、`btn_*.png` 圖檔已不再使用，但沒有刪除。

### 驗證（模擬器 Pixel 類 1080×2400，沒有在實機測）
- 建置通過；待機、錄音中（計時、波形、脈動環）、結果卡（含刪除線與動作鈕）、淺色與深色模式都有在模擬器上看過截圖；插入與復原實際對輸入框操作過一次。
- 沒有驗證到：處理中畫面（需要真的 API key 連網）、按住說話／上滑取消手勢（只用單點測過）、重新潤飾、prompt 遷移（只比對過舊版常數與 fork5 內容一致）。請 kevy 實機測這幾項。

### 簽章與安裝方式（重要）
- fork1～fork5 用的 debug keystore（憑證 SHA-256 `45920e60…62bcf4`）已經遺失，沒辦法再簽出同一把簽章。fork6 改用本機 Gradle 內建的 debug keystore（`~/.android/debug.keystore`，alias `androiddebugkey`）簽，release 建置的 `signingConfig` 指向它（`app/build.gradle.kts`）；憑證 SHA-256 為 `81a29dbbc74cc0a59ed17947bfedc52bde166ebb78fea44d1d9442c1bf29b644`。
- 因為簽章不同，**不能覆蓋安裝 fork5**：請先解除安裝 fork5，再安裝 fork6，App 內的設定會被清掉，要重新填 Groq API key（其他欄位預設已是 Groq，不用重填）。從 fork6 起，之後的版本只要沿用同一把本機 debug keystore 就能直接覆蓋安裝。
- 上次結果（轉寫紀錄）存在 App 私有儲存，解除安裝後也會一起消失。

### 程式位置
- 面板狀態與全部畫面邏輯：`keyboard/WhisperKeyboard.kt`（`render()`、`setupMicGesture()`、結果卡動作）；自訂 View：`keyboard/KeyboardViews.kt`；退格：`keyboard/BackspaceButton.kt`。
- 服務端的插入／復原／整詞刪除／重新潤飾接線：`WhisperInputService.kt`；重新潤飾：`WhisperTranscriber.polishAsync`。
- prompt：`PolishClient.kt`（`DEFAULT_POLISH_PROMPT`、`LEGACY_POLISH_PROMPT_V1`、`POLISH_PROMPT_VERSION`、`unwrap`）、`PolishPromptMigration.kt`、`MainActivity.kt`＋`res/layout/activity_main.xml`（重設按鈕）。

## 2026-10-30 fork5 改動（版本 0.4-fork5，versionCode 9）

### 要解決的問題（kevy 實機回報，精確重現步驟）
- 在 Google Gboard 長按空白鍵，切換到 Whisper；接著整個畫面 focus 還在輸入框，但是 Whisper 的鍵盤沒有出現。
- 最常發生在 Muse Android App 的聊天輸入框；輸入框本身有 focus、其他東西都正常，就是鍵盤不見，頁面裡切不出去再點回來也沒用，只有重新整理頁面或用系統層切換／重啟輸入法服務才有機會恢復。
- 驗收要求不只「切換時第一次就出現」：如果切換後一開始還是沒出現，使用者再點一次輸入框（輸入框本來就已經有 focus），鍵盤就一定要出現。這個第二點是可靠的恢復手段，本版要同時顧到。

### 程式碼審查的結論：先說沒有找到什麼（誠實說明）
- **程式碼審查未找到 Gboard 長按切換特有的確定原因。** 切換輸入法是系統驅動的：不管是用 Gboard 長按空白鍵、用導覽列小鍵盤圖示、用 Whisper 自己的切換鍵，系統最後都是同一組回呼交給 Whisper（綁定服務 → onStartInput → 建輸入畫面 → onStartInputView → 顯示視窗 → onWindowShown），Whisper 程式碼裡沒有任何「只有 Gboard 長按才走」的分支，所以不存在一個只屬於長按切換的缺口可以單點修掉。
- 這不代表問題不存在，而是說：問題最可能出在切換那一刻本來就會跑到的幾條路徑上——服務在切換時被叫到的回呼裡崩潰、錄音器在切換時狀態不對而拋例外、或是系統根本沒有決定要顯示這個輸入畫面。本版就是針對這幾條路徑做防禦性修復。
- **本版沒有在真機重現過這個 bug，也沒有辦法在沒有 Android 裝置的情況下證實哪一處就是 kevy 那一台的元兇。** 下面每一處改動都是「讓切換時可能讓鍵盤消失的路徑不再能讓鍵盤消失」，不是「已經證實修好了 kevy 那個特定案例」。請 kevy 照下面「真機測試」那段測，第一次切換和第二點都要測，結果才是驗收。

### 調查到的切換路徑缺口（程式碼上實際存在的）
1. 服務生命週期回呼裡到處是 `recorderManager!!`：舊程式只在 `onCreateInputView()` 裡建立 RecorderManager，但 `onWindowShown()`、`onWindowHidden()`、`onDestroy()`、`onStartRecording()`、`onStartTranscription()` 全都用 `!!` 強制解包。如果切換時系統叫回呼的順序跟預期不同（例如視窗回呼在畫面建立之前就先到，或是服務被綁定後還沒建畫面就先被銷毀），任何一個 `!!` 都會在主執行緒拋出例外、服務進程死掉，鍵盤就靜默消失——跟回報的症狀一模一樣，而且不會跳任何錯誤框。
2. 切換進來第一次就會自動開始錄音，而錄音啟動本來就會拋例外：舊 `onWindowShown()` 第一次被叫到時（預設自動開始錄音是開的）會直接叫錄音；舊 `RecorderManager.start()` 是先把 `prepare()` 失敗記下來、然後不管怎樣都叫 `start()`，而 `start()` 在麥克風還被前一個輸入法／其他 App 佔著時會拋例外。這個例外以前沒有被接住，會直接在主執行緒殺掉服務——也就是說，切換成功、視窗正要出現、自動錄音一失敗，鍵盤就沒了。
3. 停止錄音也會拋例外：舊 `RecorderManager.stop()` 直接叫 `MediaRecorder.stop()`，但錄音器在還沒開始、已經停止、或是完全沒錄到資料時叫 `stop()` 本身就會拋 `IllegalStateException`／`RuntimeException`。而 `stop()` 正是 `onWindowShown()`／`onWindowHidden()`／`onDestroy()`（全是切換時會跑到的回呼）裡第一件做的事。
4. 服務完全沒有覆寫「要不要顯示輸入畫面」的決策：舊程式沒有 `onStartInputView()`，也沒有 `onEvaluateInputViewShown()`，切換後畫面要不要出現完全交給預設行為。輸入框本來就有 focus、只是換輸入法時，系統不一定會像「點輸入框叫鍵盤」那樣完整重走顯示流程，這裡沒有任何一處明確告訴系統「這個畫面要顯示」。
5. 切換時建畫面還會在主執行緒同步載入轉換表：舊 `onCreateInputView()` 裡兩次 `ChineseUtils.preLoad()` 都是同步做在主執行緒，第一次切換進來時這段載入會擋住畫面建立的時間。

### 本版做的防禦性修復（只做這些，沒有改其他功能）
- RecorderManager 改到服務建立時就建立：`WhisperInputService.onCreate()` 裡就建立 RecorderManager 並接好音量回呼，`onCreateInputView()` 只在萬一還沒建立時才補建。服務所有生命週期回呼裡的 `recorderManager!!` 全部改成安全呼叫（`?.`），並且 `onStartRecording()` 在錄音器不存在時只把鍵盤回到閒置、不會崩潰。從此「回呼順序跟預期不同」這一類原因不可能再讓服務死掉。
- 錄音啟動改成回傳成功／失敗，不再拋例外：`RecorderManager.start()` 現在回傳 Boolean，`prepare()` 或 `start()` 失敗時會釋放錄音器、記一筆 log、回傳 false；`onStartRecording()` 收到 false 就把鍵盤回到閒置，讓使用者可以再點一次麥克風。切換後自動錄音失敗時，結果是「鍵盤還在、只是沒開始錄」，不再是「鍵盤整個不見」。
- 錄音停止改成永遠不拋例外：`RecorderManager.stop()` 現在把 `stop()`／`release()` 各自包在 try/catch 裡，不管錄音器是什麼狀態，最後一定釋放並忘掉它。切換時的 `onWindowShown()`／`onWindowHidden()`／`onDestroy()` 因此不可能再被停止錄音拖垮。
- 每次顯示輸入畫面都重新同步：新增 `onStartInputView()`，裡面先叫父類別實作、再叫 `whisperKeyboard.reset()`（重新讀回上次結果與潤飾開關、回到閒置）。這個回呼在切換後、以及之後每一次畫面重新顯示時都會跑，而且 `reset()` 本身是等冪的（重複叫沒有副作用），所以「已經有畫面」「已經顯示過」都不會讓第二次顯示被跳過；它也不會停止進行中的錄音或轉寫（停止錄音／轉寫仍留在原本的視窗回呼裡）。
- 明確告訴系統要顯示畫面：新增 `onEvaluateInputViewShown()`，只要有編輯器資訊、且編輯器不是明確要求不要軟鍵盤（inputType 為 TYPE_NULL），就回傳 true；還沒有編輯器資訊時則交給父類別預設行為，不亂猜。使用者主動用返回鍵收起鍵盤是走隱藏視窗的路，不是走這個決策，所以這個改動不會讓鍵盤在使用者不想看的時候硬跳出來。
- 轉換表預載改到服務建立時先在背景暖機：`onCreate()` 裡用背景執行緒先跑兩次 `preLoad()`，`onCreateInputView()` 裡的同步 `preLoad()` 刻意保留，作為轉換正確性的保證——暖機之後它通常只是命中快取，不再是第一次載入的完整等待；就算暖機還沒跑完，行為也跟舊版完全一樣，沒有把預載拿掉的風險。

### 第二點（再點一次輸入框）的恢復手段：fork5 能保證到哪裡、不能保證到哪裡
- fork5 能保證的（輸入法這邊）：只要系統因為第二次點輸入框而再次進入輸入法——也就是再次綁定服務、再次叫 `onStartInput`／`onStartInputView`／`onWindowShown` 其中任何一條——這邊一定會把畫面建出來、不會崩潰、`onEvaluateInputViewShown()` 會回答要顯示、`onStartInputView()` 會重新同步鍵盤狀態。服務如果在第一次切換時已經死掉，第二次點輸入框觸發系統重新綁定時，新的服務實例會從 `onCreate()` 就把錄音器建好、再建畫面，不會重蹈第一次的覆轍。
- fork5 不能保證的（App 這邊，誠實寫明）：輸入法沒有辦法自己強迫跳出來，它只能等系統叫它。如果 Muse App 在輸入框已經有 focus 的情況下、使用者再點一次時完全不向系統要求鍵盤（不叫顯示軟鍵盤、不重新要求輸入），系統就不會再進入輸入法，fork5 也就沒有機會顯示。也就是說，第二點要成立，前提是 App 那一次點按有向系統要求鍵盤；如果 App 那邊根本沒要求，問題就不在輸入法這邊，要另外從 App 端看。這一點在真機測試時要分開判斷：第二次點了有沒有用，先看下面測試步驟裡的對照。
- 沒有改的東西：自動開始錄音的預設值不動（仍是開的）、麥克風置中、上排對齊、兩行顯示、潤飾開關、潤飾管線、短句門檻（仍是預設 10 字）、Groq 預設值全部不動；沒有倉頡、沒有串流／分段辨識。

### 真機測試（請 kevy 兩步都測，缺一不可）
1. 第一次切換：打開 Muse 聊天輸入框，先用 Gboard，長按空白鍵切到 Whisper，看鍵盤有沒有出現；如果出現，講一段話確認錄音／轉寫／潤飾都還正常（這版不應該改到這些）。
2. 第二點恢復：如果第一步切換後鍵盤還是沒出現，不要先重新整理頁面，先再點一次輸入框（輸入框本來就有 focus），看鍵盤有沒有出現；如果出現，這一步就是有效的恢復手段。
3. 對照組（幫助判斷是哪一邊的問題）：如果第二點點了也沒用，改用導覽列小鍵盤圖示切一次 Whisper、或是切回 Gboard 再切回來，看有沒有用；如果系統層切換有用、但頁面裡再點沒用，那比較可能就是 App 那一次點按沒有向系統要求鍵盤（見上一節的限制），要再從 App 端想辦法。

### 如果還是發生，請這樣幫忙抓資訊（不用現在就做，之後發生時再記下來就好）
- 當時是怎麼切的：Gboard 長按空白鍵、還是導覽列小鍵盤圖示、還是 Whisper 自己的切換鍵。
- 切換後再點一次輸入框有沒有用、切回 Gboard 再切回來有沒有用。
- 鍵盤消失那一次，Whisper 是不是正要自動開始錄音（第一次切進來會自動錄音，麥克風圖示有沒有先亮一下）。
- 如果之後願意提供 logcat（到時候再另外教怎麼抓，本版不要求任何額外步驟），搜尋 `whisper-input` 開頭的 log 會最有幫助，特別是錄音啟動失敗的那一行。

### 程式位置
- 服務生命週期與顯示決策：`WhisperInputService.kt`（`onCreate`、`onCreateInputView`、`onStartInputView`、`onEvaluateInputViewShown`、`onWindowShown`、`onWindowHidden`、`onDestroy`、`onStartRecording`、`onStartTranscription`、`onCancelRecording`）。
- 錄音器不再拋例外：`recorder/RecorderManager.kt`（`start` 回傳 Boolean、`stop` 永遠不拋例外）。
- 沒有改的檔案（對照用）：`keyboard/WhisperKeyboard.kt`、`WhisperTranscriber.kt`、`PolishClient.kt`、兩行顯示與潤飾開關的版面與邏輯。

### 安裝方式
- fork5 的安裝方式要看簽名比對結果（見交付訊息）：簽名跟 fork4 相同才能直接覆蓋安裝、設定保留；簽名不同就必須先解除安裝 fork4、設定要重填一次。不要憑感覺覆蓋安裝。

## 2026-10-30 fork4 改動（版本 0.4-fork4，versionCode 8）

## 2026-10-30 fork4 改動（版本 0.4-fork4，versionCode 8）

### 原文＋潤飾後同時顯示（只做這件事）
- 位置與樣式：鍵盤上排不再只有一條會輪流換內容的標題，改成同一個上排容器裡的兩行（版面在 `res/layout/keyboard_view.xml`，新容器 `result_texts`，在原本切換鍵與潤飾開關之間、垂直置中）。
  - 上面一行沿用原本的 `label_status`：顯示偵測出來的原文，前面加「原文：」，比較淡（alpha 0.65）、字比較小（13sp）。
  - 下面一行是新的 `label_polished`：顯示潤飾完、實際插進去的那段，前面加「潤飾後：」，正常大小（15sp）、粗體。
  - 兩行各自最多三行、太長省略（maxLines=3 + ellipsize），不會因為長句無限拉長面板；前綴只是顯示用的標籤，複製時不會把「原文：」／「潤飾後：」一起複製進去。
- 分段流程（插入方式不變，只在最後插入一次，不先插原文再替換）：
  - 剛轉寫完：只顯示原文那行，潤飾後那行還沒有（不會顯示空的潤飾後行）。
  - 正在潤飾時：原文那行留著，潤飾後那行顯示「潤飾後：正在潤飾中…」，不會把原文蓋掉；等待圖示與取消鍵跟轉寫中狀態相同。
  - 潤飾完：原文那行留著，潤飾後那行改成潤飾後結果，文字欄位也在此時才插入一次（潤飾後）。
  - 關掉潤飾、太短跳過（仍是預設 10 字）、潤飾失敗／超時／模型不存在：只顯示原文那行，不顯示空的潤飾後行，插入原文，跟 fork3 行為一致。
  - 正在錄音或正在轉寫新的一段、還沒出新原文時：上排先顯示「錄音中」／「轉寫中」狀態，潤飾後行隱藏，不會殘留上一段的原文／潤飾後結果來混淆新一段；取消回到閒置時，照舊顯示上一筆已插入的結果。
  - 潤飾完成那段沒有新增階段回呼：轉寫器回呼仍是原文 → 正在潤飾中兩段，最終兩行是轉寫完成後從上一筆紀錄讀回來的（原文＋潤飾後），這也是鍵盤 view 重建時的同一條路。
- 複製行為：
  - 左邊複製鍵：複製實際插進去的那段（潤飾成功時是潤飾後，跳過／失敗／關掉時是原文），跟 fork3 一致。
  - 點原文那行：複製原文完整內容（不是被截斷的顯示文字）。
  - 點潤飾後那行：複製潤飾後完整內容；正在潤飾中時點潤飾後那行不會複製「正在潤飾中…」這句狀態文字（此時還沒有潤飾後內容，點了沒反應）。
  - 錄音中／單純轉寫中（還沒出原文）時點任一行都不會複製上一段的舊文字。
- 鍵盤 view 重建：從上一筆紀錄讀回並同時顯示兩行（有 polishedText 就顯示原文＋潤飾後，沒有就只顯示原文），不要只顯示其中一段。
- 排版不動既定設計：切換鍵、潤飾開關、設定齒輪、上排對齊、麥克風置中都不動；上排因多一行會變高、麥克風約束在上排下方所以會跟著往下移一點，這是預期的，沒有硬改麥克風位置或上排高度。

### 沒有做的事（明確不在本次範圍）
- 沒有改潤飾管線、分段插入方式、短句跳過門檻（仍是預設 10 字）、Groq 預設值、潤飾開關。
- 沒有修 Gboard → Whisper 切換時鍵盤消失的問題（那是另一個待查 bug，不在本版範圍）。
- 沒有倉頡鍵盤、沒有串流／邊講邊出字。

### 使用方式
- fork4 的安裝方式要看簽名比對結果（見交付訊息）：本次簽名跟 fork3 相同，可以直接覆蓋安裝、設定保留；不要憑感覺先解除安裝。
- 講完話後看上排：先出原文那行 → 潤飾後那行變「正在潤飾中…」→ 潤飾後那行變潤飾後結果並插入，原文那行一直留著給你對照；點兩行可以分別複製原文與潤飾後。

### 程式位置
- 兩行顯示與複製：`keyboard/WhisperKeyboard.kt`（`label_polished`、`rawResultText`、`polishedResultText`、`polishingInFlight`、`renderResultLines`、`copyRawResult`、`copyPolishedResult`）。
- 上排版面與前綴字串：`res/layout/keyboard_view.xml`（`result_texts`、`label_polished`）、`res/values/strings.xml`（`result_raw_prefix`／`result_polished_prefix`）。
- 最終兩行來源（本版未改，只確認）：`TranscriptionHistory.kt`（上一筆 rawText＋polishedText）、`WhisperInputService.kt` 的 `transcriptionCallback`（插入後呼叫 setLastResult，再 reset 從紀錄讀回兩行）。

## 2026-10-30 fork3 改動（版本 0.4-fork3，versionCode 7）

### 鍵盤上的潤飾開關（只做這一顆按鈕）
- 位置：鍵盤上排、設定齒輪左邊（切換鍵 — 上排標題 — 潤飾開關 — 設定齒輪），新 id `btn_polish_toggle`，版面在 `res/layout/keyboard_view.xml` 的 `top_row` 內。
- 圖示：專案現有 drawable 裡沒有星星／魔法棒／潤飾圖（只有返回、取消、重試、退格、設定、麥克風、複製等圖），也沒有下載任何外部素材；本版新增一個簡單的自繪向量圖 `res/drawable/ic_polish.xml`（一大一小兩顆四角星星，星星圖案），跟 `ic_copy` 一樣用 `?attr/colorControlNormal` 著色，會跟著主題走。
- 跟設定頁同一個開關：這顆按鈕直接讀寫設定頁潤飾開關同一個 DataStore key（`POLISH_ENABLED`，見 `MainActivity.kt`），不是另外開一個新開關。鍵盤上點一下就切換並持久存下來，下次打開鍵盤還是上次狀態；設定頁那個開關讀的是同一個 key，所以兩邊不會打架（設定頁改完回鍵盤時，鍵盤在 `reset()`／視窗顯示時會重新讀一次，不用重建 view 也會同步）。預設值跟設定頁一致（預設開）。
- 開關狀態怎麼看：開的時候圖示全亮（alpha 1.0），關的時候明顯變淡（alpha 0.35，跟面板上「沒有上次結果時複製鍵淡化」是同一種做法）；鍵盤 view 建立時會先讀 DataStore 把狀態畫對，不會先顯示錯的狀態。無障礙說明文字也會跟著變（開：潤飾已開啟，點一下關閉；關：潤飾已關閉，點一下開啟）。
- 只影響下一段錄音：切換不會中途切掉或取消正在錄音／正在潤飾的那段。已實際確認現有程式本來就是這樣：`WhisperTranscriber.kt` 在轉寫開始時把 `POLISH_ENABLED` 連同其他設定一次讀成快照，之後的潤飾判斷只用那份快照，中途不會重新讀取開關狀態，所以本版不需要改潤飾管線來達成這一點。
- 排版不動既定設計：上排維持 48dp 同一垂直中心線，切換鍵與設定齒輪位置不動，麥克風維持置中不動；只是多一顆 48dp 按鈕，標題寬度因此變窄 48dp，這是預期的。上排高度與麥克風位置都沒有改。

### 沒有做的事（明確不在本次範圍）
- 沒有改潤飾管線、分段顯示（STT 原文 → 正在潤飾中 → 潤飾後結果、一次插入）、短句跳過門檻（仍是預設 10 字）、Groq 預設值。
- 沒有修 Gboard → Whisper 切換時鍵盤消失的問題（那是另一個待查 bug，不在本版範圍）。

### 使用方式
- fork3 的安裝方式要看簽名比對結果（見交付訊息）：簽名跟 fork2 相同才能直接覆蓋安裝、設定保留；簽名不同就必須先解除安裝 fork2、設定要重填一次。不要憑感覺覆蓋安裝。
- 講話前點一下上排的星星開關就能決定這段要不要潤飾，不用進設定頁；開著就照 fork2 流程走（夠長才潤飾、太短直接出原文、失敗退回原文），關掉就永遠只插原文。

### 程式位置
- 鍵盤開關按鈕與狀態：`keyboard/WhisperKeyboard.kt`（`btn_polish_toggle`、`polishEnabled`、`refreshPolishToggle`、`onButtonPolishToggleClick`、`updatePolishToggleButton`）。
- 上排版面與圖示：`res/layout/keyboard_view.xml`（`btn_polish_toggle`）、`res/drawable/ic_polish.xml`、`res/values/strings.xml`（`polish_toggle_hint`／`polish_toggle_on`／`polish_toggle_off`）。
- 潤飾啟用快照（本版未改，只確認）：`WhisperTranscriber.kt`（`startAsync` 開始時一次讀取 `POLISH_ENABLED`）。

## 2026-10-30 fork2 改動（版本 0.4-fork2，versionCode 6）

### 潤飾管線：轉寫 → 判斷 → 潤飾 → 一次插入
- 流程：轉寫成功、後處理轉繁體後先得到原文（rawText），再決定要不要潤飾，最後只插入一次。潤飾成功插潤飾後文字，跳過或失敗插原文。
- **一次插入的安全理由**：不要先插原文、之後再去替換成潤飾後文字，因為使用者在等潤飾時可能已經動了游標或改了字，事後替換會蓋掉他的編輯。等潤飾有結果才插入，就沒有這個問題。
- 潤飾預設用 Groq chat completions：端點 `https://api.groq.com/openai/v1/chat/completions`，模型 `qwen/qwen3.8-27b`，temperature 0.2（保真、不要發散）。Groq 免費模型輪換時，使用者在設定頁自己改模型字串即可，不用重編。
- 潤飾 prompt 預設（設定頁可編輯）：「你是文字編輯，只輸出整理後的成品，不要加任何解釋、前言或引號。規則：刪除贅字（嗯、呃、那個、就是說），修正文法、補上標點；講者自我修正時只保留最後版本，例如『約明天下午、不對、是後天』只留『後天』；全文必須是繁體中文，絕對不要輸出簡體字，逐字稿是簡體也要轉成繁體；數字、英文術語保持原樣；不要擴寫、不要腦補、不要改變原意；非中文內容保持原語言，不要硬翻成中文。」
- 回傳處理：取 choices[0].message.content，trim 後若整段被 markdown 圍欄或雙引號包住就去掉外層包裝；去不掉、為空、或解析失敗都視為潤飾失敗，退回原文。

### 分段顯示（只改鍵盤上排顯示，不改插入方式）
- 第一段：轉寫完成後，上排先顯示 STT 原文結果（最多三行、太長省略，跟 fork1 的上次結果顯示方式一致）。
- 第二段：開始送 LLM 潤飾時，上排改成顯示「正在潤飾中…」，不要還停在原文、也不要先顯示潤飾後結果，讓使用者知道現在是在等 LLM，不是卡住。等待圖示與取消鍵跟轉寫中狀態相同。
- 第三段：LLM 回來後，上排改成顯示潤飾後結果，複製鍵複製的也是潤飾後結果，文字欄位插入的也是潤飾後結果。
- 複製目標跟著階段走：第一段時複製原文，第三段時複製潤飾後結果（或失敗／跳過時的原文），不會複製到上一段的舊文字。錄音中／單純轉寫中（還沒出原文）時，點上排不會複製舊結果。
- 短句跳過時：直接顯示原文當最終結果，不經過「正在潤飾中」那段。
- 潤飾失敗／超時／模型不存在時：直接顯示原文當最終結果並插入原文，不會卡在「正在潤飾中」。取消錄音或取消轉寫也會結束分段顯示，不會殘留「正在潤飾中」。

### 短句跳過（門檻是 fork 預設值，不是 kevy 定的數字）
- kevy 的規則只有「太短的字不用潤飾」，沒有定數字。本版用一個明確的預設值：trim 後長度 ≤ 10 個字元就直接跳過 LLM，插入原文，polishedText 保持 null，上排顯示原文。
- 門檻只有一處常數（PolishClient.kt 的 POLISH_MIN_LENGTH），kevy 之後想改數字只要改這一處，不要在其他地方硬編第二個門檻。

### 失敗一律退回原文
- LLM 超時（連線 30 秒、讀取 60 秒）、HTTP 錯誤、模型不存在、回傳空字串，都插入原文，polishedText 保持 null，上排顯示原文。鍵盤不會卡住、不會丟失文字、不會跳會擋住打字的錯誤框（轉寫本身失敗的舊錯誤提示不動）。

### 設定欄位（設定頁，照現有寫法新增）
- LLM Polish（開關，預設開）：關掉就永遠只插原文，不送 LLM。
- LLM Endpoint（預設如上）。
- LLM Model（預設如上，可手動改字串）。
- LLM API Key（預設留空＝沿用轉寫那個 Groq key，填了就用填的，不用強迫使用者填兩次同一個 key；key 只在手機設定頁輸入，不寫進程式碼）。
- LLM Polish Prompt（可編輯，預設如上）。
- 轉寫設定（後端、端點、模型、語言、後處理）全部不動；潤飾開關關掉或短句跳過時，行為跟 fork1 完全相同。

### 設定頁預設改成 Groq＋Whisper（fork2，轉寫設定）
- 轉寫設定的預設值從 OpenAI 預設改成 Groq 預設：後端選項維持「OpenAI API」不動（那只是 OpenAI 相容協定的選項名稱），端點預設改成 `https://api.groq.com/openai/v1/audio/transcriptions`，模型預設改成 `whisper-large-v3-turbo`，語言維持 `auto`（kevy 實機跑通過的那組，不擅自改成 zh），後處理維持轉繁體。
- 潤飾設定的預設值本來就是 Groq（端點 `https://api.groq.com/openai/v1/chat/completions`、模型 `qwen/qwen3.8-27b`、key 留空沿用轉寫 key），確認沒有被蓋回 OpenAI。
- 新安裝的使用者只要在設定頁填 Groq key 就好，端點跟模型兩欄已經是 Groq 預設值。絕對沒有把任何真實 API key 寫進預設值、程式碼或本文件。
- **升級不會洗掉舊設定（已實際檢查程式）**：設定頁的 SettingText／SettingStringDropdown／SettingDropdown 在初始化時，只有在 DataStore 裡完全沒有該欄位值時才寫入預設值；已經存好的值會直接讀出來用，不會被預設值覆蓋。後端切換選單的處理也有 `setupSettingItemsDone` 保護，初始化時不會觸發覆蓋。轉寫器的 Groq 預設也只是「讀不到值時的備用值」，不會寫回設定。所以 fork1 已填好 Groq 的使用者升級到 fork2 後，原本的設定保持不變；只有全新安裝、欄位還沒存過時才會拿到 Groq 預設值。
- 轉寫器讀不到設定時的備用值也改成 Groq（端點、模型、語言 auto），跟設定頁預設一致；同樣只是讀取備用，不會覆蓋已存設定。

### 紀錄與顯示（只存上一筆的規則不變）
- 潤飾成功時，同一筆紀錄填 rawText＋polishedText，插入的是 polishedText，上排顯示 polishedText，複製鍵複製 polishedText（也就是實際插入的那段）。
- 跳過或失敗時，插入 rawText，上排與複製鍵都是 rawText，polishedText 保持 null。
- 鍵盤 view 重建（換 App 再回來）時，上排顯示該筆實際插入的文字（有潤飾後就顯示潤飾後，否則顯示原文）。
- 不做完整紀錄清單，不記目標 App 名稱，不記 API key，紀錄只存在手機本機 App 私有儲存。

### 沒有做的事（明確不在本次範圍）
- 沒有加倉頡鍵盤。
- 沒有做串流／邊講邊出字的轉寫（那是之後的另一個階段）。
- 沒有完整紀錄清單。
- 沒有動麥克風置中、上排對齊、左邊複製鍵、長按切換的 fork1 行為。

### 使用方式
- 先解除安裝 fork1（簽名不同，無法直接覆蓋安裝；package 不變，仍是 com.example.whispertoinput），再安裝 fork2，設定要重新填一次；全新安裝時轉寫欄位已經預設是 Groq（端點、模型、語言 auto、轉繁體），只要填 Groq key 就好，潤飾欄位也有預設值，LLM key 留空就會沿用轉寫的 Groq key。
- 講完話後看上排：先出原文 → 變「正在潤飾中…」→ 出潤飾後結果並插入；太短的句子會直接出原文，不經過潤飾中那段。

### 程式位置
- 潤飾呼叫與回傳清理：`android/app/src/main/java/com/example/whispertoinput/PolishClient.kt`（POLISH_MIN_LENGTH、預設端點／模型／prompt、超時設定）。
- 轉寫→潤飾→一次插入流程：`WhisperTranscriber.kt`（Outcome：rawCore／polishedCore、PolishStage 回呼）。
- 分段顯示與複製目標：`keyboard/WhisperKeyboard.kt`（showTranscriptionStage、showPolishingStage、pipelineActive、refreshLastResult 改為潤飾後優先）。
- 階段回呼轉到主執行緒：`WhisperInputService.kt`（onPolishStage）。
- 紀錄：`TranscriptionHistory.kt`（add 加上 polishedText 參數）。
- 設定：`MainActivity.kt`（POLISH_ENABLED、LLM_ENDPOINT、LLM_MODEL、LLM_API_KEY、LLM_PROMPT）、`res/layout/activity_main.xml`、`res/values/strings.xml`（含「正在潤飾中…」）。

## 2026-10-30 fork1 改動

### 1. 上次結果（只存上一筆，不做完整紀錄清單）
- 每次轉寫成功後，實際插入的那段文字（已做後處理，例如轉繁體；空白鍵／換行鍵附加的結尾空白或換行在存檔時會先去掉）會存成「上一筆」。
- 每筆紀錄有：時間戳（epoch millis，顯示時用裝置本地時間）、rawText、polishedText。
- polishedText 目前一律是 null，未來潤飾用。本次沒有做任何 LLM 呼叫、也沒有編任何潤飾內容，未來加潤飾時直接填這個欄位即可，不用改資料結構。
- 只存上一筆，不存 50 筆、沒有 App 裡的完整紀錄清單（本 fork 早期曾寫過清單版，已依 kevy 定案拿掉，成品裡沒有）。
- 只存在手機本機 App 私有儲存（transcription_history.json），不上傳、不送到任何伺服器、不記錄目標 App 名稱、不記錄 API key。
- 存檔失敗不會影響插入文字：存檔用 try/catch 包起來，失敗就靜靜跳過，插入流程優先。

### 2. 鍵盤面板顯示上次結果（上排標題位置）
- 顯示位置是鍵盤面板上方現有的 label_status 那一條，不是中間，所以麥克風不需要讓位。
- 轉寫完成、文字插入後，上排顯示上次結果，並且在下次錄音開始前都保持顯示（不會一回到 Idle 就被預設「Whisper To Input」蓋掉）。
- 錄音中顯示「錄音中」、轉寫中顯示「轉寫中」，狀態優先，不會被結果蓋掉。
- 鍵盤 view 重建（換 App 再回來）時，會從本機儲存讀回上次結果顯示；還沒有任何結果時，顯示原本的預設標題。
- 上次結果最多顯示三行、太長省略（版面 maxLines=3 + ellipsize），點一下標題可複製完整文字（不是被截斷的顯示文字）。

### 3. 上排對齊＋左邊複製鍵（麥克風維持置中）
- 上排改法：切換鍵、標題／上次結果文字、設定齒輪改成同一個 48dp 起跳的上排容器（top_row），三者同一垂直中心線、按鈕同高 48dp、上下邊距一致。原本切換鍵 60x40、設定 50x50、各自對 top_bar／label_status 的不同約束已全部拿掉，改成在容器內左右貼邊、垂直置中；標題文字在兩個按鈕之間置中，不會被擠歪。容器高度是 wrap_content、最小 48dp，所以三行結果長高時按鈕仍保持垂直置中、不會被裁掉。
- 麥克風 frame 的置中約束完全不動（Start／End 都對 parent、沒有左右移），這是 kevy 明確要求的。
- 左邊補空：麥克風左邊、跟右邊退格鍵對稱的位置（同樣 60x50dp、距麥克風 16dp、對麥克風上下置中），加一顆「複製上次結果」鍵（ic_copy 圖示），點一下複製完整上次結果。
  - 這個位置原本就是取消／重試鍵的位置，所以三者共用左邊槽位，不會重疊：閒置且沒有重試時顯示複製鍵（沒有上次結果時顯示為淡化、不可點，不會點了沒反應）；錄音／轉寫中顯示取消鍵、複製鍵隱藏；需要重試時顯示重試鍵、複製鍵隱藏（此時點標題仍可複製）。
- 下面空白鍵、換行鍵、退格鍵、麥克風大小與位置、錄音／轉寫狀態顯示，全部不動。

### 4. 切換鍵長按跳出系統輸入法選擇器
- 鍵盤左上那顆切換鍵：短按保持原行為（切回上一個輸入法），長按則呼叫 InputMethodManager.showInputMethodPicker()，跳出系統輸入法選擇器，可以選任何已開啟的輸入法，不只限於上一個（例如 Gboard 倉頡或其他鍵盤）。
- 長按有震動回饋（LONG_PRESS），沒有加動畫；長按回傳 true，所以不會同時再觸發短按切換。
- 如果系統判定不應提供切換鍵（按鍵本身被隱藏），長按也同樣不可用，這跟短按原行為一致。
- 其他按鍵（麥克風、退格、換行、空白鍵等）的行為全部不動。

### 沒有做的事（明確不在本次範圍）
- 沒有加 LLM 潤飾、沒有加任何新網路請求。
- 沒有加倉頡鍵盤。
- 後端、端點、模型、語言、後處理、麥克風／取消／退格／換行／切換鍵的現有行為全部不動。

### 使用方式
- 先解除安裝手機上原版 v0.4（本 fork 的簽名是本機 debug 簽名，與官方 v0.4 的簽名不同，無法直接覆蓋安裝），再安裝本 fork APK，設定（包括 Groq key、端點、模型）要重新填一次。
- 講完話、文字插入後，鍵盤上排就會顯示上次結果；點上排文字或點麥克風左邊的複製鍵，都可以複製完整上次結果。
- 切換輸入法：點左上切換鍵是回上一個輸入法，長按左上切換鍵是跳出系統選擇器、任選輸入法。
- 版本：0.4-fork1（versionCode 5），package 不變，仍是 com.example.whispertoinput。

## 程式位置（未來加潤飾時看這裡）
- 上次結果模型與儲存：`android/app/src/main/java/com/example/whispertoinput/TranscriptionHistory.kt`（只存上一筆，loadLast / add / clear）
- 轉寫成功後寫紀錄：`WhisperTranscriber.kt` 的 `startAsync`（IO 執行緒內，插入前先寫檔，寫檔失敗不影響插入）
- 上排顯示／複製鍵／標題點按複製：`keyboard/WhisperKeyboard.kt`（setLastResult、refreshLastResult、updateStatusLabelForIdle、updateCopyButton、copyLastResult、setKeyboardStatus 的 Idle 分支）
- 轉寫完成後把結果交給鍵盤：`WhisperInputService.kt` 的 `transcriptionCallback`（commit 後呼叫 setLastResult，之後的 reset 會從儲存讀回同一筆，不會清掉）
- 上排版面與複製鍵版面：`res/layout/keyboard_view.xml`（top_row 容器、btn_copy_last_result、ic_copy 圖示）
- 切換鍵長按：`keyboard/WhisperKeyboard.kt`（長按監聽＋震動）、`WhisperInputService.kt` 的 `onShowInputMethodPicker()`
- 未來加潤飾時：轉寫後在 `WhisperTranscriber.kt` 拿到 polished 文字後，把同一筆紀錄的 polishedText 從 null 改成該文字即可；資料結構、儲存、上排顯示都已經留好位置。另外 kevy 已先給一條未來潤飾規則：太短的文字要跳過 LLM 潤飾，門檻由他之後再定，不要自己編一個數字。
