package com.belowdeck.collector;

import com.belowdeck.model.ProcStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a handful of fields from {@code /proc/<pid>/status} - the kernel's
 * own quick-look process summary (KT doc S7.1, Part 13 cheat sheet).
 *
 * <p>As documented on {@link ProcStatus}, this exists purely as a sanity
 * cross-check against the smaps-derived totals, not as a primary data source:
 * if the kernel's own {@code VmRSS} disagrees wildly with our summed-from-smaps
 * PSS/RSS, that's worth flagging as a collection anomaly (e.g. the process was
 * actively remapping memory mid-read) rather than silently trusting either number.
 */

public final class ProcStatusCollector {

    private static final Pattern VM_RSS_PATTERN = Pattern.compile("^VmRSS:\\s+(\\d+)\\s*kB\\s*$");
    private static final Pattern VM_SIZE_PATTERN = Pattern.compile("^VmSize:\\s+(\\d+)\\s*kB\\s*$");
    private static final Pattern THREADS_PATTERN = Pattern.compile("^Threads:\\s+(\\d+)\\s*$");

    public ProcStatus collect(long pid) {
        Path statusPath = Path.of("/proc", Long.toString(pid), "status");
        List<String> lines;
        try {
            lines = Files.readAllLines(statusPath);
        } catch (IOException e) {
            throw new CollectorException("Failed to read " + statusPath, e);
        }
        return parse(lines);
    }

    /** Package-visible for unit testing against captured fixtures. */
    static ProcStatus parse(List<String> lines) {
        long vmRssKb = 0;
        long vmSizeKb = 0;
        int threads = 0;

        for (String line : lines) {
            Matcher rss = VM_RSS_PATTERN.matcher(line);
            if (rss.matches()) {
                vmRssKb = Long.parseLong(rss.group(1));
                continue;
            }
            Matcher size = VM_SIZE_PATTERN.matcher(line);
            if (size.matches()) {
                vmSizeKb = Long.parseLong(size.group(1));
                continue;
            }
            Matcher threadsMatcher = THREADS_PATTERN.matcher(line);
            if (threadsMatcher.matches()) {
                threads = Integer.parseInt(threadsMatcher.group(1));
            }
        }
        return new ProcStatus(vmRssKb, vmSizeKb, threads);
    }
}
