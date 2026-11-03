#!/usr/bin/env python3
"""Collect the rule-mining corpus from GitHub with fixed, written-down criteria.

No LLM is involved in picking systems. The criteria are the ones we used for the
hold-out sets, made mechanical:

  1. GitHub repository search: topic in {microservices, microservice,
     microservices-architecture} x primary language in the eight languages the static
     layer has a tree-sitter grammar for; public, not a fork, not archived, >= 30 stars.
  2. Not one of the systems already used anywhere in the evaluation (EXCLUDE).
  3. Not a framework / library / list: name or description does not match FRAMEWORK_RE.
  4. Repo tree (default branch HEAD, pinned to its sha):
       - has a deployment descriptor (Compose file, Kubernetes/Helm yaml directory, Chart.yaml);
       - >= 6 deployable services: directories holding a Dockerfile, or the services of
         the largest Compose file, whichever is larger;
       - >= 80 % of source files are in a supported language;
       - the tree is not truncated by the API (very large monorepos are skipped).
  5. Author screening (screening.tsv, one reason per repo): an application system made of
     >= 6 deployable components that talk to each other (a demo, sample or real product);
     not a framework, middleware or platform product, developer tool, or a collection of
     independent examples. Repos not yet screened are written to to-screen.tsv (description,
     top-level directories, Compose service names) and are not eligible until screened.
  6. Sorted by stars (desc, then full name); the first N+1 eligible are kept. One of them,
     drawn with a fixed seed, becomes hold-out E and is never mined; the other N are the
     corpus.

Run:  python3 -I collect_corpus.py [--n 100] [--out .]
Needs the `gh` CLI (authenticated). Writes candidates.tsv, corpus.tsv, holdout-e.tsv,
to-screen.tsv; API answers are cached in .cache/ so a rerun after screening is cheap.
"""
import argparse
import base64
import json
import os
import random
import re
import subprocess
import sys
import time

TOPICS = ["microservices", "microservice", "microservices-architecture"]
LANGS = ["Java", "Python", "Go", "JavaScript", "TypeScript", "PHP", "Kotlin", "C#"]
SEED = 20261103

# Every system used so far (induction, hold-out B/C/D, rejected candidates, MicroDepGraph).
EXCLUDE = {
    "googlecloudplatform/bank-of-anthos", "spring-petclinic/spring-petclinic-microservices",
    "ewolff/microservice-kubernetes", "ewolff/microservice",
    "selimhorri/ecommerce-microservice-backend-app", "sqshq/piggymetrics",
    "descartesresearch/teastore", "googlecloudplatform/microservices-demo",
    "instana/robot-shop", "fudanselab/train-ticket", "open-telemetry/opentelemetry-demo",
    "macrozheng/mall-swarm", "delimitrou/deathstarbench", "nashtech-garage/yas",
    "aspnetrun/run-aspnetcore-microservices", "kiali/demos", "apache/skywalking-showcase",
    "mehdihadeli/food-delivery-microservices", "microservice-api-patterns/lakesidemutual",
    "jferrater/tap-and-eat-microservices", "yidongnan/spring-cloud-netflix-example",
    "zpng/spring-cloud-microservice-examples", "thangchung/go-coffeeshop",
    "clowee/microservicedataset",
}
# Repo basenames are matched too, so a fork or a rename of a used system is also excluded.
EXCLUDE_NAMES = {x.split("/")[1] for x in EXCLUDE} - {"demos", "microservice"}

FRAMEWORK_RE = re.compile(
    r"\b(framework|library|sdk|awesome|toolkit|generator|plugin|cli|operator|"
    r"interview|cheat ?sheet|roadmap)\b", re.I)

SUPPORTED_EXT = {".java", ".py", ".go", ".js", ".mjs", ".cjs", ".jsx", ".ts", ".tsx",
                 ".php", ".kt", ".kts", ".cs"}
UNSUPPORTED_EXT = {".rs", ".rb", ".cpp", ".cc", ".cxx", ".c", ".h", ".hpp", ".scala",
                   ".ex", ".exs", ".clj", ".hs", ".dart", ".swift", ".erl", ".fs", ".lua"}
COMPOSE_RE = re.compile(r"(^|/)(docker-)?compose[^/]*\.ya?ml$", re.I)
K8S_DIR_RE = re.compile(r"(^|/)(k8s|kubernetes|kube|helm|charts?|manifests?|deploy(ment)?s?)/[^/]+\.ya?ml$", re.I)
VENDOR_RE = re.compile(r"(^|/)(node_modules|vendor|third_party|\.git)/")


CACHE = {}


