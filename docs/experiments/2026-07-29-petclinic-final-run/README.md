# petclinic 最終跑（2026-07-29）的交付物

2026-10-20 從使用者 Downloads 的「新增包含項目的檔案夾」原樣複製進來，是 DepWeaver 在 petclinic 真實叢集跑完、按 Generate report 後 Discord 交付的 14 個檔案（報告、各層 notes、執行期邊清單、egress 清單、流量腳本說明、相依圖 PNG）。放進 repo 是為了讓論文裡引用的數字有產物可查：

- COMPSAC 表 I「量測到」的例子：`spring-petclinic-microservices-updated-istio-runtime-observed-edge-ledger.md` 第 66–70 行，`istio-ingressgateway -> api-gateway` 累計 **121** 次（reporter=source）。較早一輪同一次執行量到的是 95 次（記在作者筆記），論文已改用這份清單的 121。
- 這批檔案裡**沒有**覆蓋率訊息（「4/4」是 Discord 上的覆蓋率回覆，不在交付包內），所以 RQ4 第 1 列「45% → 4/4」仍只有會議講稿與程式註解可查。
