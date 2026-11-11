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
