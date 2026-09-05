package com.belowdeck.collector;

/**
 * Raised by any collector when it can't produce usable data, along with a
 * message written for the human running the tool (not a stack-trace-only
 * diagnostic) - e.g. "NMT is not enabled on the target JVM; relaunch it with
 * -XX:NativeMemoryTracking=detail."
 *
 * <p>Deliberately unchecked: collector failures are almost always
 * "environment isn't set up right" rather than something calling code can
 * recover from programmatically. {@code Belowdeck} catches this type
 * specifically to print a clean, actionable error instead of a raw stack trace.
 */

public class CollectorException extends RuntimeException {

    public CollectorException(String message) {
        super(message);
    }

    public CollectorException(String message, Throwable cause) {
        super(message, cause);
    }
}