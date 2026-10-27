#!/usr/bin/env python3
"""Which of the 46 extraction / merge rules fired on which of the 20 systems.

Reads the rule trace GreenfieldProbeTest writes beside each probe summary
(`<name>.trace.rules.json`, RuleTrace.toJson) for every system, the rule statements and
inducing systems from the appendix table of docs/papers/src/compsac.txt, and writes
docs/generalization/rule-triggers.md (the full table, the summary and the compact
appendix version). It also prints the appendix rows with the two new columns so they
can be pasted into compsac.txt.

    python3 -I docs/generalization/rule_triggers.py [--aics-dir ~/Documents/aics-eval/out/2026-10-27-trace]

A count is RuleTrace's distinct-token count: how many different things (edges, nodes,
names, files) the rule changed on that system; the same thing reached twice is one.
"""
import argparse
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GEN = ROOT / "docs" / "generalization"
COMPSAC = ROOT / "docs" / "papers" / "src" / "compsac.txt"
TAG = "trace"

# (column key, display name, group, path of the rules.json)
RUNS = [
    ("bank-of-anthos", "Bank of Anthos", "歸納", GEN / f"bank-of-anthos.{TAG}.rules.json"),
    ("ewolff-k8s", "ewolff k8s", "歸納", GEN / f"ewolff-k8s.{TAG}.rules.json"),
    ("ecommerce", "ecommerce", "歸納", GEN / f"ecommerce.{TAG}.rules.json"),
    ("piggymetrics", "piggymetrics", "歸納", GEN / f"piggymetrics.{TAG}.rules.json"),
    ("teastore", "TeaStore", "歸納", GEN / f"teastore.{TAG}.rules.json"),
    ("online-boutique", "Online Boutique", "歸納", GEN / f"online-boutique.{TAG}.rules.json"),
    ("robot-shop", "robot-shop (2024)", "歸納", GEN / f"robot-shop.{TAG}.rules.json"),
    ("train-ticket", "train-ticket", "回歸", GEN / f"train-ticket.{TAG}.rules.json"),
    ("spring-petclinic", "spring-petclinic", "MicroDepGraph", GEN / "external" / f"spring-petclinic.{TAG}.rules.json"),
    ("microservices-book", "microservices-book", "MicroDepGraph", GEN / "external" / f"microservices-book.{TAG}.rules.json"),
    ("tap-and-eat", "Tap-And-Eat", "MicroDepGraph", GEN / "external" / f"tap-and-eat.{TAG}.rules.json"),
    ("spring-cloud-netflix", "spring-cloud-netflix", "MicroDepGraph", GEN / "external" / f"spring-cloud-netflix.{TAG}.rules.json"),
    ("spring-cloud-microservice", "spring-cloud-microservice", "MicroDepGraph", GEN / "external" / f"spring-cloud-microservice.{TAG}.rules.json"),
    ("lakeside-mutual", "LakesideMutual", "MicroDepGraph", GEN / "external" / f"lakeside-mutual.{TAG}.rules.json"),
    ("robot-shop@2019", "robot-shop (2019)", "MicroDepGraph", GEN / "external" / f"robot-shop.{TAG}.rules.json"),
    ("opentelemetry-demo", "opentelemetry-demo", "hold-out B", GEN / "holdout" / f"opentelemetry-demo.{TAG}.rules.json"),
    ("mall-swarm", "mall-swarm", "hold-out B", GEN / "holdout" / f"mall-swarm.{TAG}.rules.json"),
    ("hotel-reservation", "hotelReservation", "hold-out B", GEN / "holdout" / f"hotel-reservation.{TAG}.rules.json"),
    ("yas", "yas", "hold-out C", GEN / "holdout-c" / f"yas.{TAG}.rules.json"),
    ("run-aspnetcore-microservices", "run-aspnetcore-microservices", "hold-out C", GEN / "holdout-c" / f"run-aspnetcore-microservices.{TAG}.rules.json"),
    ("aics", "AICS (private)", "私有", None),  # path from --aics-dir
]

