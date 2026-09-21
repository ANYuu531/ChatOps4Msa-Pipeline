# Pure LLM vs the grounded pipeline

- archive: `dep-reports/824657585331372043-1788872505120.json` (GoogleCloudPlatform/bank-of-anthos, greenfield)
- model: `gpt-4.1-mini`, temperature 0; 10 questions × 3 arms
- gold answers: the graph query engine's own result for the labelled query
- report given to arm 1: 16275 of 16275 characters

Scored on the 8 questions whose graph answer names at least one node; the 2 whose answer is "nothing" are in the last column, where the right answer is to name no service.

| arm | precision | recall | F1 | off-graph names / question | of those, not in the report | "nothing" questions right |
|---|---|---|---|---|---|---|
| report-only | 0.924 | 0.847 | 0.861 | 0.50 | 0.00 | 2/2 |
| rag | 0.903 | 0.955 | 0.915 | 0.40 | 0.10 | 0/2 |
| depweaver | 0.938 | 1.000 | 0.958 | 0.20 | 0.00 | 2/2 |

- chat calls: 31 (124952 prompt + 4325 completion tokens)

## Every question

### frontend 會呼叫哪些服務？

- gold (dependencies-of): [userservice, contacts, ledgerwriter, transactionhistory, balancereader, frontend]
- plan: router dependencies-of 0.81 (next dependents-of 0.69) → [dependencies-of(frontend)]

**report-only** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告文件，frontend 會呼叫以下服務：
> 
> - userservice
> - contacts
> - balancereader
> - transactionhistory
> - ledgerwriter
> 
> 這些呼叫都是透過 HTTP 協議進行，但具體的 HTTP 方法和端點路徑未被明確記錄。

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告，frontend 會呼叫以下服務：
> 
> - userservice
> - contacts
> - ledgerwriter
> - balancereader
> - transactionhistory
> 
> 這些呼叫都是透過 HTTP 協議進行，且在設定檔中有對應的環境變數指向這些服務的主機名稱。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告中的靜態分析（未觀察到執行期流量），frontend 會呼叫以下五個服務（同步 HTTP 呼叫）：
> 
> - ledgerwriter
> - userservice
> - balancereader
> - transactionhistory
> - contacts
> 
> 這些依賴關係是由程式碼與文件共同宣告的，但在本次分析中沒有執行期觀察到呼叫流量。證據來自 src/frontend/frontend.py 的多行程式碼。

### Which services call ledgerwriter?

- gold (dependents-of): [ledgerwriter, frontend]
- plan: router dependents-of 0.90 (next dependencies-of 0.85) → [dependents-of(ledgerwriter)]

**report-only** — P 1.00, R 0.50, off-graph names 1 (not in the report either: 0)

> According to the dependency analysis report, there is no documented or observed service that calls the ledgerwriter service. The report lists ledgerwriter as an application service but does not show any synchronous service-to-service invocation targeting ledgerwriter. Therefore, no services are known to call ledgerwriter based on the available evidence.

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the report, the frontend service calls the ledgerwriter service. This is a direct application call over HTTP, documented with high confidence but without runtime observed evidence.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> According to the dependency graph facts, only the frontend service calls ledgerwriter. This is a synchronous HTTP call declared in code and documentation but not observed at runtime.
> 
> Summary:
> - Caller: frontend
> - Callee: ledgerwriter
> - Protocol: HTTP (sync)
> - Evidence: code (src/frontend/frontend.py:663) and documentation (Bank of Anthos Overview wiki page)
> - Runtime observed: No (greenfield run)

### 如果 accounts-db 掛掉，哪些服務會受影響？

- gold (impact-of): [accounts-db, userservice, contacts, frontend]
- plan: llm planner → [impact-of(accounts-db)]

**report-only** — P 1.00, R 0.75, off-graph names 0 (not in the report either: 0)

> 根據報告中的靜態依賴分析，如果 accounts-db 掛掉，會受影響的服務有：
> 
> - userservice：依賴 accounts-db 來管理用戶帳戶和認證。
> - contacts：依賴 accounts-db 來存取用戶聯絡資訊。
> 
> 這兩個服務都直接連接到 accounts-db 資料庫，若 accounts-db 無法使用，這些服務將無法正常存取所需的帳戶和聯絡資料，可能導致功能異常。

**rag** — P 1.00, R 0.75, off-graph names 0 (not in the report either: 0)

