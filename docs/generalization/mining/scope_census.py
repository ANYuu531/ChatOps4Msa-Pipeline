#!/usr/bin/env python3
"""How often does the corpus use what the static layer does not read (COMPSAC Table XI)?

For every checkout in a list, count the presence of each out-of-scope construct by plain
text search (no parsing): languages without a grammar, Kubernetes Secret references /
Ingress / Kustomize, Compose extends / include / profiles, gRPC stubs in languages whose
query pack has no gRPC pattern, Dapr service invocation, other gateways' route files. The
numbers are "systems in which the construct occurs at all", not "edges lost": an
occurrence may carry no dependency. Run:

  python3 -I scope_census.py --list <name<TAB>path list> --out scope-census.md
"""
import argparse
import collections
import os
import re

SKIP = {".git", "node_modules", "vendor", "target", "build", "dist", "bin", "obj", ".venv", "venv",
        "__pycache__", "third_party", "testdata", ".next", "coverage"}
TEST = re.compile(r"(^|/)(test|tests|testing|spec|__tests__|e2e)(/|$)", re.I)

GRAMMAR = {".java", ".kt", ".kts", ".py", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx", ".mts", ".cts",
           ".go", ".php", ".cs"}
LLM_ONLY = {".rb", ".rs"}
UNREAD = {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".scala", ".groovy", ".swift", ".ex", ".exs",
          ".clj", ".hs", ".dart", ".erl", ".fs", ".lua", ".vb"}

# (label, file filter, regex)
YAML = (".yml", ".yaml")
CHECKS = [
    ("Kubernetes Secret 參照（secretKeyRef／secretRef）", YAML, re.compile(r"\bsecret(KeyRef|Ref)\s*:")),
    ("Kubernetes Ingress", YAML, re.compile(r"^kind:\s*Ingress\s*$", re.M)),
    ("Kustomize", ("kustomization.yaml", "kustomization.yml"), None),
    ("Compose extends", "compose", re.compile(r"^\s+extends\s*:", re.M)),
    ("Compose include", "compose", re.compile(r"^include\s*:", re.M)),
    ("Compose profiles", "compose", re.compile(r"^\s+profiles\s*:", re.M)),
    ("gRPC stub：Java／Kotlin", (".java", ".kt"), re.compile(r"ManagedChannelBuilder|NettyChannelBuilder|@GrpcClient")),
    ("gRPC stub：Python", (".py",), re.compile(r"grpc\.(insecure|secure|aio\.insecure)_channel")),
    ("gRPC stub：JavaScript／TypeScript", (".js", ".ts", ".mjs", ".cjs"), re.compile(r"@grpc/grpc-js|grpc\.credentials\.createInsecure|ClientsModule\.register\(.*Transport\.GRPC", re.S)),
    ("Dapr 服務呼叫", None, re.compile(r"InvokeMethod(Async)?\s*\(|invokeMethod\s*\(|/v1\.0/invoke/|dapr\.invoke|DaprClient")),
    ("Traefik／Kong 路由", None, re.compile(r"traefik\.http\.routers|^_format_version:", re.M)),
    ("Kubernetes Gateway API（HTTPRoute）", YAML, re.compile(r"^kind:\s*HTTPRoute\s*$", re.M)),
    ("NATS／Redis pub/sub 等其他 broker", None, re.compile(r"nats://|\bnats\.connect\(|\bNatsConnection|redis\.publish\(|\.Publish\(ctx")),
]


def compose_file(name):
    return re.match(r"(docker-)?compose[^/]*\.ya?ml$", name.lower()) is not None


def census(root):
    langs = collections.Counter()
    hits = set()
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP and not x.startswith(".")]
        rel_d = os.path.relpath(d, root)
        if TEST.search(rel_d.replace(os.sep, "/")):
            continue
        for f in files:
            ext = os.path.splitext(f)[1].lower()
            if ext in GRAMMAR:
                langs["grammar"] += 1
            elif ext in LLM_ONLY:
                langs["llm"] += 1
            elif ext in UNREAD:
                langs["unread"] += 1
            p = os.path.join(d, f)
            text = None
            for label, filt, rx in CHECKS:
                if label in hits:
                    continue
                if filt == "compose":
                    if not compose_file(f):
                        continue
                elif isinstance(filt, tuple):
                    if not (f.lower().endswith(filt) or f.lower() in filt):
                        continue
                elif filt is None and ext not in GRAMMAR | {".yml", ".yaml", ".json", ".properties", ".conf", ".toml"}:
                    continue
                if rx is None:
                    hits.add(label)
                    continue
                if text is None:
                    try:
                        if os.path.getsize(p) > 512 * 1024:
                            text = ""
                        else:
                            with open(p, encoding="utf-8", errors="replace") as fh:
                                text = fh.read()
                    except OSError:
                        text = ""
                if rx.search(text):
                    hits.add(label)
    return langs, hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    rows = [l.rstrip("\n").split("\t")[:2] for l in open(args.list) if l.strip() and not l.startswith("#")]
    per = {}
    for name, path in rows:
        per[name] = census(path)
    n = len(per)
    only_grammar = sum(1 for l, _ in per.values() if l["llm"] == 0 and l["unread"] == 0)
    with_llm = sum(1 for l, _ in per.values() if l["llm"] > 0)
    with_unread = sum(1 for l, _ in per.values() if l["unread"] > 0)
    share = [l["grammar"] / max(1, l["grammar"] + l["llm"] + l["unread"]) for l, _ in per.values()]
    lines = [f"# 範圍普查（{n} 個系統）", "",
             "每一列是「這個寫法在幾個系統裡出現過」，不是「漏了幾條邊」：出現不代表帶有相依關係。", "",
             "| 項目 | 系統數 | 比例 |", "|---|---|---|",
             f"| 原始碼全部是有文法的語言 | {only_grammar} | {only_grammar / n:.0%} |",
             f"| 含 Ruby／Rust（只由 LLM 閱讀） | {with_llm} | {with_llm / n:.0%} |",
             f"| 含其他不讀的語言（C／C++、Scala、Groovy…） | {with_unread} | {with_unread / n:.0%} |",
             f"| 原始碼檔案中有文法的比例（中位數） | {sorted(share)[n // 2]:.0%} | |"]
    for label, _, _ in CHECKS:
        c = sum(1 for _, h in per.values() if label in h)
        lines.append(f"| {label} | {c} | {c / n:.0%} |")
    lines += ["", "## 各系統", "", "| 系統 | 有文法／LLM／不讀（檔案數） | 出現的範圍外寫法 |", "|---|---|---|"]
    for name in sorted(per):
        l, h = per[name]
        lines.append(f"| {name} | {l['grammar']}／{l['llm']}／{l['unread']} | {'、'.join(x for x, _, _ in CHECKS if x in h) or '—'} |")
    open(args.out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines[:8 + len(CHECKS)]))


if __name__ == "__main__":
    main()