# The two robot-shop checkouts are one system: "systems fired" counts it once.
SYSTEM_OF = {key: ("robot-shop" if key.startswith("robot-shop") else key) for key, *_ in RUNS}
SYSTEMS = sorted(set(SYSTEM_OF.values()), key=lambda s: [k for k, *_ in RUNS].index(s if s != "robot-shop" else "robot-shop"))

# How the appendix's 觸發 column names the systems.
INDUCER_ALIASES = [
    ("ewolff/microservice", {"microservices-book"}),
    ("microservices-book", {"microservices-book"}),
    ("ewolff", {"ewolff-k8s"}),
    ("TeaStore", {"teastore"}),
    ("Online Boutique", {"online-boutique"}),
    ("piggymetrics", {"piggymetrics"}),
    ("robot-shop", {"robot-shop"}),
    ("ecommerce", {"ecommerce"}),
    ("train-ticket", {"train-ticket"}),
    ("Bank of Anthos", {"bank-of-anthos"}),
    ("spring-petclinic", {"spring-petclinic"}),
    ("Tap-And-Eat", {"tap-and-eat"}),
    ("spring-cloud-microservice", {"spring-cloud-microservice"}),
    ("LakesideMutual", {"lakeside-mutual"}),
    ("AICS", {"aics"}),
    ("opentelemetry-demo", {"opentelemetry-demo"}),
    ("mall-swarm", {"mall-swarm"}),
    ("hotelReservation", {"hotel-reservation"}),
    ("MicroDepGraph", {"spring-petclinic", "microservices-book", "tap-and-eat", "spring-cloud-netflix",
                       "spring-cloud-microservice", "lakeside-mutual", "robot-shop"}),
    ("run-aspnetcore-microservices", {"run-aspnetcore-microservices"}),
    ("yas", {"yas"}),
]


