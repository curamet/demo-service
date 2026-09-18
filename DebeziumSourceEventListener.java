package com.tetrapak.converto.legacy.debezium;

import io.debezium.config.Configuration;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import io.debezium.engine.format.KeyValueHeaderChangeEventFormat;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the embedded Debezium engine and supervises it.
 *
 * <p>Previously the engine was started once via {@code executor.execute(engine)}
 * and, when it died, the completion callback logged and returned. Nothing
 * restarted it. The JVM stayed up, HTTP stayed up, CDC was dead — and the only
 * actor that reacted was the liveness probe, which killed the pod. That produced
 * an invisible restart loop for transient errors and an infinite crashloop for
 * permanent ones.
 *
 * <p>This class replaces that with an explicit supervisor:
 * <ul>
 *   <li>recoverable failure → rebuild the engine, exponential backoff with jitter</li>
 *   <li>permanent failure → stop, hold health DOWN, expose a fatal gauge, page</li>
 * </ul>
 *
 * <p>A DebeziumEngine instance is single-use, so each attempt builds a fresh one.
 */
@Service
public class DebeziumSourceEventListener {

    private static final Logger logger = LoggerFactory.getLogger(DebeziumSourceEventListener.class);

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    private final Configuration oracleConnector;
    private final EventHandler eventHandler;
    private final DebeziumHealthIndicator debeziumHealthIndicator;
    private final DebeziumFailureClassifier classifier;
    private final MeterRegistry meterRegistry;

    private final ExecutorService supervisor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "debezium-supervisor");
        thread.setDaemon(true);
        return thread;
    });

    /** 1 while the connector task is running, 0 otherwise. */
    private final AtomicInteger connectorUp = new AtomicInteger(0);
    /** 1 once a permanent failure has stopped the supervisor. Never clears without a restart. */
    private final AtomicInteger connectorFatal = new AtomicInteger(0);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private final AtomicReference<Throwable> lastError = new AtomicReference<>();
    private final AtomicReference<String> lastMessage = new AtomicReference<>();

    private volatile boolean shuttingDown;
    private volatile DebeziumEngine<ChangeEvent<String, String>> engine;

    public DebeziumSourceEventListener(Configuration oracleConnector,
                                       EventHandler eventHandler,
                                       DebeziumHealthIndicator debeziumHealthIndicator,
                                       DebeziumFailureClassifier classifier,
                                       MeterRegistry meterRegistry) {
        this.oracleConnector = oracleConnector;
        this.eventHandler = eventHandler;
        this.debeziumHealthIndicator = debeziumHealthIndicator;
        this.classifier = classifier;
        this.meterRegistry = meterRegistry;

        this.debeziumHealthIndicator.recordStarting();

        Gauge.builder("converto_debezium_connector_up", connectorUp, AtomicInteger::get)
                .description("1 when the Debezium connector task is running, 0 otherwise")
                .register(meterRegistry);

        Gauge.builder("converto_debezium_connector_fatal", connectorFatal, AtomicInteger::get)
                .description("1 when Debezium stopped with a permanent failure and will not be retried")
                .register(meterRegistry);
    }

    @PostConstruct
    void start() {
        supervisor.execute(this::superviseLoop);
    }

    private void superviseLoop() {
        while (!shuttingDown) {
            lastError.set(null);
            lastMessage.set(null);

            try (DebeziumEngine<ChangeEvent<String, String>> current = buildEngine()) {
                this.engine = current;
                current.run(); // blocks until the engine stops
            } catch (Exception e) {
                logger.error("Debezium engine threw while running", e);
                lastError.compareAndSet(null, e);
            }

            if (shuttingDown) {
                return;
            }

            connectorUp.set(0);

            Throwable error = lastError.get();
            String message = lastMessage.get() != null ? lastMessage.get() : "Debezium engine stopped";
            DebeziumFailureType type = classifier.classify(error);

            meterRegistry.counter("converto_debezium_engine_stops",
                    "failure_type", type.name()).increment();

            if (!type.isRecoverable()) {
                connectorFatal.set(1);
                debeziumHealthIndicator.recordFailure(message, error, type);
                logger.error("Debezium stopped with a PERMANENT failure ({}). Not restarting — "
                                + "manual recovery required. Reason: {}", type, message, error);
                return; // supervisor exits; pod stays NotReady until an operator acts
            }

            int attempt = consecutiveFailures.incrementAndGet();
            Duration backoff = backoffFor(attempt);
            debeziumHealthIndicator.recordFailure(message, error, type);
            logger.warn("Debezium stopped with a recoverable failure ({}), attempt {}. "
                            + "Restarting in {}s. Reason: {}",
                    type, attempt, backoff.toSeconds(), message, error);

            if (!sleep(backoff)) {
                return;
            }
        }
    }

    private DebeziumEngine<ChangeEvent<String, String>> buildEngine() {
        return DebeziumEngine.create(
                        KeyValueHeaderChangeEventFormat.of(Json.class, Json.class, Json.class),
                        "io.debezium.embedded.async.ConvertingAsyncEngineBuilderFactory")
                .using(oracleConnector.asProperties())
                .using(buildCompletionCallback())
                .using(buildConnectorCallback())
                .notifying(eventHandler::handleChangeEvent)
                .build();
    }

    private DebeziumEngine.CompletionCallback buildCompletionCallback() {
        return (success, message, error) -> {
            lastMessage.set(message);
            lastError.set(error);
            if (success) {
                logger.info("Debezium engine completed: {}", message);
                return;
            }
            logger.error("Debezium engine terminated with an error: {}", message, error);
        };
    }

    private DebeziumEngine.ConnectorCallback buildConnectorCallback() {
        return new DebeziumEngine.ConnectorCallback() {
            @Override
            public void taskStarted() {
                logger.info("Debezium connector task started");
                connectorUp.set(1);
                connectorFatal.set(0);
                consecutiveFailures.set(0); // a healthy start resets the backoff ladder
                debeziumHealthIndicator.recordSuccess();
            }

            @Override
            public void taskStopped() {
                connectorUp.set(0);
                if (shuttingDown) {
                    return;
                }
                logger.warn("Debezium connector task stopped unexpectedly");
            }
        };
    }

    /** Exponential backoff with full jitter, capped at {@link #MAX_BACKOFF}. */
    private Duration backoffFor(int attempt) {
        int shift = Math.min(attempt - 1, 20);
        long ceiling = Math.min(MAX_BACKOFF.toMillis(), INITIAL_BACKOFF.toMillis() << shift);
        long floor = ceiling / 2;
        return Duration.ofMillis(floor + ThreadLocalRandom.current().nextLong(ceiling - floor + 1));
    }

    /** @return false if interrupted, meaning the caller should stop */
    private boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    void stop() throws Exception {
        shuttingDown = true;
        DebeziumEngine<ChangeEvent<String, String>> current = this.engine;
        if (current != null) {
            current.close();
        }
        supervisor.shutdownNow();
        if (!supervisor.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
            logger.warn("Debezium supervisor did not terminate within {}s", SHUTDOWN_GRACE.toSeconds());
        }
    }
}
