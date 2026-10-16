package com.voxticket.agent;

import com.voxticket.conversation.TurnAbortedException;
import com.voxticket.conversation.TurnContext;
import com.voxticket.conversation.TurnHandle;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P0.1: enforces the per-tool-call time budget.
 *
 * <p>Every {@code @Tool} method body runs through {@link #call}: the work
 * executes on a bounded daemon pool and the caller waits at most
 * {@code timeout}. On expiry the worker thread is interrupted
 * ({@code Future.cancel(true)}) and a {@link ToolTimeoutException} is thrown,
 * which the configured {@link SanitizedToolExecutionExceptionProcessor}
 * converts into the fixed {@code TOOL_EXECUTION_FAILED} JSON for the model -
 * the turn continues instead of hanging on a stuck tool while holding the
 * session lock.
 *
 * <p>The pool is static and daemon-only: tool calls are short-lived and few
 * (at most 10 per turn by configuration), so a shared pool avoids one
 * executor per tool instance. Daemon threads never block JVM shutdown;
 * production sizing is deliberately boring (fixed, named threads).
 */
public final class ToolCallTimeout {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTimeout.class);

    private static final int POOL_SIZE = 16;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "voxticket-tool-timeout-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private ToolCallTimeout() {
    }

    /**
     * Runs {@code work} with a hard time budget.
     *
     * @param toolName human-readable tool name, for logs and the exception
     * @param timeout maximum time to wait for the work to complete
     * @param work the tool implementation
     * @return the work's result
     * @throws ToolTimeoutException if the budget expires (the worker thread
     *         is interrupted first)
     * @throws RuntimeException wrapping any checked failure from the work,
     *         preserving unchecked throwables as-is
     */
    public static <T> T call(String toolName, Duration timeout, Callable<T> work) {
        return call(toolName, timeout, work, TurnContext.current());
    }

    /**
     * P1.1/P1.2: ownership-aware variant.
     *
     * <p>Before submitting, the turn's ownership is checked: a preempted
     * turn's tool is <em>deferred</em> (never started) via
     * {@link TurnAbortedException}, which the runtime handles as an abort -
     * it is never routed through the sanitized {@code TOOL_EXECUTION_FAILED}
     * path, so a dead turn cannot keep talking to the model.
     *
     * <p>The submitted future is registered on the handle, so preemption
     * cancels in-flight tool work; the resulting
     * {@link CancellationException} is translated to
     * {@link TurnAbortedException} for the same reason.
     *
     * @param handle the turn's ownership handle, or null for the pre-P1
     *        behavior (no ownership check, no work registration)
     */
    public static <T> T call(String toolName, Duration timeout, Callable<T> work, TurnHandle handle) {
        if (handle != null) {
            handle.checkOwned();
        }
        Future<T> future = EXECUTOR.submit(work);
        if (handle != null) {
            handle.registerWork(future);
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (CancellationException e) {
            // P1.1: preemption cancelled our future - this is an abort, not
            // a tool error. Never the sanitized path.
            log.info("event=tool_call_cancelled tool={}", toolName);
            throw new TurnAbortedException(
                    new IllegalStateException("tool call cancelled: turn superseded: " + toolName));
        } catch (TimeoutException e) {
            // Interrupt first: a tool blocked in interruptible I/O (HTTP,
            // socket reads) unwinds promptly; JDBC blocks are bounded by the
            // statement timeout instead.
            future.cancel(true);
            log.warn("event=tool_call_timeout tool={} timeoutMs={}", toolName, timeout.toMillis());
            throw new ToolTimeoutException(toolName, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RuntimeException("tool call interrupted: " + toolName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException("tool call failed: " + toolName, cause);
        } finally {
            if (handle != null) {
                handle.unregisterWork(future);
            }
        }
    }
}
