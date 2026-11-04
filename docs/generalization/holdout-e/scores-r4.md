# Hold-out set E：靜態 greenfield 圖 vs 參考邊集（程式計分，r4）

由 `docs/generalization/holdout-e/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r4.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| go-food-delivery | 0 | 0 | 0 | 8 | — | 0.00 | — | — / 0.00 | — | — | 5 |
| **合計** | 0 | 0 | 0 | 8 | — | 0.00 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## go-food-delivery

參考邊集：data 8（可計分 8）＋ variant 26。


漏（truth 有、工具沒畫，8 條）：

- catalogwriteservice -> postgres  ·  internal/services/catalogwriteservice/internal/shared/configurations/catalogs/infrastructure/infrastructure_fx.go:29 postgresgorm.Module（config/config.development.json:33 gormOptions host localhost、:34 port 5432 → docker-compose.infrastructure.yaml postgres 發佈 5432）
- catalogwriteservice -> rabbitmq  ·  internal/services/catalogwriteservice/internal/products/configurations/rabbitmq/rabbitmq_configurations.go:12 AddProducer（config/config.development.json:46 hostName localhost、port 5672 → compose rabbitmq）
- catalogreadservice -> rabbitmq  ·  internal/services/catalogreadservice/internal/products/configurations/rabbitmq/rabbitmq_configuration.go:26 AddConsumer（config/config.development.json:38）
- catalogreadservice -> mongo  ·  internal/services/catalogreadservice/internal/shared/configurations/catalogs/infrastructure/infrastructure_fx.go:28 mongodb.Module（config/config.development.json:51 host localhost、port 27017 → compose mongo）
- catalogreadservice -> redis  ·  internal/services/catalogreadservice/internal/shared/configurations/catalogs/infrastructure/infrastructure_fx.go:29 redis.Module（config/config.development.json:44 host localhost、port 6379 → compose redis）
- orderservice -> rabbitmq  ·  internal/services/orderservice/internal/orders/configurations/rabbitmq/rabbitmq_configurations.go:13 AddProducer（config/config.development.json:46）
- orderservice -> mongo  ·  internal/services/orderservice/internal/shared/configurations/orders/infrastructure/infrastructure_fx.go:30 mongodb.Module（config/config.development.json:33 host localhost、port 27017）
- orderservice -> eventstore  ·  internal/services/orderservice/internal/shared/configurations/orders/infrastructure/infrastructure_fx.go:32 eventstroredb.ModuleFunc（config/config.development.json:90 host localhost、httpPort 2113、tcpPort 1113 → compose eventstore）

工具另外畫到的 variant 邊（不計分，5 條）：

- grafana -> prometheus  (sync-http, inferred)  ·  deployments/docker-compose/monitoring/grafana.yaml:8 datasource url http://prometheus:9090；遙測
- grafana -> tempo  (sync-http, inferred)  ·  deployments/docker-compose/monitoring/grafana.yaml:28；遙測
- otelcol -> jaeger  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
- otelcol -> prometheus  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
- otelcol -> tempo  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
