# Release: CDC failure handling (PR1 + PR2)

## What went wrong on 07-28

`liveness.include: debezium` **replaced** the default liveness group rather than
adding to it. Two consequences:

1. `livenessState` was not monitored anywhere — Spring's own liveness signal was
   effectively disabled.
2. The pod's entire liveness contract became "did the CDC engine last report
   success."

When Oracle reported `offset scn 13136367402` was no longer in redo — a permanent
condition — the health indicator latched DOWN, liveness failed, Kubernetes killed
the pod, the engine restarted, hit the same SCN, and died again. Forever, silently.

The engine code made this inevitable: `buildCompletionCallback` logged the error
and returned. Nothing restarted the engine in-process, so liveness was acting as
the supervisor the service never had.

## Changes

| File | Change |
|---|---|
| `application.yaml` | liveness = `livenessState`; `debezium` moved to readiness |
| `deployment.yaml` | `startupProbe` added; dead `initialDelaySeconds: 300` removed |
| `DebeziumFailureType.java` | new — permanent vs recoverable classification |
| `DebeziumFailureClassifier.java` | new — maps Throwable → type |
| `DebeziumHealthIndicator.java` | failure type + recoverability in health details |
| `DebeziumSourceEventListener.java` | supervisor loop replaces fire-and-forget executor |
| Grafana | 5 rules (see `debezium-alerts.yaml`) |

## Behaviour change

| Scenario | Before | After |
|---|---|---|
| Transient Oracle blip | Pod killed, silent restart | In-process restart, backoff, `restart_storm` alert if it recurs |
| Offset SCN expired | Infinite crashloop, no alert | Supervisor stops, pod NotReady, `debezium_fatal` pages in 1m |
| Slow LogMiner start | 300s blind window | `startupProbe` covers it, then real probes take over |

## Why `startupProbe` and not `initialDelaySeconds`

While a `startupProbe` is running, Kubernetes does not run liveness or readiness
at all. Keeping `initialDelaySeconds: 300` would add a further 300s of blindness
*after* startup already succeeded.

## Rollout

1. Deploy to the lower environment.
2. Verify `/actuator/health/liveness` returns 200 with only `livenessState`,
   and `/actuator/health/readiness` includes `debezium`.
3. Confirm `converto_debezium_connector_up` is 1 in Prometheus.
4. Fault-inject a transient failure — drop the Oracle listener for 30s. Expect:
   pod stays Running, readiness flips, supervisor retries, recovers.
5. Fault-inject a permanent failure — point the connector at a stale offset.
   Expect: `converto_debezium_connector_fatal` = 1, no restart, `debezium_fatal`
   fires, pod stays Running but NotReady.
6. Import the alert rules.

Rollback is config-only for PR1; PR2 reverts cleanly since `EventHandler` is untouched.

## Open decisions

**1. `show-details: always`** exposes Oracle error text, SCN values and table names
on `/actuator/health`. Check whether ingress routes `/actuator` on port 8093. If it
does, either switch to `when-authorized` or move management to a separate,
non-ingressed port. Probes are unaffected — they only read the status code.

**2. Root cause of the 07-28 SCN loss** is still unconfirmed. Three candidates,
and the fix differs for each:
   - pod down longer than archive log retention
   - a long-running full snapshot held the offset back (the ORA-01555 problem the
     requirements doc targets)
   - RMAN deleted archive logs Debezium had not consumed

   If it is the third, none of this release prevents a recurrence — that needs an
   Oracle-side `ARCHIVELOG DELETION POLICY` change.

**3. Classifier patterns are pinned to the current Debezium version.** Add
`DebeziumFailureClassifierTest` with the exact 07-28 message before merge, so a
future Debezium upgrade that rewords it fails CI rather than silently
reclassifying a permanent failure as retryable.

## Deferred to PR3

Predictive SCN headroom: compare the committed offset SCN against
`MIN(FIRST_CHANGE#)` from `V$ARCHIVED_LOG` for logs still on disk, alert below
~25% headroom. This is the only change that prevents the incident rather than
reporting it. Pair with `debezium_metrics_MilliSecondsBehindSource` as the
leading indicator.

