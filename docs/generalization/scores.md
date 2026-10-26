# 靜態 greenfield 圖 vs ground truth（程式計分）

由 `GeneralizationScoreTest` 產生：讀 `truth/<name>.tsv` 與 `<name>.mmd`，`variant` 類不計。P = 對的 / 工具畫的，R = 對的 / 真的（非 variant）。

| 專案 | 畫了 | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R |
|---|---|---|---|---|---|---|---|---|---|
| bank-of-anthos | 12 | 12 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| ecommerce | 37 | 37 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | — | — / 1.00 |
| ewolff-k8s | 5 | 5 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | — | — / 1.00 |
| online-boutique | 17 | 17 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| piggymetrics | 33 | 33 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |
| robot-shop | 19 | 19 | 0 | 2 | 1.00 | 0.90 | 1.00 / 0.60 | 1.00 / 1.00 | — / 1.00 |
| teastore | 13 | 13 | 0 | 0 | 1.00 | 1.00 | 1.00 / 1.00 | 1.00 / 1.00 | — / 1.00 |

## bank-of-anthos

對的裡面有 1 條標 inferred（名字推的，不是表查到的）。

全對。

## ecommerce

對的裡面有 17 條標 inferred（名字推的，不是表查到的）。

全對。

## ewolff-k8s

全對。

## online-boutique

對的裡面有 15 條標 inferred（名字推的，不是表查到的）。

全對。

## piggymetrics

全對。

## robot-shop

對的裡面有 7 條標 inferred（名字推的，不是表查到的）。

漏（truth 有、工具沒畫）：
- cart -> catalogue  ·  cart/server.js:362 request('http://' + catalogueHost + ':8080/product/')
- ratings -> catalogue  ·  ratings/html/src/Service/CatalogueService.php:27 sprintf('%s/product/%s', $this->catalogueUrl)（host 由 ratings/html/src/Kernel.php:76 的 getenv('CATALOGUE_URL') ?: 'http://catalogue:8080' 注入；repo 沒有 config/services.yaml）

## teastore

對的裡面有 5 條標 inferred（名字推的，不是表查到的）。

全對。
