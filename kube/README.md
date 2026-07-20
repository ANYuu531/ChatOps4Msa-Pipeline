# kube/

Put your kubeconfig here as `kube/config` (git-ignored). The `k8s-mcp-server` container
mounts it read-only and uses it to run kubectl against the cluster.

The cluster runs on a **separate machine on the LAN**, so the kubeconfig's
`server:` must be that machine's LAN address — **not `127.0.0.1`**, which inside
the container resolves to the container itself.

```bash
# Wrong: unreachable from inside the container
#   server: https://127.0.0.1:6443
# Right:
#   server: https://192.168.100.106:6443
```

Check it before starting:

```bash
grep server: kube/config
```

Then confirm the MCP server can actually reach the cluster:

```bash
docker compose up -d k8s-mcp-server
docker compose exec k8s-mcp-server kubectl get ns
```

If that last command lists namespaces, the whole chain is working. If it hangs or
refuses the connection, the API server is not reachable at the address in the
kubeconfig — fix that before looking at anything else.

## Cluster-side requirement

The API server must be listening on an address the LAN can reach. For `kind`,
that means creating the cluster with:

```yaml
# kind-config.yaml
networking:
  apiServerAddress: "192.168.100.106"   # the cluster machine's LAN IP
  apiServerPort: 6443
```

`k3s` exposes the API server on all interfaces by default and needs no such
setting — which is one reason it is a better fit for the experiment machine.