## Not addressed (independent of this release)

From the `EventHandler` review:
- poison events are silently dropped and the offset advances — unbounded silent data loss
- `getPrimaryKeyField`/`getPrimaryKeyValue` take field index 0 only, so composite PKs are truncated
- `SecurityContextHolder` is set per event but never cleared on pooled handler threads
- `valueNode.get("source").get("scn")` will NPE into the silent-drop path if absent


Thanks Momcilo, very useful. We checked your 34 points against our risk register:

11 are already covered (for example pipeline poisoning, runner compromise, credential theft, privileged containers, container escape and lateral movement).
15 are partly covered. We will add the missing detail, such as pull-request abuse, cache poisoning, Kubernetes secrets and unauthorized deployments.
4 are new risks we will add:
mixing dev/test and release environments
over-privileged identities
kubectl exec and podman exec abuse
log tampering and evidence destruction
We will add your critical attack path to the diagram, with controls that break it at several points.
We will add T1677 to the pipeline risks.
Scope: the pipeline ships OCI artifacts only (container images and release deployment config). Firmware and OT process configuration are out of scope.

Questions:

Who owns the OT-side threat model (items 16, 18, 20, 21, and the historian feeds in item 26)?
Can you share MITRE IDs for items 6, 7, 10 and 16–34? Our proposed mapping is ready for you to confirm.

Version 3 will follow.

---------------------

Thanks Momcilo, clear and agreed:

Item 7: stays with Trusted Registries. We reference their risk “Backdoored image pushed to OT production registry”.
Items 16, 18, 20: out of scope (runtime security).
Item 21: agreed. NetworkPolicy ships in our signed bundle, so it is in scope as a new risk. Firewalls, routes and DNS stay with the network team.
Item 26: covered as CI/CD observability only (run history, logs, status checks, dashboards, deployment status). Historian and OT assets are excluded.
Ownership: agreed. We model only what we implement and focus on how the pipeline’s own functions can be used against itself.
Critical attack path: mapped hop by hop. The strongest break point is site-side enforcement (admission control and Podman agent policy), which the pipeline cannot change.
Open: please confirm that items 11 (exec commands) and 15 (discovery) are runtime security.

The updated threat model and risk assessment (v3) drafted and uploaded.

----------------------

I rebuilt the technical requirements from 30 to 24, aligned to the v4 threat assessment. Duplicates are merged, scope is fixed to images plus deployment bundles, and the missing security controls are added. Each requirement links to its business requirement and to the risks it addresses.

## Rebuilt technical requirements

**CI and release preparation (IT)**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 001 | CI, build, test, scan, signing and release preparation run only in IT, on dedicated ephemeral release runners, with no OT credentials or OT network access. | 001, 002 | R-008, R-034 |
| CICD TR 002 | Pipelines are built only from central, versioned templates. Changes need code-owner review. Untrusted pull-request builds get no secrets. | 002, 004 | R-002, R-007 |
| CICD TR 003 | Pipeline identities use short-lived tokens and dynamic secrets, with least privilege per pipeline and environment. | 002 | R-009, R-035 |
| CICD TR 004 | Images and deployment bundles (Kubernetes manifests or charts, Quadlet units, NetworkPolicy) are OCI artifacts referenced by immutable digest. No mutable tags. | 001, 002 | R-027, R-033 |
| CICD TR 005 | Release gates block on vulnerability scanning (images) and policy checks (bundles: no privileged settings, default-deny network). | 002 | R-010, R-025, R-036 |
| CICD TR 006 | After all gates, CI signs every image and bundle digest with a non-exportable release key. It attaches signed provenance (source commit, pipeline run), SBOM, scan result and site scope. | 002 | R-011, R-012 |
| CICD TR 007 | Each release produces a signed, immutable release record (version, change ID, image and per-site bundle digests). It is promoted only after two-person approval tied to the change ID. CI never deploys to OT. | 001, 002 | R-013 |

