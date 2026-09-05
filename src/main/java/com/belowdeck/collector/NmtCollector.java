package com.belowdeck.collector;

import com.belowdeck.model.NmtCategoryTotal;
import com.belowdeck.model.NmtRegion;
import com.belowdeck.model.NmtSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects a {@link NmtSnapshot} by shelling out to {@code jcmd <pid> VM.native_memory detail}.
 *
 * <h2>Prerequisite: NMT must be enabled at JVM launch</h2>
 * Native Memory Tracking cannot be turned on for an already-running process -
 * it has to be requested when the JVM starts, via:
 * <pre>-XX:NativeMemoryTracking=detail</pre>
 * ({@code summary} is not enough for this tool: only {@code detail} prints the
 * "Virtual memory map" section with actual addresses, which is the join key against
 * smaps - KT doc S11.1). If the target wasn't launched this way, {@code jcmd} will
 * print "Native memory tracking is not enabled" and this collector fails fast with a
 * message telling the operator exactly what to do.
 *
 * <h2>Why parsing NMT text output is inherently best-effort</h2>
 * Unlike {@code /proc/<pid>/smaps} (documented, stable kernel ABI - see {@code man 5 proc}),
 * NMT's {@code detail} text format is NOT a committed-forever API. It has changed cosmetically
 * across JDK releases and could change again. The regexes below where written from a well-known,
 * long-standing HotSpot output shape:
 *
 * <pre>
 *     Native Memory Tracking:
 *
 *     Total: reserved=9067731KB, committed=1067731KB
 *     -    Java Heap   (reserved=8388608KB, committed=1048576KB)
 *                      (mmap: reserved=8388608KB, committed=1048576KB)
 *     -    Thread      (reserved=21501KB, committed=21501KB)
 *                      (thread #21)
 *                      (stack: reserved=21462KB, committed=21462KB)
 *     ...
 *     Virtual memory map:
 *
 *     [0x00007f2c00000000 - 0x00007f2c40000000] reserved 1073741824 for Java Heap
 *     [0x00007f2c00000000 - 0x00007f2c30000000] committed 805306368
 * </pre>
 *
 * <p>If you run this against your JDK and get zero regions back, the fix is:
 * run {@code jcmd <pid> VM.native_memory detail} by hand, compare its actual
 * text against {@link #CATEGORY_TOTAL_PATTERN} / {@link #REGION_PATTERN} /
 * {@link #THREAD_COUNT_PATTERN} below, and adjust the regex to match. This is
 * intentionally the single place in the codebase where output-format drift would
 * need a fix.
 */

public final class NmtCollector {

    /** Matches a category summary line, e.g. "-    Java Heap (reserved=8388608KB, committed=1048576KB)". */
    private static final Pattern CATEGORY_TOTAL_PATTERN =
            Pattern.compile("^-\\s*(.+?)\\s*\\(reserved=(\\d+)KB,\\s*committed=(\\d+)KB\\)\\s*$");

    /** Matches an address-range reservation line, e.g. "[0x00007f2c00000000 - 0x00007f2c40000000] reserved 1073741824 for Java Heap". */
    private static final Pattern REGION_PATTERN =
            Pattern.compile("^\\[(0x[0-9a-fA-F]+)\\s*-\\s*(0x[0-9a-fA-F]+)\\]\\s+reserved\\s+(\\d+)\\s+for\\s+(.+?)\\s*$");

    /** Matches the thread-count sub-line under the "Thread" category, e.g. "(thread #21)". */
    private static final Pattern THREAD_COUNT_PATTERN = Pattern.compile("^\\s*\\(thread #(\\d+)\\)\\s*$");

    private static final String NMT_NOT_ENABLED_MARKER = "Native memory tracking is not enabled";
    private static final String NO_SUCH_PROCESS_MARKER = "No such process";

    private final String jcmdPath;
    private final Duration timeout;

    /** Uses "jcmd" resolved from Path, with a 15 second timeout - suitable for most interactive use. */
    public NmtCollector() {
        this("jcmd", Duration.ofSeconds(15));
    }

    /**
     * @param jcmdPath  path to (or bare name of) the jcmd binary to invoke. Prefer the jcmd that ships
     *                  alongside the target JVM's own JDK install - attaching with a jcmd from a mismatched
     *                  major JDK version can fail or behave inconsistently.
     * @param timeout   how long to wait for jcmd before giving up
     */
    public NmtCollector(String jcmdPath, Duration timeout) {
        this.jcmdPath = jcmdPath;
        this.timeout = timeout;
    }

    /**
     * Attaches to the target process and parses its NMT detail output.
     *
     * @param pid   target JVM's process id
     * @throws CollectorException   if jcmd can't run, the process doesn't exist,
     *                              of NMT isn't enabled on the target
     */
    public NmtSnapshot collect(long pid) {
        String output;
        try {
            output = ProcessRunner.run(List.of(jcmdPath, Long.toString(pid), "VM.native_memory", "detail"), timeout);
        } catch (IOException e) {
            throw new CollectorException(
                    "Failed to run '" + jcmdPath + " " + pid + " VM.native_memory detail'. " +
                    "Is jcmd on PATH and does it match the target JVM's JDK install?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CollectorException("Interrupted while waiting for jcmd", e);
        }

        if (output.contains(NO_SUCH_PROCESS_MARKER)) {
            throw new CollectorException("jcmd reports no such process:pid " + pid +
                    ". Double-check te pid (see `jps -l`).");
        }
        if (output.contains(NMT_NOT_ENABLED_MARKER)) {
            throw new CollectorException(
                    "Native Memory Tracking is not enabled on pid " + pid + ". " +
                    "NMT can only be turned on at JVM launch - relaunch the target with " +
                    "-XX:NativeMemoryTracking=detail and try again.");
        }
        return parse(output);
    }

    /** Package-visible for unit testing without spawning a process. */
    static NmtSnapshot parse(String nmtOutput) {
        List<NmtRegion> regions = new ArrayList<>();
        Map<String, NmtCategoryTotal> totals = new LinkedHashMap<>();
        int threadCount = -1;

        // Track which category summary block we're currently inside, so the
        // "(thread #N)" sub-line - which carries no category name of its own -
        // can be attributed correctly. Reset whenever a new "- Category (...)"
        // header line is seen.
        String currentSummaryCategory = null;

        for (String line : nmtOutput.split("\\R")) {
            Matcher totalMatcher = CATEGORY_TOTAL_PATTERN.matcher(line);
            if (totalMatcher.matches()) {
                String category = totalMatcher.group(1).trim();
                long reservedBytes = Long.parseLong(totalMatcher.group(2)) * 1024L;
                long committedBytes = Long.parseLong(totalMatcher.group(3)) * 1024L;
                totals.put(category, new NmtCategoryTotal(category, reservedBytes, committedBytes));
                currentSummaryCategory = category;
                continue;
            }
            Matcher threadMatcher = THREAD_COUNT_PATTERN.matcher(line);
            if (threadMatcher.matches() && "Thread".equalsIgnoreCase(currentSummaryCategory)) {
                threadCount = Integer.parseInt(threadMatcher.group(1));
                continue;
            }
            Matcher regionMatcher = REGION_PATTERN.matcher(line);
            if (regionMatcher.matches()) {
                long base = Long.parseUnsignedLong(regionMatcher.group(1).substring(2), 16);
                long end = Long.parseUnsignedLong(regionMatcher.group(2).substring(2), 16);
                String category = regionMatcher.group(4).trim();
                regions.add(new NmtRegion(base, end, category));
            }
        }
        return new NmtSnapshot(regions, totals, threadCount);
    }
}