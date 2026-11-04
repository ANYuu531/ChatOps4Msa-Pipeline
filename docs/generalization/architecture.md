# 靜態層架構：照四個慣例切（2026-11-04）

`conventions.md` 說 61 條規則其實在回答四個問題；在這之前程式碼的切法還是「依格式」「依 section」——三個大檔（`CodeGraphMerger` 3,073 行、`ConfigExtractor` 2,210 行、`TreeSitterExtractor` 1,254 行）各自混著判斷與讀檔，`indexMeta`、`mergeOne` 各一個 500 行的方法，20 份字詞表散在六個類別。這次重整讓程式碼的結構跟文件一樣：**轉接器只讀格式，慣例負責判斷，字詞表集中一處**。所有改動不改任何一條邊：每一步都跑 `mining/regress.sh`（23 個系統＋AICS 逐邊比對）得 same 23 / different 0，全套測試 361 個只剩原本就壞的 MCP 一個。

## 目錄

```
Service/DependencyAnalysis/
  Conventions/                   四個慣例的判斷，與它們查的字詞表
    Vocabulary     29 份封閉清單，每份附原則、來源生態系、新增條件；vocabulary.md 由它產生
    NameTable   ①  名字的純函式：拼法對齊（hyphenated、版本字尾、-container 字尾）、叢集 DNS 字尾、
                   namespace label、外部網域、目錄→模組的路徑規則
    AddressShape ② （留在 CodeExtraction/）值的形狀：scheme→種類、鍵名最後一字、連線字串、
                   opaque URI、loopback、保留網域、scheme 鏈
    EnvScope    ③  一個環境變數在某個模組讀到的是哪個宣告值：自己的範圍先、全域要一致、歧義不猜
    KeyReads    ③  整包注入（envFrom ConfigMap、env_file）的鍵，誰讀了：鍵名出現在程式碼或自己的設定，
                   或框架文件化的綁定（Spring 寬鬆綁定、.NET 的 __）
    UseSite     ④  文法跑完之後「這是不是使用」：URL 字面值只在呼叫點、沒人引用的常數、XML namespace、
                   OpenAPI 中繼資料、Python 死碼、瀏覽器端腳本、Go 經參數到 dial 的註冊名、
                   Spring @ConfigurationProperties 的存取點
  CodeExtraction/                格式轉接器：把檔案讀成 ledger 列，不判斷相依
    TreeSitterExtractor          只剩文法驅動＋emit＋薄表面降級（496 行，原 1,254）
    ConfigExtractor              派工：哪種檔給哪個讀取器（210 行，原 2,210）
    ComposeReader · KubernetesReader · DockerReader · AppConfigReader
    DescriptorRows               讀取器共用的「寫一列」：env-address、workload-env、命令列位址，
                                 以及它們都要問的形狀判斷（委給 AddressShape）
    HelmChartRenderer · ServiceRootScanner · StackDetector · BindingResolver · EdgeLedger
  Graph/
    DeployedNames  ①  這個部署的名字表（狀態）：宣告了哪些名字、哪些拼法是同一個、namespace、
                      目錄→模組；回答「這個位址落在哪」（resolveTarget）與「這個檔是誰的」（resolveSource）
    Ownership      ③  誰的值（狀態）：env→host 表與範圍、workload 收到哪個 ConfigMap／env_file 又讀了哪些鍵、
                      模組用什麼建、誰匯入函式庫、FROM 鏈、config repository 的版面、佔位符表；
                      透過 DeployedNames 回答名字的問題，DeployedNames 不反過來依賴它
    CodeGraphMerger   只剩流程與畫邊（1,410 行，原 3,073）：索引順序、每列的派工、收尾
      indexMeta → 11 個有名字的索引步驟，各自寫進 names／owner
      mergeOne  → 派工 + 14 個處理器，依 ④→③→②→① 的順序問：
                   isNotAnEdge · mountedCandidateRejected · mergeComposeDependency · isFeignWiring ·
                   fanOutSharedConfig · isDeclaredNotUsed · fanOutBaseImage · fanOutLibrary ·
                   mergeNameRef · mergeIngressRoute · mergeRegistryRef · mergeEnvRead · mergeEnvCall ·
                   fillFormatHost · mergeProperty · deferAsync · mergeSync
    GraphNormalizer · DotEmitter · MermaidEmitter · …
  RuleTrace                      61 條規則的觸發記錄，報表改按慣例分組（conventionOf / rulesOf）
```

