# 和 keyboard bot 的協作規則

這個專案由 keyboard bot 派任務、Claude Code 執行，雙方透過 `.agent/handoff.md` 溝通。Kevy 會在對話裡打「看 handoff」叫你開始一輪。

## 每一輪的流程
1. 讀 `.agent/handoff.md` 最上面的狀態列。
2. 只有 `STATUS: TODO_CLAUDE` 時才動作。如果是 `DONE_CLAUDE` 或 `BLOCKED`，回覆「目前沒有我的任務（STATUS: xxx）」就結束，什麼都不要改。
3. 只執行 `## TASK` 區塊的內容。不要修改 TASK 區塊。
4. 做完後，把 `## REPORT` 區塊整個換成這一輪的回報（格式見下）。
5. 把同一份回報 append 到 `.agent/log.md` 最後面，標題寫 `## ROUND n（日期時間）`。
6. **最後一步**才把狀態列改成 `STATUS: DONE_CLAUDE`、更新 `UPDATED:`（台北時間，格式 YYYY-MM-DD HH:mm）。ROUND 不要改。
7. 在對話裡回一句「ROUND n 完成，已更新 handoff」就結束。

## REPORT 格式
- 結果：完成／部分完成／未完成
- 做了什麼（條列）
- commit：SHA 與訊息
- 驗證：建置、測試、手動檢查的結果
- 疑慮或未完成的地方
- 需要 keyboard bot 或 Kevy 決定的事（沒有就寫「無」）

## 什麼時候改成 BLOCKED
遇到以下情況就停下來，在 REPORT 寫清楚原因和你建議的選項，狀態改成 `STATUS: BLOCKED`，不要自己猜：
- 需要 Kevy 做決定，或 TASK 寫得不清楚、互相矛盾。
- 高風險操作：產生或更換簽章 keystore、刪除非自己這輪產生的檔案、動到 API 金鑰、改寫 git 歷史（reset --hard、force push、rebase 已 commit 的內容）。
- 同一件事試了兩次都失敗。

## 其他規則
- `.agent/` 資料夾不要放進 git（.gitignore 要排除它）。
- 不要修改 `CLAUDE.md` 本身。
- 每輪的程式改動至少 commit 一次，commit 訊息用英文，開頭標 `[round n]`。
