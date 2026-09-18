package com.tetrapak.converto.legacy.debezium;

import com.tetrapak.converto.common.observability.health.LatchingHealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health for the embedded Debezium engine.
 *
 * <p>This indicator now belongs to the READINESS group, not liveness. A DOWN here
 * means "this pod is not consuming CDC", which should take it out of service and
 * raise an alert — it should never kill the pod, because the most important
 * failure mode (offset SCN no longer in redo) is permanent and restarting cannot
 * fix it.
 */
@Component("debezium")
public class DebeziumHealthIndicator extends LatchingHealthIndicator {

    public void recordStarting() {
        recordFailure("Debezium connector task has not started yet");
    }

    public void recordStopped(String reason) {
        recordFailure(reason);
    }

    public void recordFailure(String reason, Throwable error) {
        recordFailure(reason, error, null);
    }

    /**
     * @param type classification from {@link DebeziumFailureClassifier}; null when
     *             the caller has not classified (kept so existing call sites
     *             compile unchanged)
     */
    public void recordFailure(String reason, Throwable error, DebeziumFailureType type) {
        Map<String, String> details = new LinkedHashMap<>();
        if (type != null) {
            details.put("failureType", type.name());
            details.put("recoverable", Boolean.toString(type.isRecoverable()));
            if (!type.isRecoverable()) {
                details.put("action", "Manual recovery required — see runbook RUN-CDC-001");
            }
        }
        if (error != null) {
            details.put("errorType", error.getClass().getName());
            details.put("errorMessage", error.getMessage() != null ? error.getMessage() : "");
        }
        if (details.isEmpty()) {
            recordFailure(reason);
            return;
        }
        recordFailure(reason, details);
    }
}
