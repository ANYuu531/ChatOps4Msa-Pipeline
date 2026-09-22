# Pure LLM vs the grounded pipeline

- archive: `dep-reports/824657585331372043-1790046661712.json` (GoogleCloudPlatform/bank-of-anthos, greenfield)
- model: `gpt-4.1-mini`, temperature 0; 10 questions × 3 arms
- gold answers: the graph query engine's own result for the labelled query
- report given to arm 1: 21199 of 21199 characters

Precision, recall and F1 are over the 8 questions whose graph answer names at least one node. The 2 whose graph answer is "nothing" are **not** scored automatically — counting names cannot tell a useful "they are unrelated, X and Y are the ones that use it" from a list of everything — they are read by hand in the detail below; the last column is how many services each arm named there beyond the ones the question itself spells.

| arm | precision | recall | F1 | off-graph names / question | of those, not in the report | names beyond the question, "nothing" questions |
|---|---|---|---|---|---|---|
| report-only | 0.861 | 0.722 | 0.779 | 0.10 | 0.10 | 9, 5 |
| rag | 0.986 | 0.837 | 0.898 | 1.20 | 0.20 | 9, 9 |
| depweaver | 1.000 | 0.963 | 0.978 | 0.00 | 0.00 | 0, 0 |

- chat calls: 31 (149847 prompt + 4680 completion tokens)

## Every question

### frontend 會呼叫哪些服務？