READING = """## 怎麼讀（2026-10-27 這一跑）

**慣例（20 條，≥5 個系統）**：1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、22、35、36、41、44。這些都離開了歸納它的系統：
manifest 與 Compose 的名稱清單（1、11）、模組目錄正規化（2）、佔位符解析（4）、裸主機名（5）、反向代理設定（6）、Compose 佈線（7）、
「不是目標的不畫」（8）、「宣告≠使用」（9）、只認部署了自己模組的 manifest 目錄（10）、Compose v1 與 links（12）、
workload 自己 env 裡的服務位址（14）、depends_on 分級（35）各在 9–14 個系統上改變結果。值得點名的是在一個系統上歸納、卻轉移得很遠的幾條：
第 20 條（環境變數在讀取者自己的範圍解析）在私有系統 AICS 上歸納，Bank of Anthos、Online Boutique、robot-shop、opentelemetry-demo、run-aspnetcore-microservices 都用到；
第 22 條（Python 的呼叫點才算使用、死碼不畫）也來自 AICS，在 Bank of Anthos、robot-shop、train-ticket、opentelemetry-demo 上觸發；
第 15 條（loopback 以 port 讀回擁有者）在 ecommerce 歸納，Tap-And-Eat、spring-cloud-microservice、LakesideMutual、yas 都有同樣的寫法；
第 41 條（JVM 函式庫模組不是服務）在 yas 歸納，TeaStore、train-ticket、spring-cloud-microservice、mall-swarm 各有 1–3 個這樣的模組。

**部分轉移（15 條，2–4 個系統）**：16、18、19、24、27、29、30、31、32、33、37、40、42、43、45。每一條都至少在一個**非**歸納系統上觸發
（16 隱含 Eureka → spring-petclinic、spring-cloud-netflix；18 String.format＋Dockerfile ENV → TeaStore；19 版本字尾 → train-ticket；
24 Compose 鍵名轉發 → yas；27 位址清單 → train-ticket、yas；29 OpenAPI 中繼資料 → spring-cloud-netflix；30 訊息邊 → yas；31 測試 fixture → Bank of Anthos；
32 連線字串 → run-aspnetcore-microservices；33 保留 TLD → yas；37 Compose hostname → hotelReservation；40 C# 薄表面 → Online Boutique、opentelemetry-demo；
42 測試樹 → opentelemetry-demo；43 OpenAPI UI 設定鍵 → train-ticket、mall-swarm；45 位址鍵下的裸單字 → opentelemetry-demo）。這些是某種技術或寫法的慣例，語料裡用到那種技術的系統本來就只有兩三個。

**只在歸納它的系統上觸發（10 條）**：13、17、21、23、25、28、34、38、39、46。其中 7 條綁在語料裡只出現一次的技術——Kotlin（28）、Envoy（34）、YARP 與 appsettings（38）、
.NET 連線字串的形狀（39）、nginx 的 set $var（46）、SOAP／JAXB 的 XML namespace（13）、沒有 Dockerfile 的 Python 套件庫（21）；這份記錄分不出「技術只出現一次」與「專案特例」，
要再有一個用同樣技術的系統才知道。另外 3 條是真正的單一系統寫法：TeaStore 以 enum 常數經自家 registry 派送（17）、piggymetrics 的共用設定檔要靠建置檔判斷誰綁得到（23）、
mall-swarm 的 `links: mysql:db` 別名（25；Tap-And-Eat 等系統的 links 沒有別名）。它們沒有錯，但確實是為一個系統寫的。

**從未觸發（1 條）**：第 26 條（連線 scheme 決定節點種類）。在全部 20 個系統上，jdbc:／mongodb://／amqp:// 指到的主機名稱本身就帶著資料庫或 broker 的判定
（mysql、*-mongodb、*-db、rabbitmq…），scheme 說的與名稱說的一致，所以沒有一次改變結果；在歸納它的 mall-swarm 上，`jdbc:mysql://db` 的 `db` 先被第 25 條解成 `mysql`，
第 26 條就用不到了。它只在「主機名沒有任何提示、又沒有別名」時才有作用（單元測試的 fixture 就是這種），在真實語料裡是一條從未用到的保險。

**計數位置的取捨**（規則編號有歧義處，依附錄的措辭對應）：第 9 條把 JPA 標記列與 JAX-RS `@Path` 列也算進去（附錄把這兩句寫在同一條），所以它的命中數偏大；
第 1 與第 11 條以名稱清單的來源分（manifest → 1，Compose → 11）；第 5、27、45 條以設定鍵的字尾分；第 26 與第 39 條以「scheme 寫在目標裡」與「值的形狀（kindHint、ConnectionStrings、事後改型）」分；
第 21 與第 41 條以 `library_kind` 分；第 2、31、42 條的測試排除以各自新增的名單分；第 14 條只數服務類的 inferred 邊與共用 ConfigMap 的略過，
資料庫／broker 的 workload 佈線邊（2026-09-21 Bank of Anthos，早於編號）沒有規則號、不計；第 44 條只數改了名字的對應與被拒的 Compose 名；第 33 條在 ExternalHostDetector 的那一處 probe 不會經過。
"""


def inducers(text):
    out = set()
    for name, systems in INDUCER_ALIASES:
        if name in text:
            out |= systems
    return out


def appendix_rows():
    """(number, statement, 觸發 text, 位置 text) for the 46 rules, from compsac.txt's appendix table."""
    lines = COMPSAC.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("!table 46"))
    rows = []
    for l in lines[start + 1:]:
        if l.startswith("!end"):
            break
        if not l.startswith("|"):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if not cells[0].isdigit():
            continue
        rows.append((int(cells[0]), cells[1], cells[2], cells[3]))
    assert len(rows) == 46, len(rows)
    return rows


def load(path):
    if path is None or not Path(path).is_file():
        return None
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return {int(k): v for k, v in data["rules"].items()}