**Handoff to OT (Trusted Registries)**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 008 | Images and bundles cross IT/OT only as signed, scanned, site-scoped OCI artifacts through Trusted Registries, compliant with OT Security and network segmentation. OT runtimes pull only from their site's Trusted Registry. | 002, 003 | R-013, R-024 |
| CICD TR 009 | Site binding is decided by the signed site-scope attestation; registry labels and paths are routing only. Images are shared across sites, and bundles are per site. | 002, 003 | R-021, R-039 |

**Approval and deployment in OT (both runtimes)**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 010 | Deployment is pull-based and initiated by OT. No IT pipeline pushes into OT runtimes. | 001, 002 | – |
| CICD TR 011 | Each release needs approval from one OT approver per site. The approval is a signed attestation (site, release, bundle digest, change ID, approver, validity) made with a per-site key, and it publishes an immutable, forward-only version to the site's approved repository. | 002 | R-021, R-033 |
| CICD TR 012 | The Site Approval Service uses the OT authentication capability with MFA step-up and a non-exportable site key. It shows only verified evidence and the diff to the running release, and blocks releases not scoped to the site. | 002, 003 | R-038, R-039 |
| CICD TR 013 | Deploy controllers apply only approved bundles for their own site, after verifying the site approval signature and a version higher than the running one. They fail closed. | 002 | R-015, R-022, R-033 |
| CICD TR 014 | The site enforces at run time, independently of the pipeline: image signature and provenance, a revoked-digest deny-list, no privileged or host-access workloads, and default-deny networking with an egress allow-list. This policy is delivered by configuration management, not by the registry. | 002 | R-016, R-024, R-025, R-028, R-031, R-036 |
| CICD TR 015 | Deployment identities are least privilege, scoped to site, namespace or host, and operation. No exec, no secret read, no cluster-admin. | 002 | R-035 |
| CICD TR 016 | The current and previous approved releases are cached in OT. Sites operate, halt and roll back without IT; a rollback is re-approval of an earlier bundle as a new version. | 002 | R-029, R-040 |
| CICD TR 017 | Released images are re-scanned continuously. Affected digests are revoked through the deny-list and replaced by a fixed release. | 002 | R-017, R-028 |

**Runtime profiles**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 018 | Kubernetes: one GitOps controller per site using an OCI source, plus admission control (signature, provenance, Pod Security restricted, default-deny required) that fails closed. | 001, 002 | R-015, R-031 |
| CICD TR 019 | Podman: Quadlet units from the bundle are applied by a signed, tested host agent. It verifies approval, version order and the Quadlet policy. Containers run rootless with SELinux enforcing, `policy.json` default-reject, no `podman.socket` and no AutoUpdate. | 001, 002 | R-022 to R-027 |

**Traceability and observability**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 020 | Every running workload is traceable to its release, image digest, source commit, approval and deployment event. Releases can be looked up by image digest for CVE response. | 002, 004 | R-017, R-019 |
| CICD TR 021 | In-scope components emit tamper-evident audit events, retained locally until export to the SIEM (external). CI run history and status checks can't be altered by pipeline admins. Deployment status and health are visible in OT and checked against approved digests. | 002 | R-019, R-037 |

**Reusable reference pattern**

| ID | Technical requirement | BR | Risks |
|---|---|---|---|
| CICD TR 022 | Reusable templates for CI, the release record, bundle structure (desired state) and deployment on both runtimes. | 004 | – |
| CICD TR 023 | Documented reference pattern covering architecture, security, network, operations, rollback and recovery, onboarding, ownership and versioning, for Kubernetes and Podman with shared controls. | 002, 004 | – |
| CICD TR 024 | Validated with Converto at one or more reference sites, with a baseline showing reduced onboarding effort. | 001, 004 | – |

## What changed and why

