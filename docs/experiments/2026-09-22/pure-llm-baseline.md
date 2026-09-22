# Pure LLM vs the grounded pipeline

- archive: `dep-reports/824657585331372043-1790044430313.json` (GoogleCloudPlatform/bank-of-anthos, runtime)
- model: `gpt-4.1-mini`, temperature 0; 10 questions × 3 arms
- gold answers: the graph query engine's own result for the labelled query
- report given to arm 1: 19775 of 19775 characters

Precision, recall and F1 are over the 7 questions whose graph answer names at least one node. The 3 whose graph answer is "nothing" are **not** scored automatically — counting names cannot tell a useful "they are unrelated, X and Y are the ones that use it" from a list of everything — they are read by hand in the detail below; the last column is how many services each arm named there beyond the ones the question itself spells.

| arm | precision | recall | F1 | off-graph names / question | of those, not in the report | names beyond the question, "nothing" questions |
|---|---|---|---|---|---|---|
| report-only | 0.984 | 0.943 | 0.956 | 0.20 | 0.00 | 8, 8, 0 |
| rag | 0.982 | 0.885 | 0.925 | 0.50 | 0.30 | 0, 8, 0 |
| depweaver | 1.000 | 1.000 | 1.000 | 0.10 | 0.00 | 0, 3, 0 |

- chat calls: 31 (142311 prompt + 3969 completion tokens)

## Every question

### frontend 會呼叫哪些服務？

