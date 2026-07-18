# Federated AI Agent Search — Proof of Concept

A minimal, working spike for **[#8424 — federated AI agent search across registry instances](https://github.com/Apicurio/apicurio-registry/issues/8424)**.

It demonstrates the core, highest-risk part of the project — **fanning a search out to peer registries, merging the results, and degrading gracefully when a peer is unreachable** — built as a self-contained slice on top of the existing A2A agent-search API, with **zero changes to existing classes**.

> Scope: this is a feasibility spike, not the finished feature. It intentionally proves the backend federation logic and leaves the peer-storage model, auth propagation, and UI as design work for the mentorship (see [Design notes](#design-notes--production-path)).

---

## What it proves (mapped to the #8424 deliverables)

| #8424 deliverable | Status in this POC |
|---|---|
| Peer registration API (URL + optional credentials) | ✅ `POST` / `GET` / `DELETE /apis/registry/v3/federation/peers` (in-memory) |
| Federated search endpoint (fan out to peers, merge results) | ✅ `GET /apis/registry/v3/federation/agents/search` — concurrent HTTP fan-out, results tagged by source and merged |
| Resilient handling of unavailable peers (timeouts, partial results) | ✅ per-peer timeout, `degraded` flag, reachable sources still returned — never a 500 |
| Integration tests with multiple instances | ◑ automated merge + degrade test against a live stub peer; production extends this to a multi-instance **Testcontainers** suite |
| "Remote Registries" UI page | ✗ deliberately out of scope for the spike (backend-first) |

---

## Architecture

A single new package, `io.apicurio.registry.federation`, layered on top of the existing
`/.well-known/agents` search endpoint. Every source — local and remote — is just an agent-search
endpoint, so a peer is simply another registry.

```
GET /apis/registry/v3/federation/agents/search
        │
        ├── local  ── in-process call to the existing agent search
        │
        └── peers ── concurrent HTTP fan-out (JDK HttpClient, 3s per-peer timeout)
                        │
                        ▼
              merge + tag each result with its source
              a failed/timed-out peer ⇒ degraded = true, partial results
```

**Files added (main):**
- `federation/FederatedPeer.java`, `federation/PeerRegistry.java` — in-memory peer store
- `federation/FederatedAgentSearchService.java` — concurrent fan-out, merge, degradation
- `federation/rest/FederationResource.java` — peer CRUD + federated search endpoints
- `federation/rest/beans/*` — request/response DTOs (reuse the existing `AgentSearchResult`)

**Files added (test):**
- `noprofile/federation/FederationResourceTest.java` — merge + graceful-degradation integration test
- `noprofile/federation/FederationTestProfile.java` — enables A2A, disables dev-services

---

## Run it yourself

### 1. Start the registry (in-memory H2, A2A enabled, no containers needed)
```powershell
$env:JAVA_HOME="C:\apicurio\tools\jdk-21.0.11+10"
.\mvnw.cmd quarkus:dev -pl app "-Daether.syncContext.named.factory=noop" "-Dapicurio.a2a.enabled=true" "-Dquarkus.devservices.enabled=false" "-Dquarkus.kubernetes-client.devservices.enabled=false"
```

### 2. Register a peer
```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/apis/registry/v3/federation/peers `
  -ContentType 'application/json' -Body '{"name":"self","url":"http://localhost:8080"}'
```

### 3. Federated search
```
http://localhost:8080/apis/registry/v3/federation/agents/search
```
Each returned agent is tagged with the `source` it came from, and `sources[]` reports which peers
answered. Register a peer with an unreachable URL and search again: the response comes back
`degraded: true` with that peer marked `available: false`, while every reachable source still
returns — demonstrating partial results instead of a failed request.

### 4. Run the automated test
```powershell
.\mvnw.cmd -B -pl app test "-Dtest=FederationResourceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Daether.syncContext.named.factory=noop"
```

---

## Design notes & production path

The spike deliberately keeps decisions open that belong in the mentorship design phase:

- **Peer persistence & credentials** — peers are held in memory here; production persists them in
  `RegistryStorage` and carries per-peer credentials for authenticated server-to-server calls.
- **Local search reuse** — the local branch is queried in-process; a production version would
  extract a shared local-search service rather than depend on the well-known resource.
- **Resilience** — the same *4xx/partial-not-500* discipline used elsewhere in the codebase is
  applied to peer failures. A natural extension is periodic peer **health pinging** (the "liveness"
  model in the AI-native federation vision) so peer status is known before a query.
- **Merge semantics** — de-duplication of the same agent seen across instances, result ordering,
  and federated pagination are follow-ups.
- **Testing** — the automated test uses an in-JVM stub peer for speed and determinism; the
  deliverable extends this to a true multi-instance **Testcontainers** suite.
- **UI** — the "Remote Registries" management page (React/TS) is the remaining deliverable.
