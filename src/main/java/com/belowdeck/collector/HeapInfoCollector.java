package com.belowdeck.collector;

import com.belowdeck.model.HeapInfo;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects heap used-vs-committed figures via {@code jcmd <pid> GC.heap_info}.
 *
 * <h2>Why this exists alongside NMT</h2>
 * NMT's "Java Heap" category only reports {@code reserved} and {@code committed}
 * (KT doc S6.3) - virtual space claimed and OS-backed pages.
 * Neither tells you how much of the heap currently holds live objects. Without a
 * "used" figure, the report can't distinguish "high RSS because of a real leak" from
 * "high RSS because the collector hasn't returned idle pages to the OS yet"
 * (KT doc S6.3's "practical takeaway"). {@code GC.heap_info} is the cheapest way to
 * get that "used" figure without attaching a full profiler.
 *
 * <h2>Why parsing this is genuinely best-effort</h2>
 * Unlike NMT, {@code GC.heap_info} doesn't even have one consistent shape across
 * collectors - G1, Parallel, Serial, ZGC, and Shenandoah aech print their own
 * bespoke text. This collector tries a handful of known shapes and falls back to
 * reporting {@link HeapInfo#available()} {@code == false} (with the raw text
 * preserved) rather than guessing wrong. Concretely, it handles:
 * <ul>
 *     <li>G1 (default since Java 9): a single "garbage-first heap total X, used Y" line</li>
 *     <li>ZGC: a "ZHeap used X, capacity Y" line (note: ZGC reports in megabytes, not kilobytes)</li>
 *     <li>Shenandoah: a "NNN total, NNN committed, NNN used" block</li>
 *     <li>Parallel / Serial (generational, no single "whole heap" line): summed across each
 *          "... generation ... total X ... used Y" line, explicitly skipping Metaspace/class-space
 *          lines which use the same word for a complete different (non-heap) region</li>
 * </ul>
 */

public final class HeapInfoCollector {

    private static final Pattern G1_PATTERN =
            Pattern.compile("garbage-first heap\\s+total\\s+(\\d+)K,\\s*used\\s+(\\d+)K");

    private static final Pattern ZGC_PATTERN =
            Pattern.compile("ZHeap\\s+used\\s+(\\d+)M,\\s*capacity\\s+(\\d+)M");

    private static final Pattern SHENANDOAH_PATTERN =
            Pattern.compile("(\\d+)M total, (\\d+)M committed, (\\d+)M used");

    /**
     * Matches one generation line like " PSYoungGen total 305664K, used 152106K [...]" - deliberately
     * excludes "Metaspace"/"class space" lines by requiring the word "generation" or "space" but NOT
     * being anchored to the start, and callers filter those two names out explicitly.
     */
    private static final Pattern GENERATION_PATTERN =
            Pattern.compile("^\\s*(\\S.*?)\\s+total\\s+(\\d+)K,\\s*used\\s+(\\d+)K");

    private final String jcmdPath;
    private final Duration timeout;

    public HeapInfoCollector() {
        this("jcmd", Duration.ofSeconds(15));
    }

    public HeapInfoCollector(String jcmdPath, Duration timeout) {
        this.jcmdPath = jcmdPath;
        this.timeout = timeout;
    }

    public HeapInfo collect(long pid) {
        String output;
        try {
            output = ProcessRunner.run(List.of(jcmdPath, Long.toString(pid), "GC.heap_info"), timeout);
        } catch (IOException e) {
            throw new CollectorException(
                    "Failed to run '" + jcmdPath + " " + pid + " GC.heap_info'. is jcmd on path?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CollectorException("Interrupted while waiting for jcmd", e);
        }
        return parse(output);
    }

    /** Package-visible for unit testing without spawning a process. */
    static HeapInfo parse(String output) {
        Matcher g1 = G1_PATTERN.matcher(output);
        if (g1.find()) {
            long total = Long.parseLong(g1.group(1)) * 1024L;
            long used = Long.parseLong(g1.group(2)) * 1024L;
            return new HeapInfo(total, used, output, true);
        }
        Matcher zgc = ZGC_PATTERN.matcher(output);
        if (zgc.find()) {
            long capacity = Long.parseLong(zgc.group(2)) * 1024L * 1024L;
            long used = Long.parseLong(zgc.group(1)) * 1024L * 1024L;
            return new HeapInfo(capacity, used, output, true);
        }
        Matcher shenandoah = SHENANDOAH_PATTERN.matcher(output);
        if (shenandoah.find()) {
            long committed = Long.parseLong(shenandoah.group(2)) * 1024L * 1024L;
            long used = Long.parseLong(shenandoah.group(3)) * 1024L* 1024L;
            return new HeapInfo(committed, used, output, true);
        }

        // Generational collectors (Parallel, Serial): sum each generation's own total/used,
        // skipping Metaspace and class-space lines which are not part of the Java heap at all.
        long totalSum = 0;
        long usedSum = 0;
        boolean foundAny = false;
        for (String line : output.split("\\R")) {
            if (line.contains("Metaspace") || line.contains("class space")) {
                continue;
            }
            Matcher generation = GENERATION_PATTERN.matcher(line);
            if (generation.find()) {
                totalSum += Long.parseLong(generation.group(2)) * 1024L;
                usedSum += Long.parseLong(generation.group(3)) * 1024L;
                foundAny = true;
            }
        }
        if (foundAny) {
            return new HeapInfo(totalSum, usedSum, output, true);
        }
        return HeapInfo.unavailable(output);
    }
}