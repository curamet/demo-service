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
