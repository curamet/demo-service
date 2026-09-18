package com.tetrapak.converto.legacy.debezium;

/**
 * Classification of why the Debezium engine stopped.
 *
 * <p>{@code recoverable} drives supervisor behaviour: recoverable failures are
 * retried with exponential backoff, permanent ones stop the loop and hold the
 * health indicator DOWN until a human intervenes.
 *
 * <p>Deliberate bias: anything unmatched is {@link #UNKNOWN} and therefore
 * recoverable, so an unrecognised error behaves the way the service behaves
 * today rather than wedging permanently on a false positive.
 */
public enum DebeziumFailureType {

    /**
     * The committed offset SCN is no longer present in online redo or archive logs.
     * Recovery requires a deliberate new snapshot — retrying can never succeed.
     */
    OFFSET_NOT_IN_REDO(false),

    /** Persisted schema history is missing or unusable. Requires operator action. */
    SCHEMA_HISTORY_CORRUPT(false),

    /** Missing table, missing privilege, or missing supplemental logging. */
    CONFIGURATION_INVALID(false),

    /** Oracle rejected the credentials, or the account is locked/expired. */
    AUTHENTICATION_FAILED(false),

    /** ORA-01555: undo overwritten while a long-running read was in flight. */
    UNDO_EXHAUSTED(true),

    /** Network or Oracle session loss. */
    CONNECTION_LOST(true),

    /** Engine stopped cleanly without an error, but we did not ask it to. */
    UNEXPECTED_STOP(true),

    /** Nothing matched. Retried, because unmatched is not the same as permanent. */
    UNKNOWN(true);

    private final boolean recoverable;

    DebeziumFailureType(boolean recoverable) {
        this.recoverable = recoverable;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
