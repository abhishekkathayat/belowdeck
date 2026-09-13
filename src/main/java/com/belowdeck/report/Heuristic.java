package com.belowdeck.report;

/**
 * One actionable observation produced by {@link HeuristicEngine} - the "so what"
 * layer described in KT doc S12.5: turning raw measurements into a recommendation
 * an operator can actually act on.
 *
 * @param severity  how urgent this observation is
 * @param title     one-line summary, shown as the headline in the report
 * @param detail    the fuller explanation, including the concrete numbers that
 *                  triggered this heuristic and (where applicable) a suggested fix
 */
public record Heuristic(Severity severity, String title, String detail) {

    public enum Severity {
        /** Worth knowing, not necessarily a problem (e.g. "heap is mostly idle, committed pages"). */
        INFO,
        /** A real, actionable concern, but not necessarily the direct cause of an incident. */
        WARNING,
        /** Strong candidate explanation for an OOMKill / memory incident. */
        CRITICAL
    }
}