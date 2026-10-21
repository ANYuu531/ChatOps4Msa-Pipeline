# DepWeaver 分析存檔（機器 B `dep-reports/`，2026-10-21 複製進 repo）

每一份是一次「Generate report」後存下的完整模型：`graph`（節點與邊）、`coverage`（覆蓋率訊息原文）、`report`（報告原文）、`evidence`／`provenance`（出處帳）、`chunks`（問答檢索用的段落）。原本只留在機器 B 且 7 天輪替，這裡是論文數字的產物。檔名後半是建立時間（epoch ms）。

## 索引：哪份存檔支撐論文哪個數字

| 存檔（尾碼） | 日期 | 專案 | 模式 | 覆蓋率訊息 | 論文裡的用途 |
|---|---|---|---|---|---|
| 1788687921056 | 2026-09-06 | Bank of Anthos | runtime | 業務 7/7、資料層 5/5 | RQ2 |
| 1788860520357 | 2026-09-08 | Bank of Anthos | runtime | 7/7、5/5 | RQ2 |
| 1788863698794 | 2026-09-08 | Bank of Anthos | greenfield | **「Istio observed 0/7 — 0%」、資料層 0/10** | RQ4 第 7 列的「修正前」：純靜態模式把未量測寫成 0% |
| 1788870576909 | 2026-09-08 | Bank of Anthos | greenfield | 0/7、0/5 | 同上 |
| 1788872505120 | 2026-09-08 | Bank of Anthos | greenfield | 0/6、0/6 | 同上（修到一半） |
| 1788873374309 | 2026-09-08 | train-ticket | greenfield | （無） | 分層與回歸測試用的 train-ticket 圖（55 節點 66 邊，含文件層） |
| 1789387332698 | 2026-09-14 | Bank of Anthos | runtime | 7/7、**資料層 5/10** | 文件層用去連字號的拼法另造了一份資料庫（accountsdb、ledgerdb…），分母被稀釋；9/23 的併名規則修掉（threshold-design §9.4c） |
| 1789441580055 | 2026-09-15 | Bank of Anthos | runtime | 7/7、5/5 | RQ2 |
| 1790044430313 | 2026-09-22 | Bank of Anthos | runtime | 7/7、5/5；transactionhistory → ledger-db 5,191 次 TCP | **RQ2 主要存檔**；問答對照第二輪（threshold-design §9.4b） |
| 1790046661712 | 2026-09-22 | Bank of Anthos | greenfield | 0/7、0/10（修正前措辭） | 問答對照第三輪（§9.4c）；RQ2 表 VIII「靜態（修正後）」那一列的圖 |
| 1791201314868 | 2026-10-05 | spring-cloud-microservice | greenfield | 「0/22 — 0%」 | 名稱確認表單還沒修好之前的一次（報告沒有「Names resolved by the operator」一節），不算 RQ3 的五次 |
| 1791261458214 | 2026-10-06 04:37 | spring-cloud-microservice | greenfield | 「0/23 — 0%」 | **RQ3 第一次 Discord 真跑**：操作者答了 8 個名稱（alias-ask-design §5.1） |
| 1791275041858 | 2026-10-06 08:24 | 同上 | greenfield | **「Not measured」** | **第二次**：累計 11 個名稱（8＋離線補問的 3）；覆蓋率訊息已改成未量測（RQ4 第 7 列「修正後」） |
| 1791280049636 | 2026-10-06 09:47 | 同上 | greenfield | 「Not measured」 | **第三次**：累計 16（新問 5 個） |
| 1791286099431 | 2026-10-06 11:28 | 同上 | greenfield | 「Not measured」 | **第四次**：累計 20（新問 4 個，其中 2 個是逗號未拆的 bug） |

**沒有存檔的**：RQ3 第五次真跑（問 2 題：ZooKeeper 與 repo 名；該次沒有按 Generate report 或已被 7 天輪替清掉）；RQ3 的流量參數實驗（2026-08-26 關閉第 1 層的 Bank of Anthos 執行，5/7 → 6/7 → 7/7）；RQ4 第 1 列的 petclinic 45% → 4/4（2026-07-29／30）。這三件只有 Discord 對話與會議講稿可查。

## 怎麼讀

```bash
python3 -c "import json; d=json.load(open('docs/experiments/archives/824657585331372043-1790044430313.json')); print(d['coverage'])"
```

`report` 裡「# Names resolved by the operator」一節是累計套用的操作者答案；`graph.edges` 每條帶 `confidence` 與出處清單。