| Old TR | New TR | Change |
|---|---|---|
| 001, 006 | 001, 007 | Adds dedicated release runners and "no OT network access" |
| 002, 004 | 004, 006 | Covers bundles as well as images; signing key and provenance made specific |
| 003 | 005 | Adds the bundle policy scan |
| 005 | 007 | Release record is signed and tied to the two-person promotion gate |
| 007, 010, 011 | 008 | One transfer mechanism (Trusted Registries) for images and config; replaces the vague "FOTIS-approved mechanism" |
| 008, 020 | 008 | Duplicate merged |
| 009 | 013, 014, 018, 019 | Split into apply-time and run-time checks per runtime |
| 012 | 010 | Unchanged |
| 013, 021 | 011, 012 | Duplicate merged; approval made concrete (signed, per site, forward-only); adds the Site Approval Service |
| 014 | 009, 011, 013 | Site binding is now signed, not just declared |
| 015, 016, 023 | 016 | Duplicates merged; adds the local cache and the rollback rule |
| 017 | 015 | Adds no exec, no secret read, no cluster-admin |
| 018 | 020 | Adds reverse lookup by image digest |
| 019 | 019 | Podman pattern made concrete |
| 022 | 021 | Merged into observability |
| 024–030 | 022–024 | Seven project-delivery items condensed to three |
| – | 002, 003, 009, 014, 017, 021 | **New**: pipeline protection, secrets, site-scope attestation, independent run-time enforcement, revocation, tamper-evident audit |

## Points to align in the BR page
- **Authentication-in-OT is out of scope in the BR page,** but TR 012 depends on it. List it as an external dependency of the Site Approval Service.
- **The Trusted OT registry platform is out of scope,** which matches our design. List the SIEM next to it as an external dependency.
- **Principle 7 lists "Git" for traceability.** In this design, the source commit and the signed attestations carry that traceability, so update the wording to match.

-------------------------

I'll build both deliverables in your FOTIS format: a draw.io threat model and a risk assessment workbook. First I'll reuse the generators from the CI/CD work.

I've built the Message Federation IT-OT threat model (draw.io) and risk assessment (spreadsheet) in the same FOTIS format as the CI/CD ones. The workbook recalculates with 0 formula errors, and every attack vector on the diagram matches a risk in the sheet. I haven't opened the diagram in draw.io, only checked its layout with a rough plot.

**What the draft missed**
1. **The federation link is a direct path from IT into OT data.** Any IT service, broker account or insider that can publish on a federated exchange can change OT replica data. That makes R-001 the only Critical risk (score 20). The draft covers the link only with generic TLS (C06). The strongest fix sits in OT: consumers verify that the declared master sent and signed each event, and check its schema and version, before applying it. That holds even if IT is compromised.
2. **Connection direction.** RabbitMQ federation is set up on the receiving broker, which connects out to the sending one. For OT→IT data, the default would therefore be IT connecting into OT. The model requires every cross-boundary connection to start from OT: federation for IT→OT, and a shovel running in OT for OT→IT. Only port 5671 (TLS) through the network team's firewall conduit (R-002).
3. **Backend Control Service.** It has broad broker rights and a REST API with no UI. It could add a shovel that sends data to an external broker or remap masters (R-005). I recommend declaring the federation setup as code through GitOps.
4. **Availability works against the vision.** An IT outage or a backlog can trigger broker disk or memory alarms, which block all publishers in OT (R-010). The replication gaps on the MF 040201 page also matter here: lost, duplicate and out-of-order events need idempotent, versioned consumers plus resync from the master (R-011).
5. **Authentication still depends on IT.** If the OAuth provider stays in IT, an IT outage stops OT logins and API calls (R-008, draft R006).

**Threat model diagram**
- **Layout:** it keeps the draft's structure: IT zone, OT DMZ/OTPM zone, OT equipment zone, legend and the four tables. It adds a firewall conduit, an "OT-initiated, mTLS" federation link, the Backend Control Service on both sides, and the platform, Trusted Registry, jump station and OAuth provider as out-of-scope components.
- **Attack vectors and tables:** 19 attack vectors (AV01–AV19), each mapped to risk IDs. The draft's data assets, controls and threat agents are kept and extended to 12 data assets, 20 controls and 6 threat agents.
- **Extra tables:** a critical attack path table and the draft's two open questions, each with an answer.

