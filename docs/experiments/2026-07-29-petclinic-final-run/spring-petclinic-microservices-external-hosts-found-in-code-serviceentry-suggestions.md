## External hosts found in code (ServiceEntry suggestions)

Applying these lets Istio observe and attribute the external edges,
which it otherwise cannot see at all.

```yaml
External hosts found in source code: 1
- github.com:443 (HTTPS) [Evidence: spring-petclinic-config-server/src/main/resources/application.yml] — TLS originated by the app, so runtime evidence will appear in istio_tcp_* metrics, not istio_requests_total


# ServiceEntry manifests for the external hosts found in the source code.
# Applying these lets Istio observe and correctly attribute these edges;
# without them the traffic is routed through PassthroughCluster and the
# real hostname is lost from the metrics.
#
#   kubectl apply -f service-entries.yaml
#

apiVersion: networking.istio.io/v1beta1
kind: ServiceEntry
metadata:
  name: github-com
  namespace: petclinic
  annotations:
    chatops4msa.io/evidence: "spring-petclinic-config-server/src/main/resources/application.yml"
spec:
  hosts:
    - github.com
  ports:
    - number: 443
      name: https
      protocol: HTTPS
  location: MESH_EXTERNAL
  resolution: DNS

```
