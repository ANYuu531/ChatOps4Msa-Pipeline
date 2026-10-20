#!/usr/bin/env python3
"""純 LLM 抽相依圖 vs DepWeaver 靜態層（COMPSAC 的假想敵實驗，threshold-design §9.5）。

同一批專案、同一份參考邊集（docs/generalization/truth/<name>.tsv），比三組：

  knowledge      只給 repo 網址＋元件清單，不給任何檔案 —— 「不讓 AI 基於事實」
  files          給元件清單＋預算內的部署描述檔／設定檔／原始碼 —— 「AI 看得到事實，但沒有工具」
  files-nonames  同上，但連元件清單都不給，名字它自己取 —— 最接近「把 repo 丟給 AI 畫圖」

DepWeaver 靜態層的分數不在這裡算：它由 GeneralizationScoreTest 產生在 docs/generalization/scores.md，
本腳本把它讀進來並排在同一張表（--selfcheck 會用同一套計分重算一次，兩邊要相等）。

計分和 GeneralizationScoreTest 完全一樣：邊 =（source, target）；truth 裡 class=variant 的邊
不算對也不算錯；LLM 畫了而 truth 沒有的是錯，truth 有（非 variant）而 LLM 沒畫的是漏。
P = 對／畫，R = 對／truth 非 variant。另外依 truth 的 class 分 business／data／control。

用法（在 repo 根目錄）：
  python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos <七個 checkout 的目錄> --dry-run
  python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --repos <...> --runs 3
  python3 -I docs/experiments/2026-10-20/pure_llm_graph.py --selfcheck

只用標準函式庫。API 金鑰讀 src/main/resources/application.properties 的 openai.api.key／model，
或環境變數 OPENAI_API_KEY／OPENAI_MODEL。
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
TRUTH_DIR = ROOT / "docs/generalization/truth"
GEN_DIR = ROOT / "docs/generalization"
OUT_DIR = Path(__file__).resolve().parent

# truth 檔名 → (checkout 目錄名, GitHub repo)
PROJECTS = {
    "bank-of-anthos": ("bank-of-anthos", "GoogleCloudPlatform/bank-of-anthos"),
    "ewolff-k8s": ("microservice-kubernetes", "ewolff/microservice-kubernetes"),
    "ecommerce": ("ecommerce-microservice-backend-app", "SelimHorri/ecommerce-microservice-backend-app"),
    "piggymetrics": ("piggymetrics", "sqshq/piggymetrics"),
    "teastore": ("TeaStore", "DescartesResearch/TeaStore"),
    "online-boutique": ("microservices-demo", "GoogleCloudPlatform/microservices-demo"),
    "robot-shop": ("robot-shop", "instana/robot-shop"),
}

# ---- 預算（寫死在程式裡，結果檔會原樣印出，審稿人看得到） ----
BUDGET = {
    "descriptor_bytes": 300_000,  # 部署描述檔＋設定檔，整檔給（超過就截斷）
    "source_bytes": 500_000,      # 原始碼總量
    "per_file_bytes": 20_000,     # 單檔上限，超過截斷並標記
    "max_files": 400,
}
DESCRIPTOR_NAME = re.compile(
    r"(^|/)(docker-)?compose[^/]*\.ya?ml$|(^|/)(k8s|kubernetes|kubernetes-manifests|manifests|helm|charts|"
    r"kustomize|release|deploy|examples)/.*\.ya?ml$|(^|/)values\.ya?ml$|(^|/)Chart\.ya?ml$|"
    r"(^|/)(application|bootstrap)[^/]*\.(ya?ml|properties)$|(^|/)\.env$|(^|/)nginx[^/]*\.conf$|"
    r"(^|/)[^/]*\.conf$|(^|/)Dockerfile[^/]*$|(^|/)docker-compose[^/]*$|(^|/)skaffold\.ya?ml$|"
    r"(^|/)Makefile$|(^|/)README\.md$", re.I)
SOURCE_EXT = {".java", ".py", ".go", ".js", ".ts", ".cs", ".php", ".kt", ".rb", ".proto"}
SKIP_DIR = {".git", "node_modules", "vendor", "target", "build", "dist", ".idea", "__pycache__",
            "test", "tests", "testing", "docs", "doc", "img", "images", "static", "assets",
            "genproto", "proto-gen", "third_party", "bin", "obj", "wwwroot", "loadgenerator-images"}
SOURCE_HINT = re.compile(r"client|service|controller|config|main|app|server|api|handler|route|repository|"
                         r"gateway|proxy|registry|resource|dao|consumer|producer|listener", re.I)

CLASS_TEXT = (
    "Edge classes: business = a service calls another service (HTTP/gRPC/RPC); "
    "data = a service uses a database, cache or message broker (write the consumer of a queue as "
    "consumer -> broker too); external = a service calls a host outside the cluster (e.g. an API on the "
    "internet, an SMTP server); control = a service talks to control-plane components such as a config "
    "server, a service registry/discovery server, a reverse proxy or API gateway in front of it, an "
    "admin/monitoring dashboard. Direction is caller -> callee."
)


# ---------------------------------------------------------------- truth
def read_truth(name: str):
    rows = []
    with open(TRUTH_DIR / f"{name}.tsv", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            rows.append((parts[0].strip(), parts[1].strip(), parts[2].strip(), parts[3].strip() if len(parts) > 3 else ""))
    return rows


def vocabulary(truth):
    """truth 裡出現的所有節點，附上從 class 推出來的 kind（給 LLM 的元件清單）。"""
    kind = {}
    for s, t, c, _ in truth:
        kind.setdefault(s, "service")
        if c == "data":
            kind[t] = "database/cache/broker"
        elif c == "external":
            kind[t] = "external host"
        elif c == "control":
            kind.setdefault(t, "control-plane component")
        else:
            kind.setdefault(t, "service")
    return dict(sorted(kind.items()))


def canon(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def ratio(a: int, b: int) -> str:
    """和 Java 的 String.format("%.2f") 一樣四捨五入（HALF_UP），0.625 → 0.63。"""
    from decimal import Decimal, ROUND_HALF_UP
    if b == 0:
        return "—"
    return str((Decimal(a) / Decimal(b)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


# ---------------------------------------------------------------- scoring
def score(truth, drawn_pairs, name_map=None):
    """和 GeneralizationScoreTest 同一套規則。drawn_pairs: iterable of (source, target)。
    name_map: LLM 名字 → truth 名字（files-nonames 用）；None 表示名字必須完全相同。"""
    truth_by_key = {f"{s} -> {t}": (s, t, c) for s, t, c, _ in truth}
    scorable = {k for k, (_, _, c) in truth_by_key.items() if c != "variant"}
    drawn = []
    unmatched = set()
    for s, t in drawn_pairs:
        if name_map is not None:
            ms, mt = name_map.get(canon(s)), name_map.get(canon(t))
            if ms is None:
                unmatched.add(s)
            if mt is None:
                unmatched.add(t)
            if ms is None or mt is None:
                continue
            s, t = ms, mt
        k = f"{s} -> {t}"
        if k not in drawn:
            drawn.append(k)
    right = [d for d in drawn if d in truth_by_key and truth_by_key[d][2] != "variant"]
    wrong = [d for d in drawn if d not in truth_by_key]
    missed = [k for k in scorable if k not in drawn]

    def per_class(cls):
        # 逐字照 GeneralizationScoreTest.perClass：truth 裡沒有這一類就「—」；control 只報 R；
        # 錯的邊沒有 class，目標像 db/queue 的算 data，否則算 business。
        tot = [k for k, (_, _, c) in truth_by_key.items() if c == cls]
        hit = sum(1 for k in tot if k in drawn)
        if not tot:
            return "—"
        if cls == "control":
            return "— / " + ratio(hit, len(tot))
        fp = 0
        for d in wrong:
            tgt = d.split(" -> ")[1]
            is_data = bool(re.search(r"db|mongo|redis|mysql|postgres|rabbit|kafka|cache|queue|sql", tgt, re.I))
            if (cls == "data") == is_data:
                fp += 1
        return ratio(hit, hit + fp) + " / " + ratio(hit, len(tot))

    n_drawn = len(right) + len(wrong)
    return {
        "drawn": n_drawn, "right": len(right), "wrong": len(wrong), "missed": len(missed),
        "P": len(right) / n_drawn if n_drawn else 0.0,
        "R": len(right) / len(scorable) if scorable else 0.0,
        "business": per_class("business"), "data": per_class("data"), "control": per_class("control"),
        "wrong_edges": wrong, "missed_edges": missed, "unmatched_names": sorted(unmatched),
    }


def depweaver_edges(name: str):
    """從 docs/generalization/<name>.summary.md 讀工具畫的邊（GeneralizationScoreTest 讀的同一份）。"""
    p = GEN_DIR / f"{name}.summary.md"
    if not p.exists():
        return None
    pat = re.compile(r"^- (\S+) -> (\S+)\s+\(([\w-]+), (\w+)\)")
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        m = pat.match(line)
        if m:
            out.append((m.group(1), m.group(2)))
    return out


def scores_md_row(name: str):
    """docs/generalization/scores.md 裡 DepWeaver 那一列（P、R）。"""
    p = GEN_DIR / "scores.md"
    if not p.exists():
        return None
    for line in p.read_text(encoding="utf-8").splitlines():
        if line.startswith(f"| {name} |"):
            cells = [c.strip() for c in line.strip("|").split("|")]
            return {"drawn": int(cells[1]), "right": int(cells[2]), "wrong": int(cells[3]),
                    "missed": int(cells[4]), "P": float(cells[5]), "R": float(cells[6]),
                    "business": cells[7], "data": cells[8], "control": cells[9]}
    return None


# ---------------------------------------------------------------- repo content
def collect_files(repo: Path):
    """依預算挑檔案。回傳 (descriptor_blobs, source_blobs, manifest) ；manifest 寫進結果檔。"""
    descriptors, sources = [], []
    for path in sorted(repo.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(repo).as_posix()
        if any(part in SKIP_DIR for part in path.parts[len(repo.parts):-1]):
            continue
        try:
            size = path.stat().st_size
        except OSError:
            continue
        if size == 0 or size > 2_000_000:
            continue
        if DESCRIPTOR_NAME.search(rel):
            descriptors.append((rel, size))
        elif path.suffix.lower() in SOURCE_EXT and not re.search(r"(_test|\.test|Test|spec)\.\w+$", rel):
            sources.append((rel, size))
    # 原始碼：名字有線索的優先，再依大小（小的先，涵蓋更多檔）
    sources.sort(key=lambda x: (0 if SOURCE_HINT.search(Path(x[0]).name) else 1, x[1]))

    def take(items, budget):
        blobs, used, n = [], 0, 0
        for rel, size in items:
            if n >= BUDGET["max_files"] or used >= budget:
                break
            try:
                text = (repo / rel).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            truncated = False
            if len(text) > BUDGET["per_file_bytes"]:
                text = text[: BUDGET["per_file_bytes"]]
                truncated = True
            if used + len(text) > budget:
                text = text[: budget - used]
                truncated = True
            blobs.append((rel, text, truncated))
            used += len(text)
            n += 1
        return blobs, used

    d_blobs, d_used = take(descriptors, BUDGET["descriptor_bytes"])
    s_blobs, s_used = take(sources, BUDGET["source_bytes"])
    manifest = {
        "descriptor_files_available": len(descriptors), "descriptor_files_given": len(d_blobs), "descriptor_bytes": d_used,
        "source_files_available": len(sources), "source_files_given": len(s_blobs), "source_bytes": s_used,
        "truncated_files": sum(1 for b in d_blobs + s_blobs if b[2]),
        "files": [b[0] + (" (truncated)" if b[2] else "") for b in d_blobs + s_blobs],
    }
    return d_blobs, s_blobs, manifest


def render_files(blobs):
    parts = []
    for rel, text, truncated in blobs:
        parts.append(f"\n===== FILE: {rel}{' (truncated)' if truncated else ''} =====\n{text}")
    return "".join(parts)


# ---------------------------------------------------------------- prompts
def build_prompt(arm: str, name: str, repo_url: str, vocab: dict, d_blobs, s_blobs) -> str:
    head = (f"You are reconstructing the service dependency map of the open-source microservice system "
            f"`{name}` (GitHub repository {repo_url}).\n{CLASS_TEXT}\n")
    if arm == "knowledge":
        body = ("You are NOT given any file. Answer from what you know about this repository.\n"
                "Use ONLY the component names listed below, exactly as spelled. Output every dependency edge "
                "you believe exists in the repository as it is today; do not invent components.\n\n"
                "Components:\n" + "\n".join(f"- {n}  ({k})" for n, k in vocab.items()) +
                "\n\nOutput JSON only, no prose: {\"edges\": [{\"source\": \"...\", \"target\": \"...\", \"class\": \"business|data|external|control\"}]}")
    elif arm == "files":
        body = ("Below are files from the repository (deployment descriptors and configuration first, then source "
                "code; some files are truncated and many are omitted for budget). Derive the edges from the files; "
                "if the files do not show a dependency, do not add it from memory.\n"
                "Use ONLY the component names listed below, exactly as spelled.\n\n"
                "Components:\n" + "\n".join(f"- {n}  ({k})" for n, k in vocab.items()) +
                "\n\nOutput JSON only, no prose: {\"edges\": [{\"source\": \"...\", \"target\": \"...\", \"class\": \"business|data|external|control\"}]}\n"
                + render_files(d_blobs) + render_files(s_blobs))
    elif arm == "files-nonames":
        body = ("Below are files from the repository (deployment descriptors and configuration first, then source "
                "code; some files are truncated and many are omitted for budget). First list the components "
                "(services, databases/caches/brokers, external hosts, control-plane components) using the names the "
                "deployment descriptors use (Kubernetes workload name, or Compose service name; if neither, the "
                "module/directory name). Then list the edges between them. Derive everything from the files; if the "
                "files do not show a dependency, do not add it from memory.\n\n"
                "Output JSON only, no prose: {\"components\": [{\"name\": \"...\", \"kind\": \"service|database|broker|external|control\"}], "
                "\"edges\": [{\"source\": \"...\", \"target\": \"...\", \"class\": \"business|data|external|control\"}]}\n"
                + render_files(d_blobs) + render_files(s_blobs))
    else:
        raise ValueError(arm)
    return head + body


# ---------------------------------------------------------------- API
def load_api():
    key = os.environ.get("OPENAI_API_KEY", "")
    model = os.environ.get("OPENAI_MODEL", "")
    props = ROOT / "src/main/resources/application.properties"
    if props.exists():
        text = props.read_text(encoding="utf-8")
        if not key:
            m = re.search(r"^openai\.api\.key=(.*)$", text, re.M)
            key = m.group(1).strip() if m else ""
        if not model:
            m = re.search(r"^openai\.api\.model=(.*)$", text, re.M)
            model = m.group(1).strip() if m else ""
    return key, (model or "gpt-4.1-mini")


def chat(key: str, model: str, prompt: str, retries: int = 4):
    body = {"model": model, "temperature": 0, "response_format": {"type": "json_object"},
            "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.load(r)
            return data["choices"][0]["message"]["content"], data.get("usage", {})
        except urllib.error.HTTPError as e:
            msg = e.read().decode(errors="replace")
            if e.code == 429 and "insufficient_quota" in msg:
                raise SystemExit("OpenAI: no credits remaining — top up the account, then rerun.")
            if e.code in (429, 500, 502, 503) and attempt + 1 < retries:
                time.sleep(10 * (attempt + 1))
                continue
            raise SystemExit(f"OpenAI HTTP {e.code}: {msg[:400]}")


def parse_edges(text: str):
    try:
        obj = json.loads(text)
    except json.JSONDecodeError:
        m = re.search(r"\{.*\}", text, re.S)
        obj = json.loads(m.group(0)) if m else {"edges": []}
    edges = []
    for e in obj.get("edges", []) or []:
        s, t = str(e.get("source", "")).strip(), str(e.get("target", "")).strip()
        if s and t:
            edges.append((s, t))
    comps = [str(c.get("name", "")).strip() for c in (obj.get("components", []) or []) if c.get("name")]
    return edges, comps


# ---------------------------------------------------------------- main
def fmt(x):
    return f"{x:.2f}" if isinstance(x, float) else str(x)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repos", help="directory holding the seven checkouts")
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--arms", default="knowledge,files,files-nonames")
    ap.add_argument("--projects", default=",".join(PROJECTS))
    ap.add_argument("--dry-run", action="store_true", help="write prompts and budgets, call nothing")
    ap.add_argument("--selfcheck", action="store_true", help="re-score DepWeaver's own graphs; must equal scores.md")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args()

    projects = [p for p in args.projects.split(",") if p]
    if args.selfcheck:
        ok = True
        for name in projects:
            truth = read_truth(name)
            edges = depweaver_edges(name)
            if edges is None:
                print(f"{name}: no summary.md, skipped")
                continue
            mine = score(truth, edges)
            ref = scores_md_row(name)
            same = ref and all(mine[k] == ref[k] for k in ("drawn", "right", "wrong", "missed")) \
                and all(mine[k] == ref[k] for k in ("business", "data", "control"))
            ok &= bool(same)
            print(f"{name}: drawn {mine['drawn']} right {mine['right']} wrong {mine['wrong']} missed {mine['missed']} "
                  f"P {mine['P']:.2f} R {mine['R']:.2f} | {mine['business']} | {mine['data']} | {mine['control']}"
                  f"  {'== scores.md' if same else '!= scores.md ' + str(ref)}")
        print("selfcheck", "OK" if ok else "MISMATCH")
        return 0 if ok else 1

    if not args.repos:
        ap.error("--repos is required (or --selfcheck)")
    repos = Path(args.repos).resolve()
    arms = [a for a in args.arms.split(",") if a]
    key, model = ("", "dry") if args.dry_run else load_api()
    if not args.dry_run and not key:
        raise SystemExit("no API key: set OPENAI_API_KEY or openai.api.key in application.properties")

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    prompt_dir = OUT_DIR / "prompts"
    raw_dir = OUT_DIR / "raw"
    prompt_dir.mkdir(exist_ok=True)
    raw_dir.mkdir(exist_ok=True)

    jobs = []  # (name, arm, run, prompt, vocab, truth)
    manifests = {}
    for name in projects:
        checkout, gh = PROJECTS[name]
        repo = repos / checkout
        if not repo.is_dir():
            raise SystemExit(f"missing checkout {repo}")
        truth = read_truth(name)
        vocab = vocabulary(truth)
        d_blobs, s_blobs, manifest = collect_files(repo)
        try:
            manifest["commit"] = os.popen(f"git -C '{repo}' rev-parse --short HEAD").read().strip()
        except OSError:
            manifest["commit"] = "?"
        manifests[name] = manifest
        for arm in arms:
            prompt = build_prompt(arm, name, f"https://github.com/{gh}", vocab, d_blobs, s_blobs)
            (prompt_dir / f"{name}.{arm}.txt").write_text(prompt, encoding="utf-8")
            for run in range(1, args.runs + 1):
                jobs.append((name, arm, run, prompt, vocab, truth))

    if args.dry_run:
        lines = ["| 專案 | commit | 描述檔 給/有 | 描述檔 bytes | 原始碼 給/有 | 原始碼 bytes | 截斷 | prompt 大小（files 組，chars） |",
                 "|---|---|---|---|---|---|---|---|"]
        for name, m in manifests.items():
            p = (prompt_dir / f"{name}.files.txt")
            size = p.stat().st_size if p.exists() else 0
            lines.append(f"| {name} | {m['commit']} | {m['descriptor_files_given']}/{m['descriptor_files_available']} | {m['descriptor_bytes']} | "
                         f"{m['source_files_given']}/{m['source_files_available']} | {m['source_bytes']} | {m['truncated_files']} | {size} |")
        (OUT_DIR / "budget.md").write_text(
            "# 給 LLM 的輸入預算（--dry-run 產生）\n\n預算：" + json.dumps(BUDGET) + "\n\n" + "\n".join(lines) + "\n\n"
            + "\n".join(f"## {n}\n\n" + "\n".join(f"- {f}" for f in m["files"]) + "\n" for n, m in manifests.items()),
            encoding="utf-8")
        print("\n".join(lines))
        print(f"\n{len(jobs)} calls would be made ({args.runs} runs × {len(arms)} arms × {len(projects)} projects); prompts in {prompt_dir}")
        return 0

    usage_total = {"prompt_tokens": 0, "completion_tokens": 0}
    results = {}  # (name, arm) -> list of score dicts per run

    def work(job):
        name, arm, run, prompt, vocab, truth = job
        raw_path = raw_dir / f"{name}.{arm}.run{run}.json"
        if raw_path.exists():  # 可續跑
            data = json.loads(raw_path.read_text(encoding="utf-8"))
            return job, data["content"], data.get("usage", {})
        content, usage = chat(key, model, prompt)
        raw_path.write_text(json.dumps({"model": model, "usage": usage, "content": content}, ensure_ascii=False, indent=1), encoding="utf-8")
        return job, content, usage

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        for job, content, usage in ex.map(work, jobs):
            name, arm, run, prompt, vocab, truth = job
            usage_total["prompt_tokens"] += usage.get("prompt_tokens", 0)
            usage_total["completion_tokens"] += usage.get("completion_tokens", 0)
            edges, comps = parse_edges(content)
            if arm == "files-nonames":
                name_map = {canon(n): n for n in vocab}
                sc = score(truth, edges, name_map)
                sc["components_named"] = len(comps)
            else:
                sc = score(truth, edges)
            sc["run"] = run
            results.setdefault((name, arm), []).append(sc)
            print(f"{name:16s} {arm:14s} run{run}: P {sc['P']:.2f} R {sc['R']:.2f} (drawn {sc['drawn']} right {sc['right']} wrong {sc['wrong']} missed {sc['missed']})", flush=True)

    # ---- 寫結果
    csv_path = OUT_DIR / "pure-llm-graph.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["project", "arm", "run", "drawn", "right", "wrong", "missed", "P", "R", "business", "data", "control", "unmatched_names"])
        for (name, arm), runs in sorted(results.items()):
            for sc in sorted(runs, key=lambda s: s["run"]):
                w.writerow([name, arm, sc["run"], sc["drawn"], sc["right"], sc["wrong"], sc["missed"], f"{sc['P']:.3f}", f"{sc['R']:.3f}",
                            sc["business"], sc["data"], sc["control"], ";".join(sc["unmatched_names"])])
        for name in projects:
            ref = scores_md_row(name)
            if ref:
                w.writerow([name, "depweaver-static", 1, ref["drawn"], ref["right"], ref["wrong"], ref["missed"], f"{ref['P']:.3f}", f"{ref['R']:.3f}",
                            ref["business"], ref["data"], ref["control"], ""])

    md = [f"# 純 LLM 抽相依圖 vs DepWeaver 靜態層", "",
          f"- model: `{model}`, temperature 0, JSON mode; {args.runs} runs × {len(arms)} arms × {len(projects)} projects",
          f"- 參考邊集：`docs/generalization/truth/<name>.tsv`（variant 不計）；計分規則同 `GeneralizationScoreTest`",
          f"- 輸入預算：`{json.dumps(BUDGET)}`，實際給了什麼見 `budget.md`／`prompts/`",
          f"- chat calls: {len(jobs)} ({usage_total['prompt_tokens']} prompt + {usage_total['completion_tokens']} completion tokens)", "",
          "## 總表（P / R 為各 run 平均；括號內為最小–最大）", "",
          "| 專案 | 組別 | 畫（avg） | 對 | 錯 | 漏 | P | R | business P/R | data P/R | control P/R |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for name in projects:
        for arm in arms:
            runs = results.get((name, arm), [])
            if not runs:
                continue
            P = [s["P"] for s in runs]
            R = [s["R"] for s in runs]
            avg = lambda k: statistics.mean(s[k] for s in runs)
            md.append(f"| {name} | {arm} | {avg('drawn'):.1f} | {avg('right'):.1f} | {avg('wrong'):.1f} | {avg('missed'):.1f} | "
                      f"{statistics.mean(P):.2f} ({min(P):.2f}–{max(P):.2f}) | {statistics.mean(R):.2f} ({min(R):.2f}–{max(R):.2f}) | "
                      f"{runs[0]['business']} | {runs[0]['data']} | {runs[0]['control']} |")
        ref = scores_md_row(name)
        if ref:
            md.append(f"| {name} | **depweaver-static** | {ref['drawn']} | {ref['right']} | {ref['wrong']} | {ref['missed']} | "
                      f"**{ref['P']:.2f}** | **{ref['R']:.2f}** | {ref['business']} | {ref['data']} | {ref['control']} |")
    md += ["", "## 每一組的錯與漏（run 1）", ""]
    for (name, arm), runs in sorted(results.items()):
        sc = sorted(runs, key=lambda s: s["run"])[0]
        md.append(f"### {name} · {arm}")
        if sc["wrong_edges"]:
            md.append("\n錯（LLM 畫了、參考邊集沒有）：")
            md += [f"- {e}" for e in sc["wrong_edges"]]
        if sc["missed_edges"]:
            md.append("\n漏（參考邊集有、LLM 沒畫）：")
            md += [f"- {e}" for e in sc["missed_edges"]]
        if sc["unmatched_names"]:
            md.append("\n對不到參考邊集的名字（這些邊不計分）：" + ", ".join(sc["unmatched_names"]))
        if not sc["wrong_edges"] and not sc["missed_edges"]:
            md.append("\n全對。")
        md.append("")
    (OUT_DIR / "pure-llm-graph.md").write_text("\n".join(md), encoding="utf-8")
    print(f"\nwrote {csv_path} and pure-llm-graph.md")
    return 0


if __name__ == "__main__":
    sys.exit(main())