def gh(path, params=None):
    ck = path + "?" + "&".join(f"{k}={v}" for k, v in sorted((params or {}).items()))
    if ck in CACHE:
        return CACHE[ck]
    res = _gh(path, params)
    CACHE[ck] = res
    return res


def _gh(path, params=None):
    cmd = ["gh", "api", "-H", "Accept: application/vnd.github+json", "--method", "GET", path]
    for k, v in (params or {}).items():
        cmd += ["-f", f"{k}={v}"]
    for attempt in range(4):
        p = subprocess.run(cmd, capture_output=True, text=True)
        if p.returncode == 0:
            return json.loads(p.stdout)
        if "rate limit" in p.stderr.lower() or "secondary" in p.stderr.lower():
            time.sleep(30 * (attempt + 1))
            continue
        raise RuntimeError(f"gh api {path}: {p.stderr.strip()[:300]}")
    raise RuntimeError(f"gh api {path}: rate limited")


def search_all():
    seen = {}
    for topic in TOPICS:
        for lang in LANGS:
            for page in (1, 2):
                q = f"topic:{topic} language:\"{lang}\" archived:false fork:false stars:>=30"
                params = {"q": q, "sort": "stars", "order": "desc", "per_page": "100", "page": str(page)}
                cached = ("search/repositories?" + "&".join(f"{k}={v}" for k, v in sorted(params.items()))) in CACHE
                res = gh("search/repositories", params)
                if not cached:
                    time.sleep(2.2)  # search API: 30 requests / minute
                for it in res.get("items", []):
                    seen.setdefault(it["full_name"], it)
                if len(res.get("items", [])) < 100:
                    break
    return list(seen.values())


def compose_services(owner_repo, sha, path):
    try:
        blob = gh(f"repos/{owner_repo}/contents/{path}", {"ref": sha})
        text = base64.b64decode(blob.get("content", "")).decode("utf-8", "replace")
    except Exception:
        return 0
    n, inside, indent = 0, False, None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if re.match(r"^services:\s*$", line):
            inside, indent = True, None
            continue
        if inside:
            if not line.startswith((" ", "\t")):
                inside = False
                continue
            m = re.match(r"^(\s+)[A-Za-z0-9_.-]+:\s*$", line)
            if m:
                if indent is None:
                    indent = len(m.group(1))
                if len(m.group(1)) == indent:
                    n += 1
    return n


def assess(it):
    full = it["full_name"]
    low = full.lower()
    if low in EXCLUDE or it["name"].lower() in EXCLUDE_NAMES:
        return None, "already used in the evaluation"
    if FRAMEWORK_RE.search(it["name"].replace("-", " ")) or FRAMEWORK_RE.search(it.get("description") or ""):
        return None, "framework/library/list by name or description"
    if it.get("size", 0) > 300_000:
        return None, "repo > 300 MB"
    branch = it["default_branch"]
    sha = gh(f"repos/{full}/commits/{branch}")["sha"]
    tree = gh(f"repos/{full}/git/trees/{sha}", {"recursive": "1"})
    if tree.get("truncated"):
        return sha, "tree truncated (very large repo)"
    paths = [t["path"] for t in tree["tree"] if t["type"] == "blob" and not VENDOR_RE.search(t["path"])]
    composes = [p for p in paths if COMPOSE_RE.search(p)]
    k8s = [p for p in paths if K8S_DIR_RE.search(p) or p.endswith("Chart.yaml")]
    if not composes and not k8s:
        return sha, "no deployment descriptor"
    sup = sum(1 for p in paths if os.path.splitext(p)[1].lower() in SUPPORTED_EXT)
    uns = sum(1 for p in paths if os.path.splitext(p)[1].lower() in UNSUPPORTED_EXT)
    if sup == 0 or sup / (sup + uns) < 0.8:
        return sha, f"supported-language share {sup}/{sup + uns} < 0.8"
    docker_dirs = {os.path.dirname(p) for p in paths
                   if re.match(r"^Dockerfile", os.path.basename(p))
                   and not re.search(r"(^|/)(tests?|e2e|examples?|samples?)/", p, re.I)}
    services = len(docker_dirs)
    if services < 6 and composes:
        biggest = max(composes, key=lambda p: next(t["size"] for t in tree["tree"] if t["path"] == p))
        services = max(services, compose_services(full, sha, biggest))
    if services < 6:
        return sha, f"only {services} deployable services"
    tops = sorted({p.split("/")[0] for p in paths if "/" in p})
    SCREEN_INFO[full] = {"description": (it.get("description") or "").replace("\t", " ")[:200],
                         "top": " ".join(tops[:25]),
                         "compose": " ".join(compose_names(full, sha, composes, tree)[:30])}
    return sha, f"ok: {services} services, {len(composes)} compose, {len(k8s)} k8s/helm yaml"


