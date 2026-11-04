# Hold-out set E：靜態 greenfield 圖 vs 參考邊集（程式計分，r6）

由 `docs/generalization/holdout-e/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r6.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| go-food-delivery | 11 | 8 | 3 | 0 | 0.73 | 1.00 | — | 1.00 / 1.00 | — | — | 18 |
| **合計** | 11 | 8 | 3 | 0 | 0.73 | 1.00 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## go-food-delivery

參考邊集：data 8（可計分 8）＋ variant 26。


錯（工具畫了、truth 沒有，3 條）：

- catalogreadservice -> otelcol  (sync-http, inferred)  code: internal/services/catalogreadservice/config/config.development.json
- catalogwriteservice -> otelcol  (sync-http, inferred)  code: internal/services/catalogwriteservice/config/config.development.json
- orderservice -> otelcol  (sync-http, inferred)  code: internal/services/orderservice/config/config.development.json

對的裡面有 7 條標 inferred（名字推的，不是表查到的）。


工具另外畫到的 variant 邊（不計分，18 條）：

- catalogreadservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:66；遙測
- catalogreadservice -> tempo  (sync-http, inferred)  ·  config/config.development.json:70；遙測
- catalogreadservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:74；遙測
- catalogwriteservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:59 jaegerExporterOptions localhost:4320（compose jaeger 4320:4317）；遙測
- catalogwriteservice -> tempo  (sync-http, inferred)  ·  config/config.development.json:63 tempoExporterOptions localhost:4322（compose tempo 4322:4317）；遙測
- catalogwriteservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:67 localhost:9411（只有 infrastructure-collector 變體部署 zipkin）；遙測
- grafana -> jaeger  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:18；遙測
- grafana -> prometheus  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:8 datasource url http://prometheus:9090；遙測
- grafana -> tempo  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:28；遙測
- orderservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:59；遙測
- orderservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:63；遙測
- otelcol -> jaeger  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
- otelcol -> prometheus  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
- otelcol -> tempo  (sync-http, inferred)  ·  infrastructure-collector 變體；遙測
- prometheus -> cadvisor  (sync-http, documented)  ·  抓取；遙測
- prometheus -> grafana  (sync-http, documented)  ·  deployments/docker-compose/monitoring/prometheus.yml:20；遙測
- prometheus -> tempo  (sync-http, documented)  ·  deployments/docker-compose/monitoring/prometheus.yml:72 targets tempo:3200；遙測
- tempo -> prometheus  (sync-http, documented)  ·  deployments/docker-compose/otelcollector/tempo.yaml:43 remote_write http://prometheus:9090；遙測
