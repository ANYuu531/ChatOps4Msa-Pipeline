# Hold-out set E：靜態 greenfield 圖 vs 參考邊集（程式計分，r7）

由 `docs/generalization/holdout-e/score.py` 產生，規則與 `GeneralizationScoreTest` 相同：讀 `truth/<name>.tsv` 與 `<name>.r7.summary.md`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R | external P/R | 另外畫到的 variant 邊 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| go-food-delivery | 8 | 8 | 0 | 0 | 1.00 | 1.00 | — | 1.00 / 1.00 | — | — | 18 |
| **合計** | 8 | 8 | 0 | 0 | 1.00 | 1.00 | | | | | |

`external` 欄只報 R（錯的邊沒有 class，無法分辨是不是外部主機）。


## go-food-delivery

參考邊集：data 8（可計分 8）＋ variant 29。


對的裡面有 7 條標 inferred（名字推的，不是表查到的）。


工具另外畫到的 variant 邊（不計分，18 條）：

- catalogreadservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:66；遙測
- catalogreadservice -> otelcol  (sync-http, inferred)  ·  config/config.development.json:88 otlpEndpoint host.docker.internal:4317；遙測
- catalogreadservice -> tempo  (sync-http, inferred)  ·  config/config.development.json:70；遙測
- catalogreadservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:74；遙測
- catalogwriteservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:59 jaegerExporterOptions localhost:4320（compose jaeger 4320:4317）；遙測
- catalogwriteservice -> otelcol  (sync-http, inferred)  ·  config/config.development.json:81 otlpEndpoint host.docker.internal:4317（:89 localhost:4317；compose otelcol 發佈 4317）；遙測
- catalogwriteservice -> tempo  (sync-http, inferred)  ·  config/config.development.json:63 tempoExporterOptions localhost:4322（compose tempo 4322:4317）；遙測
- catalogwriteservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:67 localhost:9411（只有 infrastructure-collector 變體部署 zipkin）；遙測
- grafana -> jaeger  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:18；遙測
- grafana -> prometheus  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:8 datasource url http://prometheus:9090；遙測
- grafana -> tempo  (sync-http, documented)  ·  deployments/docker-compose/monitoring/grafana.yaml:28；遙測
- orderservice -> jaeger  (sync-http, inferred)  ·  config/config.development.json:59；遙測
- orderservice -> otelcol  (sync-http, inferred)  ·  config/config.development.json:77 otlpEndpoint host.docker.internal:4317；遙測
- orderservice -> zipkin  (sync-http, inferred)  ·  config/config.development.json:63；遙測
- prometheus -> cadvisor  (sync-http, documented)  ·  抓取；遙測
- prometheus -> grafana  (sync-http, documented)  ·  deployments/docker-compose/monitoring/prometheus.yml:20；遙測
- prometheus -> tempo  (sync-http, documented)  ·  deployments/docker-compose/monitoring/prometheus.yml:72 targets tempo:3200；遙測
- tempo -> prometheus  (sync-http, documented)  ·  deployments/docker-compose/otelcollector/tempo.yaml:43 remote_write http://prometheus:9090；遙測

全對。

