package com.belowdeck.collector;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Tiny helper for shelling out to an external diagnostic command (in practice:
 * {@code jcmd}) and capturing its stdout as a string.
 *
 * <h2>Why we shell out to jcmd instead of using the JDK Attach API in-process</h2>
 * The KT doc (S12.7) suggests using {@code com.sun.tools.attach.VirtualMachine} and
 * calling {@code executeJCmd(...)} directly, avoid a subprocess. That's a legitimate
 * approach, but the method that actually issues a diagnostic command ({@code executeJCmd})
 * lives on the JDK-internal {@code sun.tools.attach.HotSpotVirtualMachine} class, not on
 * public {@code com.tools.attach.VirtualMachine} API. Reaching it requires either a
 * reflection into a non-exported JDK-internal package, or launching *this* tool's own
 * JVM with {@code --add-opens jdk.attach/sun.tools.attach=ALL-UNNAMED} every time - a
 * brittle, JDK-version-sensitive requirement we'd be pushing onto every future run of this
 * tool.
 *
 * <p>Shelling out to the {@code jcmd} binary avoids all of that: it's the same mechanism
 * (Dynamic Attach), it's public, stable, on the PATH of any JDK install, and ot's exactly
 * what the KT doc's own cheat sheet (Part 13) uses.
 * The tradeoff is text parsing instead of structured objects - acceptable here since NMT's
 * own output format isn't a stable, versioned API either way; see {@code NmtCollector}'s
 * parsing notes.
 *
 * <p>This class doesn not attempt to locate {@code jcmd} across arbitrary JDK installs.
 * It assumes {@code jcmd} is resolvable via the {@code PATH} the tool itself was launched with,
 * matching the target JVM's own installation.
 * If that's not true in your environment, pass an explicit path via the {@code jcmdPath}
 * constructor parameter on {@link NmtCollector} / {@link HeapInfoCollector} (both default to plain
 * {@code "jcmd"}).
 */
final class ProcessRunner {

    private ProcessRunner() {}

    /**
     * Runs a command, waits for it to finish (bounded by {@code timeout}), and returns
     * its combined stdout as a string. Non-zero exit coes are NOT treated as errors here
     * - {@code jcmd} itself prints useful diagnostic text (e.g. "Native memory tracking is
     * not enabled") on what the OS still considers exit code 0 in some JDK versions and
     * non-zero in others, so callers are expected to inspect the returned text rather than
     * rely on the exit code alone.
     *
     * @throws IOException          if the process could not be started (e.g. command not found)
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    static String run(List<String> command, Duration timeout) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true); // merge stderr into stdout - jcmd somtimes writes errors to stderr
        Process process = builder.start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", command));
        }
        return output;
    }
}
