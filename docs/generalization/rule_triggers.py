#!/usr/bin/env python3
"""Which of the extraction / merge rules (56 since round 4) fired on which of the 22 systems.

Reads the rule trace GreenfieldProbeTest writes beside each probe summary
(`<name>.trace.rules.json`, RuleTrace.toJson) for every system, the rule statements and
inducing systems from the appendix table of docs/papers/src/compsac.txt, and writes
docs/generalization/rule-triggers.md (the full table, the summary and the compact
appendix version). It also prints the appendix rows with the two new columns so they
can be pasted into compsac.txt.

    python3 -I docs/generalization/rule_triggers.py [--aics-dir ~/Documents/aics-eval/out/2026-10-27-r4]

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
    ("travels", "travels", "hold-out D", GEN / "holdout-d" / f"travels.{TAG}.rules.json"),
    ("skywalking-showcase", "skywalking-showcase", "hold-out D", GEN / "holdout-d" / f"skywalking-showcase.{TAG}.rules.json"),
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
    ("skywalking-showcase", {"skywalking-showcase"}),
    ("travels", {"travels"}),
]


READING = """## 怎麼讀（2026-10-27，第五輪後：56 條規則、22 個系統）

**慣例（23 條，≥5 個系統）**：1、2、3、4、5、6、7、8、9、10、11、12、14、15、20、35、36、41、44、47、51、52、56。第四輪的 20 條全部仍在 ≥5 個系統上觸發，
第五輪新增的規則有 4 條直接進來：第 47 條（呼叫點變數沿同檔綁定解析，取代第 22 條的 Python 專用解析器）在 8 個系統上改變結果，其中 5 個不是歸納它的；
第 51 條（HTML 載入的腳本是瀏覽器程式碼）在 AICS 歸納，piggymetrics、robot-shop、train-ticket、spring-petclinic、travels 的 static/ 裡都有同樣的頁面與腳本；
第 52 條（Helm 先渲染）在 7 個系統上渲染了 chart；第 56 條（入口路由）在 Bank of Anthos、Online Boutique、robot-shop、train-ticket 上都讀到綁 Gateway 的 VirtualService。
第 44 條（部署名對模組）加了 image 名與 package 名之後由 8 個系統擴大到 16 個。

**部分轉移（21 條，2–4 個系統）**：16、18、19、22、24、25、27、29、30、31、32、33、37、39、40、42、43、45、53、54、55。每一條都至少在一個**非**歸納系統上觸發；
第五輪的 53（命令列位址 → mall-swarm、yas）、54（namespace → skywalking-showcase 的 Helm namespace）、55（多位址值 → train-ticket、yas、skywalking-showcase）都是。
第 22 條在第五輪只剩死碼那一半，所以由 5 個系統降為 4 個。

**只在歸納它的系統上觸發（11 條）**：13、17、21、23、28、34、38、46、48、49、50。第四輪的 10 條中，第 25 條（Compose 別名）因為加上 network aliases 而在 skywalking-showcase 觸發、離開這一組；
第 28 條（Kotlin）改為有文法之後仍只有 opentelemetry-demo 有 .kt。新增的 3 條：48（環境變數讀取的預設值，robot-shop）、49（註冊中心名稱，hotelReservation）、50（模組 dotenv，yas）——
它們是某種寫法在語料裡只出現一次（Consul 名稱經參數傳到 dial、Next.js 的 .env.production、PHP 的 getenv ?: URL），要再有一個系統才知道是慣例還是特例。

**從未觸發（1 條）**：第 26 條（連線 scheme 決定節點種類），理由同前：語料裡 scheme 說的與主機名說的一致。

**計數位置的取捨**：同上一版，另外——第 28／40 條數的是以文法（或薄表面）讀的 .kt／.cs 檔；第 47 條數解開的呼叫點；第 52 條數渲染的 template 檔與「渲染的 chart 不是詞彙表時被擋下的主機」；
第 25 條的 network alias 一個別名算一次（不是每個讀者一次）；第 55 條在抽取時數被拆開的值，所以 train-ticket 的 fluentd ConfigMap 也算進去（它沒有改變任何邊）。
"""


def inducers(text):
    out = set()
    for name, systems in INDUCER_ALIASES:
        if name in text:
            out |= systems
    return out


def appendix_rows():
    """(number, statement, 觸發 text, 位置 text) for every rule, from compsac.txt's appendix table."""
    lines = COMPSAC.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if re.match(r"!table \d+ 條抽取與合併規則", l))
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
    assert len(rows) == int(re.match(r"!table (\d+)", lines[start]).group(1)), len(rows)
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
    ap.add_argument("--aics-dir", default=os.path.expanduser("~/Documents/aics-eval/out/2026-10-27-r4"))
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
    md.append(f"# {len(rows)} 條規則在 {len(SYSTEMS)} 個系統上各觸發了幾次（2026-10-27，第五輪後）\n")
    md.append("回答「這不就是把例子列舉一遍嗎？」：每條規則都是在 1–3 個系統上歸納出來的；"
              "若它在很多沒參與歸納的系統上也會改變結果，它抓到的是業界的慣例，不是那個系統的特例。"
              "表的每一格是 `RuleTrace` 在該系統的靜態層分析中記到的**不同事物數**（邊、節點、名稱、檔案；同一個東西重複碰到算一次），"
              "由 `GreenfieldProbeTest` 以 tag `trace` 跑在與 r3／現行輸出同一份 checkout 上得到（邊逐條相同，見下方），"
              "原始檔是各目錄下的 `<name>.trace.rules.json`，表由 `docs/generalization/rule_triggers.py` 產生。\n")
    md.append("計數的位置是規則**改變結果**的地方：畫出／不畫／改等級一條邊、建立／合併／壓掉一個節點、解開一個名稱；"
              "純解析的規則（第 12 條 Compose v1、第 28／40 條 Kotlin／C# 檔案、第 52 條渲染的 template）數它解析的檔案或服務。"
              "每條規則對應的程式位置與 token 形狀列在文末。\n")
    md.append(f"robot-shop 有兩個 checkout（歸納用的 2024 版與 MicroDepGraph 對齊的 2019 版），兩欄分開列、「觸發系統數」只算一次，所以欄數是 {len(runs)}、系統數是 {len(SYSTEMS)}。\n")

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
                              for g in ["歸納", "回歸", "MicroDepGraph", "hold-out B", "hold-out C", "hold-out D", "私有"]) + "。\n")

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
        start = next(i for i, l in enumerate(lines) if re.match(r"!table \d+ 條抽取與合併規則", l))
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
