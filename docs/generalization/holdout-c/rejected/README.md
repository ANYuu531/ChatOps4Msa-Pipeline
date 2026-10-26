# 選系統時被淘汰的候選（留底，不計分）

## mehdihadeli/food-delivery-microservices @ a5750a994acfb64c8d939a3b622bbd0504ce1796（2026-10-14）

原本被點名為「非 JVM」候選。抓下來核對後不符合「≥6 個服務」的門檻：

- `src/Services/` 底下 16 個目錄，只有 Catalogs（122 個 .cs）、Customers（126）、Identity（111）、Orders（27）有程式碼，其餘 11 個是 2 個檔案的空殼。
- 部署描述檔實際只部署 4 個應用服務：`deployments/docker-compose/docker-compose.services.yaml` 的 gateway、catalogs、identity、customers；Orders 只有 Aspire AppHost（開發期），沒有 compose／k8s 條目。
- `deployments/k8s/kubernetes/{catalogs,customers,identity,orders}.yaml` 各 105 bytes，是只有註解的空檔；kustomize base 指向不存在的檔案。
- 連線程式碼在 `src/BuildingBlocks`（git submodule，tarball 裡是空的），而且所有 appsettings 都寫 localhost，容器主機名幾乎沒有靜態出現（Aspire 執行期注入）。

`food-delivery-microservices.tsv` 是淘汰前已寫好的參考邊集（business 2、data 11、external 2、control 2、variant 42），留在這裡備查；`score.py` 只讀 `../truth/`，不會計到它。

改選 aspnetrun/run-aspnetcore-microservices（.NET 8，6 個應用服務＋5 個基礎設施，compose 描述檔）。
