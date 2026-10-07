package com.voxticket.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A turn arrived while its session's lock was still held (typical cause: a
 * barge-in turn arriving while the aborted previous turn is still unwinding)
 * and the tryLock budget expired. Unchecked, like
 * {@link TurnAbortedException}.
 *
 * <p>Thrown from {@link SessionStore#withSession} before any session state
 * is touched, so the session is left exactly as-is: either the previous
 * turn released cleanly or it is about to. The voice worker retries on the
 * 429 this maps to, which is how the new turn wins once the abort lands.
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class SessionBusyException extends RuntimeException {

    public SessionBusyException(String sessionId, long waitedMs) {
        super("session busy: sessionId=" + sessionId + " still locked after " + waitedMs + "ms");
    }

    public SessionBusyException(String sessionId, long waitedMs, InterruptedException interrupted) {
        super("session busy: sessionId=" + sessionId + " lock wait interrupted after " + waitedMs + "ms",
                interrupted);
    }
}