SCREEN_INFO = {}


def compose_names(full, sha, composes, tree):
    if not composes:
        return []
    biggest = max(composes, key=lambda p: next(t["size"] for t in tree["tree"] if t["path"] == p))
    try:
        blob = gh(f"repos/{full}/contents/{biggest}", {"ref": sha})
        text = base64.b64decode(blob.get("content", "")).decode("utf-8", "replace")
    except Exception:
        return []
    names, inside, indent = [], False, None
    for line in text.splitlines():
        if re.match(r"^services:\s*$", line):
            inside = True
            continue
        if inside and line and not line.startswith((" ", "\t")) and not line.lstrip().startswith("#"):
            inside = False
        m = re.match(r"^(\s+)([A-Za-z0-9_.-]+):\s*$", line) if inside else None
        if m:
            indent = indent or len(m.group(1))
            if len(m.group(1)) == indent:
                names.append(m.group(2))
    return names


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--max-ok", type=int, default=190,
                    help="stop after this many repos pass the mechanical criteria (screening pool)")
    ap.add_argument("--out", default=os.path.dirname(os.path.abspath(__file__)))
    args = ap.parse_args()

    cache_dir = os.path.join(args.out, ".cache")
    os.makedirs(cache_dir, exist_ok=True)
    cache_file = os.path.join(cache_dir, "gh.json")
    if os.path.exists(cache_file):
        CACHE.update(json.load(open(cache_file)))
    screening = {}
    spath = os.path.join(args.out, "screening.tsv")
    if os.path.exists(spath):
        for row in open(spath, encoding="utf-8"):
            cols = row.rstrip("\n").split("\t")
            if len(cols) >= 2 and cols[0] != "repo":
                screening[cols[0]] = (cols[1], cols[2] if len(cols) > 2 else "")

    items = search_all()
    items.sort(key=lambda it: (-it["stargazers_count"], it["full_name"].lower()))
    print(f"{len(items)} unique search hits", file=sys.stderr)

    rows, eligible, mech_ok = [], [], 0
    for it in items:
        if len(eligible) >= args.n + 1 or mech_ok >= args.max_ok:
            break
        if len(rows) % 25 == 0:
            json.dump(CACHE, open(cache_file, "w"))
        try:
            sha, why = assess(it)
        except Exception as e:  # unreadable repo: record and move on
            sha, why = None, f"error: {e}"
        ok = why.startswith("ok")
        mech_ok += ok
        if ok:
            verdict = screening.get(it["full_name"])
            if verdict is None:
                why += " | not screened yet"
                ok = False
            elif verdict[0] != "yes":
                why = f"screened out: {verdict[1]}"
                ok = False
        rows.append((it["full_name"], it["stargazers_count"], it.get("language") or "", sha or "", why))
        if ok:
            eligible.append(rows[-1])
        print(f"[{len(eligible):3d}] {it['full_name']}: {why}", file=sys.stderr)

    json.dump(CACHE, open(cache_file, "w"))
    with open(os.path.join(args.out, "to-screen.tsv"), "w", encoding="utf-8") as f:
        f.write("repo\tstars\tdescription\ttop-level dirs\tcompose services\n")
        for r in rows:
            if r[4].endswith("not screened yet"):
                info = SCREEN_INFO.get(r[0], {})
                f.write(f"{r[0]}\t{r[1]}\t{info.get('description', '')}\t{info.get('top', '')}\t{info.get('compose', '')}\n")
    if len(eligible) < args.n + 1:
        print(f"only {len(eligible)} eligible; screen to-screen.tsv and rerun", file=sys.stderr)
        return
    holdout = random.Random(SEED).choice(eligible)
    corpus = [r for r in eligible if r is not holdout][: args.n]

    hdr = "repo\tstars\tlanguage\tsha\tverdict\n"
    with open(os.path.join(args.out, "candidates.tsv"), "w") as f:
        f.write(hdr + "".join("\t".join(map(str, r)) + "\n" for r in rows))
    with open(os.path.join(args.out, "corpus.tsv"), "w") as f:
        f.write(hdr + "".join("\t".join(map(str, r)) + "\n" for r in corpus))
    with open(os.path.join(args.out, "holdout-e.tsv"), "w") as f:
        f.write(hdr + "\t".join(map(str, holdout)) + "\n")
    print(f"corpus {len(corpus)}, hold-out E = {holdout[0]} @ {holdout[3][:7]}", file=sys.stderr)


if __name__ == "__main__":
    main()
