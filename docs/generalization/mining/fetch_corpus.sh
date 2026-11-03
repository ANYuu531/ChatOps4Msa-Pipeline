#!/bin/bash
# 依 corpus.tsv（與 holdout-e.tsv）把每個 repo 取到固定的 sha（只抓那一個 commit）。
# 用法：bash fetch_corpus.sh <corpus.tsv> <目的目錄>   會另外寫 <目的目錄>/list.tsv（name<TAB>路徑）給 MiningProbeTest。
# 不要放在 /tmp（macOS 會清掉 /private/tmp 裡三天沒動的檔案）。
set -u
TSV=${1:?usage: fetch_corpus.sh <corpus.tsv> <dest-dir>}
DEST=${2:?usage: fetch_corpus.sh <corpus.tsv> <dest-dir>}
mkdir -p "$DEST"
: > "$DEST/list.tsv"
tail -n +2 "$TSV" | while IFS=$'\t' read -r repo stars lang sha verdict; do
  name=${repo//\//__}
  dir="$DEST/$name"
  if [ -z "$(ls -A "$dir" 2>/dev/null | grep -v '^.git$')" ]; then
    mkdir -p "$dir"
    ( cd "$dir" && git init -q && (git remote add origin "https://github.com/$repo.git" 2>/dev/null || true) \
        && git fetch -q --depth 1 origin "$sha" && git checkout -q FETCH_HEAD ) \
      || { echo "FAILED $repo"; continue; }
  fi
  printf '%s\t%s\n' "$name" "$dir" >> "$DEST/list.tsv"
done
wc -l < "$DEST/list.tsv"