def short(statement, n=38):
    s = statement.split("：")[0] if "：" in statement else statement
    return s if len(s) <= n else s[: n - 1] + "…"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--aics-dir", default=os.path.expanduser("~/Documents/aics-eval/out/2026-10-27-trace"))
    ap.add_argument("--out", default=str(GEN / "rule-triggers.md"))
    ap.add_argument("--write-compsac", action="store_true",
                    help="also rewrite the appendix table rows of compsac.txt with the two new columns (系統數, 命中)")
    args = ap.parse_args()

    runs = []
    for key, name, group, path in RUNS:
        if key == "aics":
            path = Path(args.aics_dir) / f"aics.{TAG}.rules.json"
        trace = load(path)
        runs.append((key, name, group, path, trace))
    missing = [name for _, name, _, _, t in runs if t is None]
    if missing:
        print("missing traces (left blank):", ", ".join(missing), file=sys.stderr)

    rows = appendix_rows()
    table = {}  # rule -> {key: count}
    examples = {}  # rule -> [example tokens]
    for num, *_ in rows:
        table[num] = {}
        examples[num] = []
        for key, _, _, _, trace in runs:
            if trace is None:
                continue
            entry = trace.get(num, {"count": 0, "examples": []})
            table[num][key] = entry["count"]
            for ex in entry.get("examples", []):
                if len(examples[num]) < 3 and ex not in examples[num]:
                    examples[num].append(f"{key}: {ex}")

    def systems_fired(num):
        return {SYSTEM_OF[k] for k, c in table[num].items() if c > 0}

    md = []
    md.append("# 46 條規則在 20 個系統上各觸發了幾次（2026-10-27）\n")
    md.append("回答「這不就是把例子列舉一遍嗎？」：每條規則都是在 1–3 個系統上歸納出來的；"
              "若它在很多沒參與歸納的系統上也會改變結果，它抓到的是業界的慣例，不是那個系統的特例。"
              "表的每一格是 `RuleTrace` 在該系統的靜態層分析中記到的**不同事物數**（邊、節點、名稱、檔案；同一個東西重複碰到算一次），"
              "由 `GreenfieldProbeTest` 以 tag `trace` 跑在與 r3／現行輸出同一份 checkout 上得到（邊逐條相同，見下方），"
              "原始檔是各目錄下的 `<name>.trace.rules.json`，表由 `docs/generalization/rule_triggers.py` 產生。\n")
    md.append("計數的位置是規則**改變結果**的地方：畫出／不畫／改等級一條邊、建立／合併／壓掉一個節點、解開一個名稱；"
              "純解析的規則（第 12 條 Compose v1、第 28／40 條薄表面）數它解析的檔案或服務。"
              "每條規則對應的程式位置與 token 形狀列在文末。\n")
    md.append("robot-shop 有兩個 checkout（歸納用的 2024 版與 MicroDepGraph 對齊的 2019 版），兩欄分開列、「觸發系統數」只算一次，所以欄數是 21、系統數是 20。\n")

    # Full table.
    md.append("## 全表\n")
    head = ["#", "規則（首句）", "歸納自"] + [name for _, name, _, _, _ in runs] + ["觸發系統數", "合計"]
    md.append("| " + " | ".join(head) + " |")
    md.append("|" + "---|" * len(head))
    for num, statement, trigger, _ in rows:
        cells = [str(num), short(statement), trigger]
        for key, _, _, _, trace in runs:
            cells.append("" if trace is None else str(table[num].get(key, 0)))
        total = sum(table[num].values())
        cells.append(f"{len(systems_fired(num))}/{len(SYSTEMS)}")
        cells.append(str(total))
        md.append("| " + " | ".join(cells) + " |")
    md.append("")
    md.append("欄的分組：" + "；".join(f"{g}＝" + "、".join(n for _, n, gg, _, _ in runs if gg == g)
                              for g in ["歸納", "回歸", "MicroDepGraph", "hold-out B", "hold-out C", "私有"]) + "。\n")

    # Summary buckets.
    ge5, two4, one, only_ind, never = [], [], [], [], []
    for num, statement, trigger, _ in rows:
        fired = systems_fired(num)
        ind = inducers(trigger)
        n = len(fired)
        if n == 0:
            never.append(num)
        elif n >= 5:
            ge5.append(num)
        elif n >= 2:
            two4.append(num)
        else:
            one.append(num)
        if fired and fired <= ind:
            only_ind.append(num)

    def lst(nums):
        return "、".join(str(n) for n in nums) if nums else "—"

    md.append("## 摘要\n")
    md.append(f"- 在 **≥5 個系統**上觸發：{len(ge5)} 條（{lst(ge5)}）")
    md.append(f"- 在 **2–4 個系統**上觸發：{len(two4)} 條（{lst(two4)}）")
    md.append(f"- 只在 **1 個系統**上觸發：{len(one)} 條（{lst(one)}）")
    md.append(f"- 觸發的系統**全部是歸納它的系統**（沒有轉移到別處）：{len(only_ind)} 條（{lst(only_ind)}）")
    md.append(f"- **從未觸發**：{len(never)} 條（{lst(never)}）\n")

    # Per-rule detail: fired on which systems, how many of them were not inducers.
    md.append("## 每條規則：觸發的系統與其中非歸納系統\n")
    md.append("| # | 歸納自 | 觸發系統 | 其中非歸納系統 | 例子（系統: token） |")
    md.append("|---|---|---|---|---|")
    for num, statement, trigger, _ in rows:
        fired = systems_fired(num)
        ind = inducers(trigger)
        fired_list = [s for s in SYSTEMS if s in fired]
        outside = [s for s in fired_list if s not in ind]
        md.append(f"| {num} | {trigger} | {len(fired_list)}：{'、'.join(fired_list) if fired_list else '—'} | "
                  f"{len(outside)}：{'、'.join(outside) if outside else '—'} | {'；'.join(examples[num]).replace('|', '\\|')} |")
    md.append("")

    # The reading. Hand-written against the 2026-10-27 run; re-check it if the numbers above move.
    md.append(READING)

    # Compact appendix version.
    md.append("## 附錄用精簡版（規則、歸納自、觸發系統數、合計命中）\n")
    md.append("| # | 規則（首句） | 歸納自 | 觸發系統數 | 合計 |")
    md.append("|---|---|---|---|---|")
    for num, statement, trigger, _ in rows:
        md.append(f"| {num} | {short(statement)} | {trigger} | {len(systems_fired(num))}/{len(SYSTEMS)} | {sum(table[num].values())} |")
    md.append("")

    Path(args.out).write_text("\n".join(md) + "\n", encoding="utf-8")
    print("wrote", args.out)

    # The appendix rows with the two new columns, for compsac.txt.
    new_rows = {num: f"| {num} | {statement} | {trigger} | {where} | {len(systems_fired(num))} | {sum(table[num].values())} |"
                for num, statement, trigger, where in rows}
    if args.write_compsac:
        lines = COMPSAC.read_text(encoding="utf-8").splitlines()
        start = next(i for i, l in enumerate(lines) if l.startswith("!table 46"))
        for i in range(start + 1, len(lines)):
            l = lines[i]
            if l.startswith("!end"):
                break
            if l.startswith("!widths"):
                lines[i] = "!widths 300,2300,1000,640,400,400"
            elif l.startswith("| # |"):
                lines[i] = "| # | 規則 | 觸發 | 位置 | 系統數 | 命中 |"
            elif l.startswith("|"):
                cells = [c.strip() for c in l.strip().strip("|").split("|")]
                if cells[0].isdigit():
                    lines[i] = new_rows[int(cells[0])]
        COMPSAC.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("rewrote the appendix table rows in", COMPSAC)
    else:
        print("\n--- compsac appendix rows ---")
        for num in sorted(new_rows):
            print(new_rows[num])
    print("\nsummary: >=5:", len(ge5), " 2-4:", len(two4), " 1:", len(one), " only-inducers:", len(only_ind), " never:", len(never))
    print("never:", never, " only-inducers:", only_ind)


if __name__ == "__main__":
    main()