- gold (dependencies-of): [userservice, contacts, ledgerwriter, transactionhistory, balancereader, frontend]
- plan: router dependencies-of 0.81 (next dependents-of 0.69) → [dependencies-of(frontend)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告，frontend 會呼叫以下服務：
> 
> - userservice
> - contacts
> - balancereader
> - transactionhistory
> - ledgerwriter
> 
> 這些呼叫都是透過 HTTP 協議進行的，但具體的 HTTP 方法和端點路徑未明確記錄。

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告，frontend 會呼叫以下服務：
> 
> - userservice
> - contacts
> - balancereader
> - ledgerwriter
> - transactionhistory
> 
> 這些呼叫都是透過 HTTP 協議進行的直接應用程式呼叫，但具體的 HTTP 方法和端點路徑未明確記錄。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據本報告的靜態分析（無執行期流量觀察），frontend 會呼叫以下服務（同步 HTTP）：
> 
> - ledgerwriter
> - userservice
> - balancereader
> - transactionhistory
> - contacts
> 
> 這些呼叫均有程式碼與文件證據支持，但在本次分析中未觀察到執行期流量。證據主要來自 src/frontend/frontend.py 的相關程式碼行。

### Which services call ledgerwriter?

- gold (dependents-of): [ledgerwriter, frontend]
- plan: router dependents-of 0.90 (next dependencies-of 0.85) → [dependents-of(ledgerwriter)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the dependency analysis report, there is no documented or observed service that calls the ledgerwriter service. The report lists ledgerwriter as an application service but does not show any synchronous or asynchronous calls to it from other services. The only documented calls from frontend include ledgerwriter, but no other service calls ledgerwriter.

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the report, the service that calls ledgerwriter is:
> 
> - frontend
> 
> This is a direct application dependency over HTTP from frontend to ledgerwriter, documented with high confidence. No other services are documented as calling ledgerwriter.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the dependency graph facts, only the service "frontend" calls "ledgerwriter" via a synchronous HTTP call. This is documented in code and documentation but was not observed at runtime in this analysis. No other services call "ledgerwriter" in the graph.

### 如果 accounts-db 掛掉，哪些服務會受影響？

- gold (impact-of): [accounts-db, userservice, contacts, loadgenerator, frontend]
- plan: llm planner → [impact-of(accounts-db)]

**report-only** — P 1.00, R 0.60, off-graph names 0 (not in the report either: 0)

> 如果 accounts-db 掛掉，會受影響的服務有：
> 
> - userservice：依賴 accounts-db 來管理使用者帳戶資料。
> - contacts：依賴 accounts-db 來存取使用者聯絡資訊。
> 
> 這兩個服務都需要連接 accounts-db 資料庫，因此 accounts-db 掛掉會導致它們無法正常存取或更新相關資料。

**rag** — P 1.00, R 0.60, off-graph names 0 (not in the report either: 0)

> 如果 accounts-db 掛掉，會受影響的服務有：
> 
> - userservice：依賴 accounts-db 作為資料庫，存取使用者帳戶資訊。
> - contacts：依賴 accounts-db 作為資料庫，存取使用者聯絡資訊。
> 
> 這兩個服務都在程式碼和文件中宣告了對 accounts-db 的資料庫依賴，且在配置中注入了 accounts-db 的設定，但目前沒有觀察到運行時連線流量。accounts-db 是用來儲存使用者帳戶和相關資料的資料庫，若它不可用，userservice 和 contacts 服務將無法正常存取其所需的帳戶資料。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據依賴圖，若 accounts-db 掛掉，會影響以下服務（依距離排序）：
> 
> - 直接受影響（深度1）：userservice、contacts
> - 間接受影響（深度2）：frontend
> - 更遠層級（深度3）：loadgenerator
> 
> 這表示 accounts-db 是這些服務的資料庫依賴，若它故障，這些服務的功能可能會受損。此依賴是由程式碼宣告且有使用證據，但在本次分析中未觀察到執行期流量。

### What has to be running before frontend can serve a request?

- gold (startup-needs): [accounts-db, userservice, contacts, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, accountsdb, ledgerdb, postgresql]
- plan: router startup-needs 0.81 (next dependents-of 0.56) → [startup-needs(frontend)]

**report-only** — P 0.89, R 0.73, off-graph names 0 (not in the report either: 0)

> Before the frontend can serve a request, the following services and databases need to be running, as the frontend depends on them for its operations:
> 
> - userservice (manages authentication and user account management)
> - contacts (stores and retrieves user contact information)
> - balancereader (provides current account balance information)
> - transactionhistory (retrieves past financial transactions)
> - ledgerwriter (validates and records financial transactions)
> 
> Additionally, the loadgenerator service can simulate user traffic to the frontend, but it is not a prerequisite for the frontend to serve requests.
> 
> Each of these services may depend on their respective databases:
> 
> - userservice and contacts depend on accounts-db (stores user account information)
> - ledgerwriter, balancereader, and transactionhistory depend on ledger-db (stores financial transaction records)
> 
> Therefore, for the frontend to serve requests properly, the following must be running:
> 
> - userservice
> - contacts
> - balancereader
> - transactionhistory
> - ledgerwriter
> - accounts-db
> - ledger-db
> 
> This ensures that the frontend can call these services and they can access their required databases.

**rag** — P 0.89, R 0.73, off-graph names 1 (not in the report either: 1)

> Before the frontend service can serve a request, the following services and components need to be running and accessible, as the frontend depends on them for its operations:
> 
> - userservice: The frontend calls userservice for user-related data.
> - balancereader: The frontend calls balancereader to query account balances.
> - transactionhistory: The frontend calls transactionhistory to retrieve transaction history.
> - contacts: The frontend calls contacts for contact management.
> - ledgerwriter: The frontend calls ledgerwriter, likely for ledger updates or writes.
> 
> Additionally, the loadgenerator service calls the frontend, so it should be running if load testing or automated request generation is expected.
> 
> All these dependencies are documented as direct HTTP calls from frontend, although no runtime invocation evidence is observed yet. Also, the databases backing these services (accounts-db, ledger-db) should be running and reachable by their respective services, but the frontend itself does not directly connect to databases.
> 
> In summary, the backend services userservice, balancereader, transactionhistory, contacts, and ledgerwriter must be running and reachable before frontend can fully serve requests.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> Before the frontend service can serve a request, the following services and databases must be running and reachable, according to the dependency graph and implied startup order:
> 
> - Direct dependencies of frontend (tier 1):
>   - ledgerwriter (service)
>   - userservice (service)
>   - balancereader (service)
>   - transactionhistory (service)
>   - contacts (service)
> 
> - Dependencies of those services (tier 2):
>   - ledger-db (database)
>   - ledgerdb (database)
>   - accounts-db (database)
>   - accountsdb (database)
> 
> - Further dependency (tier 3):
>   - postgresql (database)
> 
> The implied startup order is from deepest tier first (databases) up to frontend:
> 1. Databases: accounts-db, ledger-db, accountsdb, ledgerdb, postgresql
> 2. Services: balancereader
> 3. Services: userservice, contacts, ledgerwriter, transactionhistory
> 4. Service: frontend
> 
> So all these must be running and reachable for frontend to work fully. This is based on declared code and documentation dependencies; no runtime calls were observed in this greenfield analysis.

### 哪些服務會直接存取資料庫？

- gold (db-users): [accounts-db, userservice, contacts, ledgerwriter, transactionhistory, balancereader, ledger-db, accountsdb, ledgerdb, postgresql]
- plan: router db-users 0.86 (next dependencies-of 0.56) → [db-users()]

**report-only** — P 1.00, R 0.70, off-graph names 0 (not in the report either: 0)

> 根據報告中的靜態分析，以下服務會直接存取資料庫：
> 
> 1. userservice 直接存取 accounts-db 資料庫。
> 2. contacts 直接存取 accounts-db 資料庫。
> 3. ledgerwriter 直接存取 ledger-db 資料庫。
> 4. balancereader 直接存取 ledger-db 資料庫。
> 5. transactionhistory 直接存取 ledger-db 資料庫。
> 
> 這些關係在文件和程式碼中都有中度到高度的信心支持，但沒有運行時的直接觀察證據。

**rag** — P 1.00, R 0.70, off-graph names 0 (not in the report either: 0)

> 根據報告內容，以下服務會直接存取資料庫：
> 
> 1. userservice 會直接存取 accounts-db（用戶帳戶資料庫）。
> 2. contacts 會直接存取 accounts-db。
> 3. ledgerwriter 會直接存取 ledger-db（交易紀錄資料庫）。
> 4. balancereader 會直接存取 ledger-db。
> 5. transactionhistory 會直接存取 ledger-db。
> 
> 這些關係皆為直接呼叫（direct_call），使用 TCP 協定，且在文件中有高信心的證據支持。

**depweaver** — P 1.00, R 0.70, off-graph names 0 (not in the report either: 0)

> 根據報告中的依賴關係，以下服務會直接存取資料庫（database）：
> 
> - userservice → accounts-db
> - contacts → accounts-db
> - ledgerwriter → ledger-db
> - transactionhistory → ledger-db
> - balancereader → ledger-db
> 
> 這些依賴都是以 TCP 協定直接呼叫資料庫，且有文件與程式碼宣告的使用證據，但在本次分析中未觀察到執行期流量。

### Which dependencies were never confirmed by runtime traffic?

- gold (unobserved-edges): [accounts-db, userservice, contacts, loadgenerator, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, accountsdb, ledgerdb, postgresql]
- plan: router uncovered 0.82 (next uncovered 0.54) → [uncovered(), unobserved-edges()]

**report-only** — P 0.00, R 0.00, off-graph names 0 (not in the report either: 0)

> All dependencies in this system were never confirmed by runtime traffic. The analysis is based solely on static documentation and source code references without any runtime invocation evidence. Specifically:
> 
> - No synchronous service-to-service calls were observed at runtime.
> - No database connections were observed at runtime.
> - No asynchronous communication was documented or observed.
> - All runtime observed statuses for dependencies and calls are marked as Unknown.
> 
> Therefore, none of the documented dependencies have been confirmed by runtime traffic.

**rag** — P 1.00, R 0.92, off-graph names 0 (not in the report either: 0)

> The following dependencies were declared in code or documentation but never confirmed by runtime traffic (0% runtime coverage):
> 
> Service-to-service edges (business edges):
> - ledgerwriter -> balancereader
> - frontend -> ledgerwriter
> - frontend -> userservice
> - frontend -> balancereader
> - frontend -> transactionhistory
> - frontend -> contacts
> - loadgenerator -> frontend
> 
> Datastore edges (database connections):
> - userservice -> accounts-db
> - contacts -> accounts-db
> - ledgerwriter -> ledger-db
> - transactionhistory -> ledger-db
> - balancereader -> ledger-db
> - userservice -> accountsdb
> - contacts -> accountsdb
> - ledgerwriter -> ledgerdb
> - balancereader -> ledgerdb
> - transactionhistory -> ledgerdb
> 
> All these dependencies have medium confidence based on static evidence but no runtime invocation or connection was observed.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> This report is a greenfield static analysis with no runtime traffic or cluster evidence collected. Therefore, all dependencies are declared in code/docs but none were confirmed by runtime traffic.
> 
> Specifically, these 19 edges were never observed at runtime (all are declared but unverified):
> 
> - ledgerwriter -> balancereader (sync-http)
> - frontend -> ledgerwriter (sync-http)
> - frontend -> userservice (sync-http)
> - frontend -> balancereader (sync-http)
> - frontend -> transactionhistory (sync-http)
> - frontend -> contacts (sync-http)
> - loadgenerator -> frontend (sync-http)
> - userservice -> accounts-db (db)
> - contacts -> accounts-db (db)
> - ledgerwriter -> ledger-db (db)
> - transactionhistory -> ledger-db (db)
> - balancereader -> ledger-db (db)
> - userservice -> accountsdb (db)
> - contacts -> accountsdb (db)
> - ledgerwriter -> ledgerdb (db)
> - balancereader -> ledgerdb (db)
> - transactionhistory -> ledgerdb (db)
> - accounts-db -> postgresql (db)
> - ledger-db -> postgresql (db)
> 
> All these dependencies have no runtime invocation evidence; they are based on code and documentation only. Runtime coverage is 0%. (See section 1 Graph Facts and 2 Runtime Coverage.)

### 圖上有哪些節點其實沒有部署起來？

- gold (undeployed): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router undeployed 0.62 (next deploy-order 0.51) → [undeployed()]

**report-only** — the graph answers "nothing" here — judge by reading: this answer names 9 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 根據報告內容，這是一個綠地（greenfield）靜態分析，沒有查詢任何 Kubernetes 叢集，因此所有服務都沒有被觀察到實際部署。
> 
> 報告中所有列出的微服務節點（frontend、userservice、contacts、ledgerwriter、balancereader、transactionhistory、loadgenerator、accounts-db、ledger-db）都標示為「未觀察到部署」（Observed in current namespace: No）。
> 
> 因此，圖上所有節點在目前分析中都沒有被部署起來。

**rag** — the graph answers "nothing" here — judge by reading: this answer names 9 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 根據報告內容，這是綠地（greenfield）靜態分析，沒有查詢到任何 Kubernetes 叢集的運行狀態，因此無法直接觀察到哪些節點實際有部署或運行。
> 
> 不過，從 Istio 的運行時流量覆蓋率來看，所有宣告的服務間呼叫邊（7 條）都沒有流量被觀察到（0/7），資料層的 TCP 連線邊（10 條）也都沒有被觀察到（0/10）。這表示目前沒有任何服務間的實際流量或資料庫連線被觸發，可能是因為服務尚未啟動或尚未被使用。
> 
> 因此，可以推測目前這些節點（包括 ledgerwriter、balancereader、frontend、userservice、transactionhistory、contacts、loadgenerator 以及相關的資料庫服務 accounts-db、ledger-db）可能都尚未實際部署或啟動。
> 
> 總結：
> - 所有主要服務節點（ledgerwriter、balancereader、frontend、userservice、transactionhistory、contacts、loadgenerator）
> - 以及資料庫節點（accounts-db、ledger-db）
> 
> 從運行時流量和連線觀察來看，均無實際部署或啟動的證據。

**depweaver** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> 本報告是在 greenfield 模式下執行的，沒有查詢任何叢集或部署狀態，因此所有節點的部署狀態都是未知的，無法判斷哪些節點沒有部署起來。
> 
> 依據【1. GRAPH FACTS】：
> - 部署狀態對所有節點都是未知（undeployed() 查詢結果）
> - 沒有任何節點被標示為已部署或未部署
> 
> 所以本報告無法提供哪些節點沒有部署的資訊。若要知道部署狀態，需要在有叢集的環境中重新執行分析並收集執行期證據。

### List every external host this system talks to.

- gold (externals): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router externals 0.66 (next dependencies-of 0.34) → [externals()]

**report-only** — the graph answers "nothing" here — judge by reading: this answer names 5 service(s) the question did not, off-graph names 1 (not in the report either: 1)

> According to the provided dependency analysis report for the GoogleCloudPlatform/bank-of-anthos system, there is no documented or observed evidence of communication with any external hosts outside the defined microservices and databases within the system.
> 
> All dependencies are internal between application services and databases (e.g., userservice, contacts, ledgerwriter, accounts-db, ledger-db, etc.). No external hosts or third-party services are mentioned or documented in the report.
> 
> Therefore, the system does not talk to any external hosts based on the available static analysis data.

**rag** — the graph answers "nothing" here — judge by reading: this answer names 9 service(s) the question did not, off-graph names 2 (not in the report either: 1)

> Based on the provided evidence, there is no indication that this system talks to any external hosts outside the defined internal services and databases. All hosts referenced (such as balancereader, transactionhistory, contacts, userservice, ledgerwriter, ledger-db, accounts-db, frontend, ledgermonolith-service, kopf) appear to be internal components or services within the system.
> 
> No external hosts or third-party services are documented or configured as communication targets in the available data.

**depweaver** — the graph answers "nothing" here — judge by reading: this answer names 0 service(s) the question did not, off-graph names 0 (not in the report either: 0)

> This report shows that the system has no external hosts in its dependency graph. All dependencies are internal services or databases within the system.
> 
> Evidence: The graph query for externals() returned no external hosts. (Section 1. GRAPH FACTS)

### 部署順序應該怎麼排？

- gold (deploy-order): [accounts-db, userservice, contacts, loadgenerator, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, accountsdb, ledgerdb, postgresql]
- plan: router deploy-order 0.76 (next undeployed 0.53) → [deploy-order()]

**report-only** — P 1.00, R 0.75, off-graph names 0 (not in the report either: 0)

> 根據報告中的靜態依賴關係，建議的部署順序如下：
> 
> 1. **資料庫服務先部署**：
>    - `accounts-db`（存放用戶帳戶資料）
>    - `ledger-db`（存放金融交易紀錄）
> 
> 2. **後端應用服務部署**，依照其對資料庫的依賴關係：
>    - `userservice`（依賴 `accounts-db`）
>    - `contacts`（依賴 `accounts-db`）
>    - `ledgerwriter`（依賴 `ledger-db`）
>    - `balancereader`（依賴 `ledger-db`）
>    - `transactionhistory`（依賴 `ledger-db`）
> 
> 3. **前端及流量模擬服務部署**：
>    - `frontend`（依賴多個後端服務：`userservice`、`contacts`、`balancereader`、`transactionhistory`、`ledgerwriter`）
>    - `loadgenerator`（依賴 `frontend`）
> 
> 此順序確保資料庫先可用，後端服務能正常連接資料庫，最後前端及流量模擬服務才能正常呼叫後端服務。

**rag** — P 1.00, R 0.75, off-graph names 8 (not in the report either: 0)

> 根據提供的 Kubernetes ConfigMap 注入和服務依賴關係，建議的部署順序如下：
> 
> 1. **accounts-db**  
>    - 因為多個服務（userservice、contacts、populate-accounts-db）依賴 accounts-db-config 和 environment-config。  
>    - 先部署資料庫服務，確保資料庫可用。
> 
> 2. **ledger-db**  
>    - ledgerwriter、transactionhistory、balancereader 等服務依賴 ledger-db-config 和 environment-config。  
>    - 先部署 ledger-db，確保帳本資料庫可用。
> 
> 3. **populate-accounts-db** 和 **populate-ledger-db**  
>    - 這些是初始化資料庫的 Job，依賴 accounts-db-config、ledger-db-config、demo-data-config 和 environment-config。  
>    - 在資料庫服務部署後執行，完成資料初始化。
> 
> 4. **userservice** 和 **contacts**  
>    - 這兩個服務依賴 accounts-db-config 和 environment-config。  
>    - 在 accounts-db 和初始化 Job 完成後部署。
> 
> 5. **ledgerwriter**、**transactionhistory**、**balancereader**  
>    - 這些服務依賴 ledger-db-config、service-api-config 和 environment-config。  
>    - 在 ledger-db 和初始化 Job 完成後部署。
> 
> 6. **frontend**  
>    - 依賴 demo-data-config、oauth-config、environment-config 和 service-api-config。  
>    - 需要後端服務（如 balancereader）先行部署。
> 
> 7. **loadgenerator**  
>    - 依賴 frontend 主機，通常是用來產生負載測試。  
>    - 在 frontend 部署完成後部署。
> 
> 總結：  
> - 先部署資料庫服務（accounts-db、ledger-db）  
> - 執行資料庫初始化 Job（populate-accounts-db、populate-ledger-db）  
> - 部署依賴資料庫的後端服務（userservice、contacts、ledgerwriter、transactionhistory、balancereader）  
> - 部署前端服務（frontend）  
> - 最後部署負載產生器（loadgenerator）
> 
> 此順序可確保服務依賴的 ConfigMap 和資料庫服務已就緒，避免啟動失敗。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據分析的相依關係與呼叫深度，建議的部署順序如下（由底層服務開始，確保每個服務啟動時其依賴已可用）：
> 
> 1. 第1層（tier 4）：accounts-db、ledger-db、accountsdb、ledgerdb、postgresql（資料庫層）
> 2. 第2層（tier 3）：balancereader
> 3. 第3層（tier 2）：userservice、contacts、ledgerwriter、transactionhistory
> 4. 第4層（tier 1）：frontend
> 5. 第5層（tier 0）：loadgenerator
> 
> 此順序確保資料庫先啟動，再依序啟動依賴它們的服務，最後是前端與負載產生器。  
> （依據 GRAPH FACTS 中 deploy-order() 查詢結果）

### transactionhistory 和 ledger-db 之間有沒有關係？

- gold (path): [transactionhistory, ledger-db]
- plan: router path 0.74 (next subgraph 0.58) → [path(transactionhistory, ledger-db)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據依賴分析報告，transactionhistory 與 ledger-db 之間存在關係：
> 
> - transactionhistory 是一個應用服務，負責檢索過去的金融交易。
> - ledger-db 是一個資料庫，存儲所有金融交易記錄。
> - transactionhistory 直接呼叫 ledger-db（依賴類型為資料庫，通訊協定為 TCP）。
> - 此依賴在文件和程式碼中都有記錄，但沒有運行時觀察到的證據。
> - 信心度高，依據 Bank of Anthos Overview 和交易流程序列圖。
> 
> 因此，transactionhistory 與 ledger-db 之間確實有依賴關係，transactionhistory 從 ledger-db 讀取交易資料。

**rag** — P 1.00, R 1.00, off-graph names 1 (not in the report either: 0)

> 根據報告內容，transactionhistory 和 ledger-db 之間確實存在關係：
> 
> - transactionhistory 依賴 ledger-db 作為資料庫，這是一種直接呼叫（direct_call）的資料庫依賴，使用 TCP 協議。
> - 這個依賴在文件和程式碼中都有記載，信心等級為中到高，但在運行時尚未觀察到實際流量。
> - transactionhistory 的配置中包含了 ledger-db 的設定（例如在 Kubernetes manifest 中有引用 ledger-db-config 的 ConfigMap）。
> - ledger-db 是一個資料庫，負責存儲所有金融交易記錄。
> 
> 總結：transactionhistory 服務會連接並使用 ledger-db 資料庫來取得過去的金融交易資料。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據本報告的依據：
> 
> - transactionhistory 服務與 ledger-db 資料庫之間有直接的資料庫依賴關係。
> - 這個依賴是由程式碼與文件中宣告並有使用證據支持，但在本次靜態（greenfield）分析中，尚未觀察到執行期的連線或呼叫。
> - 具體證據包括 transactionhistory 的 Kubernetes 配置檔（src/ledger/transactionhistory/k8s/base/transactionhistory.yaml）及 Bank of Anthos Overview 的交易流程圖。
> - ledger-db 是用來儲存所有金融交易紀錄的資料庫，transactionhistory 服務則是用來檢索過去的金融交易。
> 
> 總結：
> transactionhistory 與 ledger-db 之間存在明確的資料庫依賴關係，但本次分析未觀察到執行期的連線。此關係屬於「宣告於程式碼與文件中且有使用證據，但未在執行期觀察到」。