> 根據報告資料，accounts-db 是用來儲存使用者帳戶資訊和聯絡資料的資料庫。以下服務有依賴 accounts-db：
> 
> - userservice：管理使用者帳戶和認證，並簽發 JWT，依賴 accounts-db 作為資料庫。
> - contacts：管理聯絡人資訊，依賴 accounts-db 作為資料庫。
> 
> 這兩個服務都在文件中明確記載依賴 accounts-db，且依賴類型為資料庫，使用 TCP 協議，但目前沒有觀察到運行時的連線流量。
> 
> 因此，如果 accounts-db 掛掉，userservice 和 contacts 這兩個服務將會受到影響，因為它們無法存取所需的帳戶和聯絡人資料。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據圖譜事實，若 accounts-db 掛掉，會影響以下服務：
> 
> - userservice（直接依賴 accounts-db）
> - contacts（直接依賴 accounts-db）
> - frontend（間接依賴 accounts-db，透過 userservice 和 contacts）
> 
> 這三個服務會受到影響，因為它們直接或間接依賴 accounts-db 的資料庫功能。此資訊來自依賴圖譜的傳遞依賴分析。

### What has to be running before frontend can serve a request?

- gold (startup-needs): [accounts-db, userservice, contacts, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, postgresql]
- plan: router startup-needs 0.81 (next dependents-of 0.56) → [startup-needs(frontend)]

**report-only** — P 1.00, R 0.89, off-graph names 0 (not in the report either: 0)

> Before the frontend can serve a request, the following backend services and databases should be running, as the frontend depends on them to fulfill user requests:
> 
> - userservice (manages user accounts and authentication)
> - contacts (stores and retrieves user contact information)
> - balancereader (provides current account balance information)
> - transactionhistory (retrieves past financial transaction records)
> - ledgerwriter (processes and records financial transactions)
> 
> Additionally, the databases that these services depend on should be running:
> 
> - accounts-db (used by userservice and contacts)
> - ledger-db (used by ledgerwriter and balancereader)
> 
> These components are documented as dependencies of the frontend service, so they need to be available for the frontend to properly serve requests.

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> Before the frontend service can serve a request, the following backend services and databases it depends on should be running and reachable:
> 
> - Backend services:
>   - userservice
>   - contacts
>   - ledgerwriter
>   - balancereader
>   - transactionhistory
> 
> - Databases used by backend services (though frontend does not directly connect to them):
>   - accounts-db (used by userservice and contacts)
>   - ledger-db (used by ledgerwriter and balancereader)
>   - postgresql (used by userservice and ledgerwriter)
> 
> The frontend acts as the entry point routing requests to these backend services, so they need to be operational for full functionality. However, no runtime invocation evidence was observed, so this is based on static configuration and documentation.

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> Before the frontend service can serve a request, the following services and databases must be running and reachable, according to the dependency graph and startup order:
> 
> - Tier 3 (deepest, start first):
>   - accounts-db (database)
>   - ledger-db (database)
>   - postgresql (database)
> 
> - Tier 2:
>   - balancereader (service)
> 
> - Tier 1:
>   - userservice (service)
>   - contacts (service)
>   - ledgerwriter (service)
>   - transactionhistory (service)
> 
> - Tier 0 (entry point):
>   - frontend (service)
> 
> So the implied startup/deployment order is:
> accounts-db, ledger-db, postgresql → balancereader → userservice, contacts, ledgerwriter, transactionhistory → frontend
> 
> This ensures all dependencies are available for frontend to route requests properly.
> 
> (Evidence: Graph facts, tier and startup order section)

### 哪些服務會直接存取資料庫？

- gold (db-users): [accounts-db, userservice, contacts, ledgerwriter, balancereader, ledger-db, postgresql]
- plan: router db-users 0.86 (next dependencies-of 0.56) → [db-users()]

**report-only** — P 1.00, R 0.86, off-graph names 0 (not in the report either: 0)

> 根據報告中的靜態分析與文件記載，以下服務會直接存取資料庫：
> 
> 1. userservice 會直接存取 accounts-db（用戶帳戶資料庫）
> 2. contacts 會直接存取 accounts-db（用戶聯絡資訊資料庫）
> 3. ledgerwriter 會直接存取 ledger-db（交易紀錄資料庫）
> 4. balancereader 會直接存取 ledger-db（交易紀錄資料庫）
> 
> 這些關係均為文件中有明確記載，但無運行時觀察證據。

**rag** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告資料，下列服務會直接存取資料庫：
> 
> 1. userservice 直接存取 accounts-db 資料庫，用於儲存和檢索使用者資料。
> 2. contacts 直接存取 accounts-db 資料庫。
> 3. ledgerwriter 直接存取 ledger-db 資料庫。
> 4. balancereader 直接存取 ledger-db 資料庫（推測，因為 balancereader 依賴 ledger-db，且有相關環境變數設定）。
> 5. userservice 也有依賴 postgresql 資料庫（中介於 accounts-db 之下的資料庫）。
> 
> 這些關係多為文件中有明確記載，且有中高信心，但目前無運行時觀察到連線的證據。

