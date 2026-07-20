# Fork change: `apply_manifest` MCP tool

ChatOps4Msa posts an **"Apply ServiceEntries"** button after showing the
ServiceEntry manifest. When clicked, `ButtonListener` calls the k8s MCP server's
`apply_manifest` tool with the rendered YAML.

That tool cannot be `execute_kubectl`: `execute_kubectl` only takes a `command`
string, supports pipes for *output* filtering only, and has no stdin/heredoc — so
an inline manifest cannot be fed through it. Hence a dedicated tool.

## Status: implemented in the fork

Added to `k8s-mcp-server` (the fork at `~/Downloads/k8s-mcp-server`):
`src/k8s_mcp_server/server.py` → `apply_manifest`. It writes the manifest to a
temp file and runs `kubectl apply -f <file>` through the same
`_execute_tool_command` path as the other tools (same validation, timeout, and
`CommandResult` serialisation).

Verified so far:
- `python -m py_compile server.py` passes.
- The fork's own security layer allows `kubectl apply -f <file>` and
  `kubectl apply -n <ns> -f <file>` in strict mode (`kubectl apply` is not a
  restricted command), while still rejecting `kubectl delete -f ...` — i.e.
  validation is really running.

Not yet verified (needs the container + a live cluster): the end-to-end apply.

## Contract ChatOps4Msa expects

```
tool name: apply_manifest
arguments:
  manifest  : string  (required)  the YAML to apply (may be multi-document)
  namespace : string  (optional)  default namespace for resources without one
  timeout   : integer (optional)  seconds; default 300
returns: the same CommandResult shape as execute_kubectl (status + output)
```

Call site: [ButtonListener.java](../src/main/java/ntou/soselab/chatops4msa/Service/DiscordService/ButtonListener.java)
(`toolkitMcpCallTool("k8s", "apply_manifest", {manifest, namespace, timeout})`).

Note: the generated ServiceEntry manifests already carry `metadata.namespace`, and
ChatOps4Msa passes the *same* namespace as `-n`, so they never conflict. (kubectl
errors only if `-n` disagrees with an explicit `metadata.namespace`.)

## Rebuild and verify end-to-end

```bash
# from the ChatOps4Msa-Pipeline repo (K8S_MCP_SRC points at the fork)
docker compose build k8s-mcp-server
docker compose up -d k8s-mcp-server

# 1) tool is exposed
docker compose exec k8s-mcp-server kubectl version --client >/dev/null && echo kubectl-ok
#    from the bot: toolkit-mcp-list-tools should now list `apply_manifest`

# 2) full path: run a dependency analysis on a repo with an external host
#    (e.g. one calling https://www.googleapis.com). After the manifest is shown,
#    click "Apply ServiceEntries"; the result should show
#    `serviceentry.networking.istio.io/... created`.

# 3) confirm on the cluster
kubectl get serviceentry -n <namespace>
```

If `kubectl apply` ever gets added to a restricted/read-only allowlist on the
server, it must remain permitted for this tool.