- gold (dependencies-of): [frontend, ledgerwriter, balancereader, transactionhistory, userservice, contacts]
- plan: router dependencies-of 0.81 (next dependents-of 0.69) → [dependencies-of(frontend)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告，frontend 會呼叫以下服務：
> 
> - ledgerwriter
> - balancereader
> - transactionhistory
> - userservice
> - contacts
> 
> 這些呼叫都是同步的 HTTP 服務呼叫，且在運行時有被觀察到。

**rag** — P 1.00, R 0.83, off-graph names 0 (not in the report either: 0)

> 根據報告資料，frontend 會呼叫以下服務：
> 
> - userservice
> - ledgerwriter
> - contacts
> - balancereader
> 
> 這些呼叫都是透過 HTTP 協議進行，且在運行時有觀察到請求紀錄。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告中圖譜的事實，frontend 會呼叫以下服務（皆為同步 HTTP 呼叫，且在執行期有觀察到流量）：
> 
> - ledgerwriter
> - balancereader
> - transactionhistory
> - userservice
> - contacts
> 
> 這些呼叫都有執行期的請求數據支持，且在程式碼與文件中都有宣告與說明。

### Which services call ledgerwriter?

- gold (dependents-of): [frontend, ledgerwriter]
- plan: router dependents-of 0.90 (next dependencies-of 0.85) → [dependents-of(ledgerwriter)]

**report-only** — P 1.00, R 1.00, off-graph names 1 (not in the report either: 0)

> According to the dependency analysis report, the service that calls ledgerwriter is:
> 
> - frontend
> 
> This is confirmed by runtime-observed synchronous invocation evidence. No other services are documented or observed to call ledgerwriter directly.

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the report, the frontend service calls the ledgerwriter service. This is a direct HTTP application call with high confidence, observed at runtime via istio_requests_total metric. No other services are documented as calling ledgerwriter.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> Only the service "frontend" calls "ledgerwriter" in this graph.
> 
> - Edge: frontend -> ledgerwriter
> - Type: synchronous HTTP call
> - Runtime observed: Yes (6 requests)
> - Evidence: istio_requests_total metric
> 
> No other services call ledgerwriter according to the current dependency graph and runtime evidence.

### 如果 accounts-db 掛掉，哪些服務會受影響？

- gold (impact-of): [istio-ingressgateway, frontend, userservice, contacts, accounts-db]
- plan: llm planner → [impact-of(accounts-db)]

**report-only** — P 1.00, R 0.60, off-graph names 0 (not in the report either: 0)

> 根據報告，accounts-db 是用來儲存使用者帳戶資訊和聯絡資料的資料庫。runtime 觀察到以下服務有直接的資料庫連線依賴於 accounts-db：
> 
> - userservice
> - contacts
> 
> 因此，如果 accounts-db 掛掉，userservice 和 contacts 這兩個服務會受到影響，因為它們無法存取所需的帳戶資料庫。其他服務沒有直接依賴 accounts-db 的資料庫連線，理論上不會直接受影響。

**rag** — P 1.00, R 0.60, off-graph names 0 (not in the report either: 0)

> 如果 accounts-db 掛掉，會受影響的服務有：
> 
> - userservice：因為 userservice 直接依賴 accounts-db 的資料庫來儲存使用者帳戶資訊，且在執行時有 TCP 連線被觀察到。
> - contacts：contacts 也直接依賴 accounts-db 的資料庫來儲存使用者聯絡資訊，且在執行時有 TCP 連線被觀察到。
> 
> 這兩個服務都需要 accounts-db 提供的資料庫功能，accounts-db 掛掉會導致它們無法正常存取所需的資料。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 如果 accounts-db 掛掉，會受影響的服務有 4 個，依距離深度排序如下：
> 
> - depth 1（直接依賴）：userservice、contacts
> - depth 2：frontend
> - depth 3：istio-ingressgateway
> 
> 這是根據依賴圖計算的結果，accounts-db 是這些服務的底層資料庫節點，故其故障會影響這些服務的正常運作。 (參考 GRAPH FACTS)

### What has to be running before frontend can serve a request?

- gold (startup-needs): [frontend, ledgerwriter, balancereader, transactionhistory, userservice, contacts, ledger-db, accounts-db]
- plan: router startup-needs 0.81 (next dependents-of 0.56) → [startup-needs(frontend)]

**report-only** — P 0.89, R 1.00, off-graph names 0 (not in the report either: 0)

> Before the frontend can serve a request, the following services and components must be running and ready:
> 
> 1. **frontend** service and its pods (serving the web UI and acting as entry point).
> 2. Backend services that frontend calls synchronously at runtime:
>    - **ledgerwriter**
>    - **balancereader**
>    - **transactionhistory**
>    - **userservice**
>    - **contacts**
> 3. Databases accessed by these backend services:
>    - **ledger-db** (accessed by ledgerwriter, balancereader, transactionhistory)
>    - **accounts-db** (accessed by userservice and contacts)
> 
> Additionally, the **istio-ingressgateway** must be running to route external requests to the frontend.
> 
> All these components are observed running and ready in the namespace, and runtime evidence confirms synchronous calls and database connections among them.

**rag** — P 0.88, R 0.88, off-graph names 1 (not in the report either: 0)

> Before the frontend can serve a request, the istio-ingressgateway must be running because it acts as the ingress gateway to the frontend. This is confirmed by the runtime-observed edge from istio-ingressgateway to frontend with 76 observed requests.
> 
> Additionally, the frontend depends on several backend services to fulfill its requests, as evidenced by runtime-observed synchronous calls from frontend to these services:
> - userservice
> - balancereader
> - ledgerwriter
> - contacts
> - transactionhistory
> 
> These backend services should be running and reachable for the frontend to fully serve requests.
> 
> In summary, the following must be running before frontend can serve a request:
> 1. istio-ingressgateway (ingress gateway)
> 2. Backend services: userservice, balancereader, ledgerwriter, contacts, transactionhistory
> 
> Also, the ledger-db database is a critical infrastructure dependency for balancereader, ledgerwriter, and transactionhistory, so it should be available as well.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> Before the frontend service can serve a request, the following services and databases must be running and reachable, according to the dependency graph and implied startup order:
> 
> - Databases (deepest tier, tier 4):
>   - ledger-db
>   - accounts-db
> 
> - Services (tier 3 and tier 2):
>   - balancereader (tier 3)
>   - ledgerwriter (tier 2)
>   - transactionhistory (tier 2)
>   - userservice (tier 2)
>   - contacts (tier 2)
> 
> These are the direct and transitive dependencies of frontend, which is tier 1. The implied startup order is:
> 
> ledger-db, accounts-db → balancereader → ledgerwriter, transactionhistory, userservice, contacts → frontend
> 
> This ensures all dependencies are available for frontend to function fully. (Source: GRAPH FACTS, Query startup-needs(frontend))

### 哪些服務會直接存取資料庫？

- gold (db-users): [ledgerwriter, balancereader, transactionhistory, userservice, contacts, ledger-db, accounts-db]
- plan: router db-users 0.86 (next dependencies-of 0.56) → [db-users()]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告，以下服務會直接存取資料庫：
> 
> 1. userservice 直接存取 accounts-db 資料庫  
> 2. contacts 直接存取 accounts-db 資料庫  
> 3. ledgerwriter 直接存取 ledger-db 資料庫  
> 4. balancereader 直接存取 ledger-db 資料庫  
> 5. transactionhistory 直接存取 ledger-db 資料庫  
> 
> 這些關係均有運行時 TCP 連線數據支持，信心度高。

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告內容，以下服務會直接存取資料庫：
> 
> 1. userservice 直接存取 accounts-db 資料庫，用於儲存使用者帳戶資訊。
> 2. contacts 直接存取 accounts-db 資料庫，用於儲存使用者聯絡資訊。
> 3. ledgerwriter 直接存取 ledger-db 資料庫，用於記錄金融交易。
> 4. transactionhistory 直接存取 ledger-db 資料庫。
> 5. balancereader 直接存取 ledger-db 資料庫。
> 
> 這些服務與資料庫之間的連線均有高信心的運行時 TCP 連線觀察證據。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據本報告的依據，以下服務會直接存取資料庫（runtime觀察到TCP連線）：
> 
> - ledgerwriter 直接存取 ledger-db
> - balancereader 直接存取 ledger-db
> - transactionhistory 直接存取 ledger-db
> - userservice 直接存取 accounts-db
> - contacts 直接存取 accounts-db
> 
> 這些都是高信心且有runtime觀察證據的直接資料庫依賴。

### Which dependencies were never confirmed by runtime traffic?

- gold (unobserved-edges): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router uncovered 0.82 (next uncovered 0.54) → [uncovered(), unobserved-edges()]

**report-only** — the graph answers "nothing" here — judge by reading: this answer names 8 service(s) the question did not, off-graph names 1 (not in the report either: 0)

> According to the report, all documented synchronous and database dependencies listed have runtime evidence confirming their existence, except for any asynchronous communication, which was not observed at all.
> 
> Specifically:
> 
> - All synchronous calls between frontend, ledgerwriter, balancereader, transactionhistory, userservice, and contacts have runtime-observed edges.
> - All database connections from userservice and contacts to accounts-db, and from ledgerwriter, balancereader, and transactionhistory to ledger-db have runtime-observed TCP connections.
> - No asynchronous communication or broker relationships were observed or documented.
> 
> Therefore, the dependencies never confirmed by runtime traffic are:
> 
> - Any asynchronous communication dependencies (none observed or documented).
> - Any synchronous calls or dependencies documented but not runtime-observed are not explicitly listed, implying all documented synchronous dependencies have runtime evidence.
> 
> In summary, no documented synchronous or database dependencies lack runtime traffic confirmation; only asynchronous dependencies are missing runtime confirmation because none exist or were observed.

**rag** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 1 (not in the report either: 0)

> According to the report, all documented internal service-to-service business edges (7/7) and all data layer datastore edges (5/5) were confirmed by runtime traffic, with 100% runtime coverage observed.
> 
> However, there is a noted gap in runtime evidence for external dependencies: no runtime evidence of external egress edges was observed in either HTTP or TCP queries. This means that any external dependencies (outside the mesh) were not confirmed by runtime traffic.
> 
> In summary:
> - All internal service-to-service and database dependencies were confirmed by runtime traffic.
> - No external dependencies were confirmed by runtime traffic because no external egress traffic was observed.
> 
> Therefore, the dependencies never confirmed by runtime traffic are the external dependencies (if any exist), but no specific external dependencies are named in the report.

**depweaver** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 1 (not in the report either: 0)

> According to the report's graph facts and runtime coverage:
> 
> - All 7 business (service-to-service) edges were observed at runtime (100% coverage).
> - All 5 data layer (database) edges were observed at runtime (100% coverage).
> - No edges were unobserved; every declared dependency was confirmed by runtime traffic.
> 
> Therefore, there are no dependencies in this report that were never confirmed by runtime traffic. Every dependency has runtime evidence.

### 圖上有哪些節點其實沒有部署起來？

- gold (undeployed): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router undeployed 0.62 (next deploy-order 0.51) → [undeployed()]

**report-only** — the graph answers "nothing" here — judge by reading: this answer names 8 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 根據報告內容，所有在 namespace `bank-of-anthos` 中列出的服務（accounts-db、balancereader、contacts、frontend、ledger-db、ledgerwriter、transactionhistory、userservice）都有對應的 Service 和 Pod（或 Deployment）在運行中，且 Pod 狀態均為 Running 且 Ready。
> 
> 報告中並未指出有任何節點（服務）未部署或未運行。
> 
> 因此，圖上所有節點皆已部署並運行。

**rag** — the graph answers "nothing" here — judge by reading: this answer names 8 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 根據報告內容，所有在 Kubernetes 中觀察到的服務（accounts-db、balancereader、contacts、frontend、ledger-db、ledgerwriter、transactionhistory、userservice）都有對應的部署（Deployment）和 Pod，且 EndpointSlices 完整映射，表示這些節點都有部署起來。
> 
> 唯一提到但缺乏部署或運行細節的是 loadgenerator 服務，報告指出其監聽端口和部署狀態未明確記錄，且未包含在運行時觀察的組件中，暗示 loadgenerator 可能沒有實際部署或未運行。
> 
> 因此，圖上節點中，loadgenerator 可能沒有部署起來，其餘節點皆有部署。

**depweaver** — the graph answers "nothing" here — judge by reading: this answer names 3 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 根據本報告的圖形事實（GRAPH FACTS）：
> 
> - 所有被參考的工作負載（節點）都是已部署的（undeployed() 查詢結果顯示沒有未部署節點）。
> - 節點中 postgresql、in-memory-cache、caching 雖然存在，但報告指出它們沒有任何邊（沒有被呼叫或呼叫其他節點），但並未說明它們未部署，只是沒有發現相依或流量。
> 
> 因此，圖上沒有節點是「未部署」的。所有節點都已部署。

### List every external host this system talks to.

- gold (externals): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router externals 0.66 (next dependencies-of 0.34) → [externals()]

**report-only** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> According to the dependency analysis report for the `bank-of-anthos` namespace, there is no evidence or documentation of this system communicating with any external hosts. All observed and documented dependencies are internal to the namespace, involving services, databases, and components within the system.
> 
> Therefore, the system does not talk to any external hosts based on the available evidence.

**rag** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 1 (not in the report either: 1)

> No external hosts are observed or documented as being called by this system. Runtime evidence shows no external egress edges or traffic-driven evidence of external dependencies. The system may make external calls, but none were observed in HTTP or TCP queries, and no ServiceEntry manifests for external hosts are declared. Therefore, no external hosts can be listed from the available data.

**depweaver** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> According to the graph facts and runtime evidence in this report:
> 
> - No external hosts are present in the dependency graph.
> - No external egress edges were observed at runtime by Istio.
> - No external calls were detected in HTTP or TCP traffic.
> - No ServiceEntry manifests for external hosts were found or applied.
> 
> Therefore, this system does not talk to any external hosts as per the current runtime and code evidence. If external calls are expected, the report recommends applying ServiceEntry manifests and driving traffic to capture such dependencies.

### 部署順序應該怎麼排？

- gold (deploy-order): [istio-ingressgateway, frontend, ledgerwriter, balancereader, transactionhistory, userservice, contacts, ledger-db, accounts-db]
- plan: router deploy-order 0.76 (next undeployed 0.53) → [deploy-order()]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告中各服務與資料庫的依賴關係，建議的部署順序如下：
> 
> 1. **accounts-db**（資料庫）
>    - 因為 userservice 和 contacts 服務都依賴 accounts-db。
> 
> 2. **ledger-db**（資料庫）
>    - 因為 ledgerwriter、balancereader 和 transactionhistory 服務都依賴 ledger-db。
> 
> 3. **userservice**（應用服務）
>    - 依賴 accounts-db。
> 
> 4. **contacts**（應用服務）
>    - 依賴 accounts-db。
> 
> 5. **ledgerwriter**（應用服務）
>    - 依賴 ledger-db 和 balancereader。
> 
> 6. **balancereader**（應用服務）
>    - 依賴 ledger-db。
> 
> 7. **transactionhistory**（應用服務）
>    - 依賴 ledger-db。
> 
> 8. **frontend**（應用服務）
>    - 依賴 ledgerwriter、balancereader、transactionhistory、userservice 和 contacts。
> 
> 9. **istio-ingressgateway**（入口網關）
>    - 依賴 frontend。
> 
> 此順序確保資料庫先行部署，後續服務依賴的資料庫已可用，再依序部署依賴關係較少的服務，最後部署依賴多個後端服務的 frontend 及入口網關。

**rag** — P 1.00, R 0.89, off-graph names 2 (not in the report either: 2)

> 根據提供的資訊，部署順序建議依照服務的依賴關係與資料庫初始化順序來安排，具體如下：
> 
> 1. **accounts-db** 與 **ledger-db**：這兩個是資料庫服務，且有對應的初始化工作（populate-accounts-db、populate-ledger-db），應先部署並初始化資料庫。
> 
> 2. **populate-accounts-db** 與 **populate-ledger-db**：在資料庫服務啟動後，執行資料庫初始化作業。
> 
> 3. 其他後端服務：
>    - **userservice**
>    - **contacts**
>    - **ledgerwriter**
>    - **transactionhistory**
>    - **balancereader**
> 
>    這些服務依賴資料庫，應在資料庫初始化完成後部署。
> 
> 4. **frontend**：前端服務依賴後端服務，應在後端服務部署完成後啟動。
> 
> 5. **loadgenerator**：負載產生器，通常用於測試，應在系統其他服務都部署完成且穩定後啟動。
> 
> 總結部署順序：
> 
> 1. accounts-db、ledger-db
> 2. populate-accounts-db、populate-ledger-db（資料初始化）
> 3. userservice、contacts、ledgerwriter、transactionhistory、balancereader
> 4. frontend
> 5. loadgenerator
> 
> 此順序可確保資料庫先就緒，後端服務能正常連接資料庫，前端能正常呼叫後端，最後進行負載測試。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據本報告中計算出的部署順序（由最深層 tier 開始，確保每個服務啟動時其依賴已在運行），建議的部署順序如下：
> 
> - 第1步（tier 4）：ledger-db、accounts-db（資料庫層）
> - 第2步（tier 3）：balancereader
> - 第3步（tier 2）：ledgerwriter、transactionhistory、userservice、contacts
> - 第4步（tier 1）：frontend
> - 第5步（tier 0）：istio-ingressgateway
> 
> 此順序確保每個服務啟動時，其所依賴的下層服務已經可用。

### transactionhistory 和 ledger-db 之間有沒有關係？

- gold (path): [transactionhistory, ledger-db]
- plan: router path 0.74 (next subgraph 0.58) → [path(transactionhistory, ledger-db)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 有，transactionhistory 與 ledger-db 之間存在關係。
> 
> 根據報告第4節「Synchronous Dependency Candidates」和第5節「Infrastructure Dependencies」：
> - transactionhistory 以 TCP 協議直接連接到 ledger-db，屬於資料庫依賴。
> - 此連接在運行時被觀察到，且有 5191 個 TCP 連線數據，顯示兩者間有實際的資料庫連接。
> - 這表示 transactionhistory 會存取 ledger-db 中的金融交易記錄資料。
> 
> 因此，transactionhistory 與 ledger-db 之間確實存在運行時的資料庫依賴關係。

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 有，transactionhistory 與 ledger-db 之間存在直接的資料庫依賴關係。根據多項報告和程式碼證據：
> 
> - 依賴類型為資料庫（database）。
> - 透過 TCP 協議進行連線，且在執行時觀察到大量 TCP 連線（約 5191 個連線）。
> - 交易歷史服務（transactionhistory）使用 ledger-db 來存取金融交易紀錄。
> - 這個依賴關係在文件中有記載，且在執行時被確認。
> 
> 因此，transactionhistory 服務確實依賴 ledger-db 來取得或存取交易資料。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 有，transactionhistory 與 ledger-db 之間有直接的資料庫依賴關係：
> 
> - transactionhistory 會透過 TCP 連線存取 ledger-db。
> - 這個依賴在執行期被觀察到，有 5191 個 TCP 連線次數紀錄（不是請求數）。
> - 依賴關係的信心度高，且在程式碼與執行期都有證據支持。
> - ledger-db 是一個資料庫節點，存放金融交易紀錄，transactionhistory 服務用來取得過去的交易資料。
> 
> （以上資訊來自執行期觀察與程式碼分析）