**depweaver** — P 1.00, R 1.00, off-graph names 1 (not in the report either: 0)

> 根據報告中的依賴圖事實，以下服務會直接存取資料庫（database）：
> 
> - userservice：存取 accounts-db 和 postgresql
> - contacts：存取 accounts-db
> - ledgerwriter：存取 ledger-db 和 postgresql
> - balancereader：存取 ledger-db
> 
> 這些資料庫存取關係都是由文件和程式碼宣告且有使用證據，但在本次靜態分析中未觀察到執行期流量（runtime observed: no）。(證據來源：Bank of Anthos Overview wiki page、pom.xml、kubernetes-manifests/*.yaml)

### Which dependencies were never confirmed by runtime traffic?

- gold (unobserved-edges): [accounts-db, userservice, contacts, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, postgresql]
- plan: router uncovered 0.82 (next uncovered 0.54) → [uncovered(), unobserved-edges()]

**report-only** — P 1.00, R 0.89, off-graph names 2 (not in the report either: 0)

> All documented dependencies in this system were never confirmed by runtime traffic. Specifically, no runtime-observed synchronous service-to-service invocations or database connections were recorded. This includes:
> 
> - Synchronous calls from frontend to userservice, contacts, balancereader, transactionhistory, and ledgerwriter.
> - Database connections from userservice and contacts to accounts-db.
> - Database connections from ledgerwriter and balancereader to ledger-db.
> 
> The report states that runtime invocation confidence is unknown due to no runtime evidence collected (greenfield analysis with no cluster queried).

**rag** — P 1.00, R 1.00, off-graph names 2 (not in the report either: 0)

> The following dependencies were never confirmed by runtime traffic (no runtime-observed evidence):
> 
> Service-to-service (business) edges (0/6 observed):
> - ledgerwriter -> balancereader
> - frontend -> ledgerwriter
> - frontend -> userservice
> - frontend -> balancereader
> - frontend -> transactionhistory
> - frontend -> contacts
> 
> Data layer (datastore) edges (0/6 observed):
> - userservice -> accounts-db
> - contacts -> accounts-db
> - ledgerwriter -> ledger-db
> - balancereader -> ledger-db
> - userservice -> postgresql
> - ledgerwriter -> postgresql
> 
> All these dependencies are declared in code and documentation with medium to high confidence but have no runtime invocation evidence yet.

**depweaver** — P 1.00, R 1.00, off-graph names 1 (not in the report either: 0)

> All dependencies in this report were never confirmed by runtime traffic because this is a greenfield (static) run with no cluster or traffic observed. Specifically, these 12 edges are declared in code/docs but not observed at runtime:
> 
> - ledgerwriter -> balancereader (sync-http)
> - frontend -> ledgerwriter (sync-http)
> - frontend -> userservice (sync-http)
> - frontend -> balancereader (sync-http)
> - frontend -> transactionhistory (sync-http)
> - frontend -> contacts (sync-http)
> - userservice -> accounts-db (database)
> - contacts -> accounts-db (database)
> - ledgerwriter -> ledger-db (database)
> - balancereader -> ledger-db (database)
> - userservice -> postgresql (database)
> - ledgerwriter -> postgresql (database)
> 
> No runtime-observed edges exist in this analysis. All runtime invocation statuses are unknown due to the lack of runtime evidence (Istio saw 0/6 service edges and 0/6 datastore edges). This is expected in greenfield mode. (Sources: Graph facts, Runtime coverage, Report sections 1, 2, 3, 4, 5)

### 圖上有哪些節點其實沒有部署起來？

- gold (undeployed): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router undeployed 0.62 (next deploy-order 0.51) → [undeployed()]

**report-only** — negative question: correct (named no service), off-graph names 0 (not in the report either: 0)

> 根據報告內容，目前沒有查詢任何 Kubernetes 叢集或命名空間，因此無法得知任何服務是否已經部署起來。報告中多次提到「Observed in current namespace: No (no cluster queried)」表示所有節點（服務和資料庫）都沒有被觀察到在運行中。
> 
> 因此，從目前的靜態分析和文件資料來看，無法確認任何節點已經部署起來；換句話說，所有節點的部署狀態都是未知的，沒有證據顯示它們已經啟動或運行。

**rag** — negative question: wrong (named 11), off-graph names 0 (not in the report either: 0)

> 根據報告內容，這是一次綠地（greenfield）靜態分析，沒有查詢任何叢集或取得執行時的流量資料，因此無法確定哪些節點實際有部署起來。報告中多次提到「no cluster was queried」、「no runtime evidence」，表示沒有執行時的觀察資料。
> 
> 不過，從靜態代碼和配置來看，以下服務是有明確文件和配置的：
> 
> - accounts-db
> - userservice
> - contacts
> - loadgenerator
> - ledgerwriter
> - transactionhistory
> - balancereader
> - ledger-db
> - ledgermonolith
> - frontend
> - ui-tests
> 
> 但沒有任何執行時流量或連線被觀察到（0/6業務邊和0/6資料庫邊），也沒有叢集中觀察到這些服務的運行狀態。
> 
> 因此，無法判斷哪些節點「沒有部署起來」，只能說目前沒有執行時證據顯示它們正在運行。建議部署系統並重新分析以取得執行時資料，才能確定哪些節點實際有啟動。

**depweaver** — negative question: correct (named no service), off-graph names 0 (not in the report either: 0)

> 本報告是在 greenfield 模式下執行的，沒有查詢任何 Kubernetes 叢集，因此所有節點的部署狀態都是未知的，無法判斷哪些節點沒有部署起來。
> 
> 依據 1. GRAPH FACTS：
> - 所有節點的部署狀態均為未知（undeployed() 查詢結果）。
> - 沒有任何節點被標示為未部署。
> 
> 若要知道哪些節點實際有部署，需在有叢集的環境下重新執行分析並收集執行期證據。

### List every external host this system talks to.

- gold (externals): (none — the graph's answer is "nothing"; scored right/wrong)
- plan: router externals 0.66 (next dependencies-of 0.34) → [externals()]

**report-only** — negative question: correct (named no service), off-graph names 0 (not in the report either: 0)

> The dependency analysis report does not document or observe any external hosts that this system talks to. All dependencies and communications described are internal to the microservices and databases within the system.
> 
> Therefore, there are no known external hosts that this system communicates with based on the available evidence.

**rag** — negative question: wrong (named 10), off-graph names 2 (not in the report either: 1)

> Based on the provided evidence and static analysis, there are no external hosts (outside the documented microservices and infrastructure) that this system talks to. All service-to-service calls resolve to internal hosts within the Kubernetes cluster, such as:
> 
> - contacts
> - userservice
> - frontend
> - ledgerwriter
> - balancereader
> - transactionhistory
> - ledgermonolith-service
> - accounts-db
> - ledger-db
> - postgresql
> - kopf (pgpool.py)
> 
> Additionally, the system depends on cloud services like Google Kubernetes Engine (GKE), Cloud SQL, Cloud Trace, and observability backends (OpenTelemetry, Spring Cloud GCP Trace), but these are cloud services rather than external hosts directly called by the microservices.
> 
> No unknown or unresolved external hosts appear in the code or configuration.

**depweaver** — negative question: correct (named no service), off-graph names 0 (not in the report either: 0)

> According to the graph facts in this report, there are no external hosts in the system's dependency graph. The system does not talk to any external hosts based on the available evidence.

### 部署順序應該怎麼排？

- gold (deploy-order): [accounts-db, userservice, contacts, ledgerwriter, transactionhistory, balancereader, ledger-db, frontend, postgresql]
- plan: router deploy-order 0.76 (next undeployed 0.53) → [deploy-order()]

**report-only** — P 0.89, R 0.89, off-graph names 2 (not in the report either: 0)

> 根據報告中的靜態依賴關係與組件角色，建議的部署順序如下：
> 
> 1. **accounts-db**（資料庫）  
>    - 因為 userservice 和 contacts 服務都依賴此資料庫。
> 
> 2. **ledger-db**（資料庫）  
>    - ledgerwriter 和 balancereader 服務依賴此資料庫。
> 
> 3. **userservice**（應用服務）  
>    - 依賴 accounts-db。
> 
> 4. **contacts**（應用服務）  
>    - 依賴 accounts-db。
> 
> 5. **ledgerwriter**（應用服務）  
>    - 依賴 ledger-db。
> 
> 6. **balancereader**（應用服務）  
>    - 依賴 ledger-db。
> 
> 7. **transactionhistory**（應用服務）  
>    - 主要提供交易歷史，推測依賴 ledger-db（雖未明確指出，但與交易紀錄相關）。
> 
> 8. **frontend**（應用服務）  
>    - 依賴 userservice、contacts、balancereader、transactionhistory、ledgerwriter 等多個後端服務，應在後端服務部署完成後部署。
> 
> 9. **cloud-sql-proxy**（閘道）  
>    - 可視為基礎設施組件，應在需要連接 Cloud SQL 時部署，通常與資料庫同時或之前部署。
> 
> 10. **loadgenerator**（應用服務）  
>     - 用於模擬流量，應在系統主要服務部署完成後部署。
> 
> 11. **blackbox-exporter**（觀察性組件）  
>     - 用於監控，可在系統部署後或同時部署。
> 
> 總結：先部署資料庫，再部署依賴資料庫的後端服務，最後部署前端與測試、監控組件。

**rag** — P 0.73, R 0.89, off-graph names 0 (not in the report either: 0)

> 根據提供的服務目錄與相依性資訊，建議的部署順序如下：
> 
> 1. **資料庫服務**  
>    - accounts-db  
>    - ledger-db  
>    這兩個資料庫服務是其他服務的基礎，應先部署並初始化（例如執行 schema 建立腳本）。
> 
> 2. **後端服務**  
>    - userservice  
>    - contacts  
>    - ledgerwriter  
>    - balancereader  
>    - transactionhistory  
>    - ledgermonolith  
>    
>    這些服務依賴資料庫，且部分服務間有相互呼叫關係（例如 ledgermonolith 會呼叫 userservice、contacts、ledgerwriter、balancereader、transactionhistory），因此應在資料庫部署完成後依序部署。
> 
> 3. **前端服務**  
>    - frontend  
>    
>    前端服務依賴後端服務，應在後端服務部署完成後部署。
> 
> 4. **輔助服務**  
>    - loadgenerator  
>    - ui-tests  
>    
>    這些服務用於負載測試與 UI 測試，應在系統主要服務部署完成且穩定後再部署。
> 
> 此順序可確保基礎資料庫先行啟動，後端服務能正常連接資料庫並相互呼叫，最後前端與測試服務才開始運作。

**depweaver** — P 1.00, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據分析的相依關係圖，建議的部署順序（由最底層開始部署）如下：
> 
> 1. 第三層（tier 3）：accounts-db、ledger-db、postgresql（資料庫層）
> 2. 第二層（tier 2）：balancereader
> 3. 第一層（tier 1）：userservice、contacts、ledgerwriter、transactionhistory
> 4. 第零層（tier 0）：frontend
> 
> 此順序確保每個服務啟動時，其所依賴的下層服務已經在運行中。

### transactionhistory 和 ledger-db 之間有沒有關係？

- gold (path): [transactionhistory, ledger-db]
- plan: router path 0.74 (next subgraph 0.58) → [path(transactionhistory, ledger-db)]

**report-only** — P 0.50, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據依賴分析報告，transactionhistory 服務和 ledger-db 資料庫之間沒有直接記錄的同步呼叫或基礎設施依賴關係。報告中並未顯示 transactionhistory 直接依賴 ledger-db，只有 ledgerwriter 和 balancereader 服務有對 ledger-db 的資料庫依賴。
> 
> 因此，transactionhistory 和 ledger-db 之間沒有明確的關係記錄。

**rag** — P 0.50, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據報告資料，transactionhistory 服務的角色是「檢索帳戶的過去金融交易記錄」，且有文件記載其配置存在，但在目前命名空間中未觀察到其運行時證據。
> 
> ledger-db 是一個資料庫，負責儲存所有金融交易記錄，文件中有高信心的記載，但無運行時證據。
> 
> 報告中並未直接提及 transactionhistory 服務與 ledger-db 之間有直接的依賴或呼叫關係。相反地，ledgerwriter 和 balancereader 兩個服務明確記錄了與 ledger-db 的資料庫依賴關係。
> 
> 因此，根據現有資料，transactionhistory 與 ledger-db 之間沒有明確的直接關係或依賴。

**depweaver** — P 0.50, R 1.00, off-graph names 0 (not in the report either: 0)

> 根據本報告的圖形事實，transactionhistory 與 ledger-db 之間沒有直接邊，也沒有任何方向的有向路徑（即沒有依賴關係或調用路徑）。
> 
> 具體說明：
> - transactionhistory 沒有任何對外依賴（outgoing edges）。
> - ledger-db 是被 ledgerwriter 和 balancereader 兩個服務依賴的資料庫，但與 transactionhistory 無關。
> - 這些依賴都是從程式碼和文件中宣告的，但在本次靜態分析（greenfield 模式）中沒有執行期觀察到任何流量。
> 
> 因此，transactionhistory 與 ledger-db 之間在此報告中沒有關係。