## 一列 ledger 怎麼變成一條邊

```
檔案 ──轉接器──▶ ledger 列 ──④ 是使用嗎──▶ ③ 誰的 ──▶ ② 值是什麼 ──▶ ① 落在哪個名字 ──▶ 邊
```

- **轉接器**（`CodeExtraction/`）：tree-sitter 查詢樣式（每語言一個 .scm）、五個描述檔讀取器。它們只知道自己的格式長什麼樣，產出的列長一樣（section + fields + file:line）。
- **④ 使用**（`UseSite`、`mergeOne` 前段的 gate）：宣告不等於使用。URL 字面值要在呼叫點那一行或前後兩行、或存在一個名字裡而那個名字出現在呼叫點；沒人引用的常數、死碼、瀏覽器端腳本、中繼資料都標成「不是邊」，列還在 ledger 裡，理由寫在欄位上。
- **③ 歸屬**（`EnvScope`、`KeyReads`、`mergeOne` 的來源判定、`mergeWorkloadWiring`）：值是誰選的。寫在 workload 自己 env 區塊的是它的；整包注入的要它讀了才算；一個模組被幾個 workload 跑就各算一份；基底映像的啟動腳本跑在每個 FROM 它的映像裡；函式庫的程式碼屬於匯入它的服務。
- **② 位址形狀**（`AddressShape`）：什麼值是位址、位址說目標是什麼種類。scheme、鍵名最後一字、連線字串、host:port；`http:${X}` 沒有主機；loopback 與保留網域不是任何人的。
- **① 名字表**（`NameTable` ＋ merger 的索引）：部署宣告了哪些名字、哪些拼法是同一個。保留網域 → 別名 → loopback（開發機上以 port 找回擁有者）→ 外部網域 → 已宣告的名字 → 才准新節點。

## 字詞表

`Vocabulary` 登記 29 份清單、306 個詞，每份寫「原則、來源生態系、新增條件」；`VocabularyInventoryTest` 保證 `vocabulary.md` 與程式碼一致。新增一個詞的條件一律是「某生態系的文件這樣拼」，不是「某個受測系統需要」。

清單一律保留宣告順序（`LinkedHashSet`）：`Set.of` 的迭代順序每次 JVM 啟動都不同，`CLUSTER_SUFFIXES` 一旦 `.cluster.local` 排到 `.svc.cluster.local` 前面，`web.ns.svc.cluster.local` 就剝成 `web.ns`——重整第二步曾因此埋下一個回歸單跑抓不到的 flaky，第三步才被抓到。

## 還沒做、刻意不做

- `DeployedNames`／`Ownership` 放在 `Graph/` 而不是 `Conventions/`：它們是 merger 的協作物件，成員是套件內可見，放同一個套件省掉一百多個 `public`。純函式的 `NameTable`、`EnvScope`、`KeyReads`、`UseSite` 在 `Conventions/`。
- `syncTarget`（② 在 config 列上的判斷）留在 merger 裡，名字已經說明它是哪個慣例。
- 第二層的幾條「靠資料決定的子句」（depends_on 的自家容器條款、links 的 v1 語意、第 41 條 main 例外、FROM 鏈只給有部署的子映像、server.port 先於 Compose 發佈的 port）沒有改，它們在 `conventions.md` 和各自的 javadoc 裡有理由；論文要列成表的是這幾條。
