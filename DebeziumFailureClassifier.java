package com.tetrapak.converto.legacy.debezium;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Maps a Throwable from the Debezium engine onto a {@link DebeziumFailureType}.
 *
 * <p>Debezium gives us almost nothing to work with: the SCN failure arrives as a
 * bare {@code io.debezium.DebeziumException} with no code and no subtype, so the
 * only discriminator is message text. That is brittle across Debezium upgrades,
 * which is why {@code DebeziumFailureClassifierTest} pins every pattern against
 * the version in the POM — an upgrade that reworks a message fails CI instead of
 * failing in production at 08:51.
 *
 * <p>Rules are evaluated in order, first match wins, across the whole cause chain.
 */
@Component
public class DebeziumFailureClassifier {

    private record Rule(DebeziumFailureType type, Pattern pattern) { }

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final List<Rule> RULES = List.of(

            // "Online REDO LOG files or archive log files do not contain the offset
            //  scn 13136367402. Please perform a new snapshot."
            new Rule(DebeziumFailureType.OFFSET_NOT_IN_REDO,
                    Pattern.compile("do not contain the offset scn", CI)),
            // ORA-01291: missing logfile — archive log required for mining is gone.
            new Rule(DebeziumFailureType.OFFSET_NOT_IN_REDO,
                    Pattern.compile("ORA-01291")),

            new Rule(DebeziumFailureType.SCHEMA_HISTORY_CORRUPT,
                    Pattern.compile("(schema|database) history .*(missing|corrupt|not exist|inconsistent)", CI)),

            // ORA-01017 invalid credentials, ORA-28000 locked, ORA-28001 expired.
            new Rule(DebeziumFailureType.AUTHENTICATION_FAILED,
                    Pattern.compile("ORA-01017|ORA-28000|ORA-28001")),

            // ORA-00942 table/view does not exist, ORA-01031 insufficient privileges.
            new Rule(DebeziumFailureType.CONFIGURATION_INVALID,
                    Pattern.compile("ORA-00942|ORA-01031")),
            new Rule(DebeziumFailureType.CONFIGURATION_INVALID,
                    Pattern.compile("supplemental logging .*(not|must be) ", CI)),

            // ORA-01555 must be matched BEFORE the generic rules: it is the snapshot
            // undo problem, which is retryable, and must not be conflated with the
            // permanent OFFSET_NOT_IN_REDO case.
            new Rule(DebeziumFailureType.UNDO_EXHAUSTED,
                    Pattern.compile("ORA-01555")),

            new Rule(DebeziumFailureType.CONNECTION_LOST,
                    Pattern.compile("ORA-03113|ORA-03114|ORA-12541|ORA-12170|ORA-12571|ORA-17002|ORA-17410")),
            new Rule(DebeziumFailureType.CONNECTION_LOST,
                    Pattern.compile("connection reset|socket read timed out|broken pipe|closed connection", CI))
    );

    /**
     * @param error the Throwable reported by the engine, may be null
     * @return the matched type, {@link DebeziumFailureType#UNEXPECTED_STOP} if the
     *         engine stopped with no error, or {@link DebeziumFailureType#UNKNOWN}
     *         if nothing matched
     */
    public DebeziumFailureType classify(Throwable error) {
        if (error == null) {
            return DebeziumFailureType.UNEXPECTED_STOP;
        }
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(message).find()) {
                    return rule.type();
                }
            }
        }
        return DebeziumFailureType.UNKNOWN;
    }
}