**Risk assessment**
- **Totals:** 22 risks: 1 Critical, 14 High, 7 Medium. A Zone column marks each as IT → OT, Boundary, OT or Both.
- **Same tabs as the CI/CD workbook:** Risk Summary, Risk Assessment, Mitigation Roadmap (4 phases covering every risk), Scoring Model (impact scale reworded for production impact) and Source Notes. Source Notes include the mapping from the draft's R004–R007 to the new IDs.
- **Two new tabs:**
  - Critical Attack Path: IT compromise → forged events → OT data, with the break points.
  - Open Decisions: see below.

**Decisions to take with FOTIS** (on the Open Decisions tab)
- **Cluster placement:** the vision says OTPM, but the draft threat model places the cluster in the OT DMZ layer. Only the OTPM option needs a DMZ relay broker.
- **Event authenticity:** end-to-end signing by the master service, or relying on the broker's user-id check only.
- **Federation setup:** keep configuring through the Backend Control Service REST API, or declare it as code through GitOps.
- **Authentication in OT:** where the OAuth provider runs, and how OT validates tokens when IT is down.
- **Certificates:** which CA issues the federation and ingress certificates, and how IT and OT trust each other.
- **RabbitMQ image source:** Bitnami changed its catalogue and licence terms in 2025, so confirm the image source.

Draft risks R001–R003 weren't visible in the screenshots, so they aren't mapped. The scores, owners and MITRE IDs are proposals for review.

--------------------

I built v5 of the CI/CD risk assessment from v4, with Momcilo's 18 Nov reply applied. It recalculates with 0 formula errors.

**Changes**
- **R-037 (item 26):** now covers only manipulation of telemetry, status and alarms: spoofed status checks, alert rules and dashboards, and false "synced" or "approved" status.
- **New R-041 (item 15):** container and cluster discovery through the CI and deploy tooling. It's Medium (9) and uses the existing attack vectors AV30 and AV31.
- **New R-042 (item 34):** deletion or alteration of forensic evidence in the CI/CD path, as a separate High risk (12).
- **New "Telemetry & Evidence Plan" tab:** this answers Momcilo's roadmap question directly. It has 12 actions for items 26 and 34, each with a phase, owner and a "done when" check. Phase 2 actions are meant to be in place before first go-live at a site. Registry metadata stays with Trusted Registries.
- **Mitigation Roadmap:** the telemetry and evidence controls now start in Phase 2 and continue in Phases 3 and 4, instead of one generic Phase 4 line. R-041 is in Phase 2.
- **FOTIS Feedback Trace:**
  - Item 11 → out of scope (confirmed by FOTIS).
  - Item 15 → R-041.
  - Item 26 → R-037.
  - Item 34 → R-042.
  - Item 28 now also references R-042.
  - No items are left "to confirm".
- **Source Notes:** new "FOTIS feedback (v5)" entry; the v3 note now records his answers on items 11 and 15.

The register now has 40 risks: 1 Critical, 28 High and 11 Medium. Two items are proposals for him to confirm: the 15-minute detection target for silenced log sources, and retention "aligned with Tetra Pak policy", since I don't know the actual policy period.

The diagram needs no change, because the new risks reuse existing attack vectors. The draft reply from my previous message still fits; just point it to v5 and the new plan tab.

-------------------------

Thanks Momcilo, agreed:

- **Item 11:** moved to runtime security.
- **Item 15:** stays in the CI/CD model. Added as new risk R-041 (discovery through CI/CD and deploy tools).
- **Items 26 and 34:** now two separate risks, R-037 (fake telemetry and alarms) and R-042 (deleted evidence). Each has a step-by-step plan in the new "Telemetry & Evidence Plan" tab, with owner and timing. The basics are in place before go-live.
- **SIEM:** stays external. We send and protect the events; the SOC owns the SIEM.

The updated risk assessment (v5) is shared here: CICD_for_OT_Risk_Assessment_v5.xlsx


———————————————————————————-

I kept the Converto point as "may need" because we haven't confirmed which Converto hosts on the OT side count as new installations. Add your name to the sign-off before sending.
