package com.belowdeck.collector;

import com.belowdeck.model.SmapsVma;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code /proc/<pid>/smaps} into a list of {@link SmapsVma} records.
 *
 * <p>This is the tool's ground-truth source for physical residency (KT doc S7.1) -
 * unlike NMT (self-reported by the JVM, and only for memory it allocated through
 * tracked paths) or cgroup stats (a single aggregate number), smaps enumerate
 * <i>every</i> mapped memory region in the process with its actual resident page
 * counts. That completeness is exactly what makes it possible to find "the gap"
 * (KT doc Part 10): anything there that doesn't fall inside an NMT reservation is
 * memory the JVM's own accounting never mentioned.
 *
 * <h2>Format (stable, documented kernel ABI - see {@code man 5 proc})</h2>
 * Each mapping starts with a header line, then a block of {@code Key: value kB}
 * fields until the next header line:
 * <pre>
 *     7f2c00000000-7f2c40000000 rw-p 00000000 00:00 0  [anon: Java heap]
 *     Size:        2048576 kB
 *     Rss:         524288 kB
 *     Pss:         524288 kB
 *     ...
 * </pre>
 * Unlike NMT's text output, this format has been stable for a very long time and is
 * documented as a real kernel interface, so this parser can be trusted more literally
 * than {@code NmtCollector}'s regexes.
 *
 * <h2>Permission requirement</h2>
 * Reading another process's smaps requires either running as the same user that owns
 * the target process, or {@code CAP_SYS_PTRACE} (subject to the kernel's yama
 * {@code ptrace_scope} setting). See KT doc S12.6. This collector assumes that access
 * is already in place (e.g. you're running the tool as the same user, or inside the same
 * container/pid namespace as the target) - it does not attempt to elevate privileges itself.
 */

public final class SmapsCollector {

    /** Header line: "start-end perms offset dev inode [pathname]". Pathname is optional (anonymous mappings). */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+(\\S+)\\s+\\S+\\s+\\S+:\\S+\\s+\\S+\\s*(.*)$");

    /** A "Key: 12345 kB" detail line. */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^(\\w+):\\s+(\\d+)\\s*kB\\s*$");

    /**
     * @param pid   target process id
     * @return every VMA in the process's smaps, in the order the kernel reported them
     * @throws CollectorException   if the file can't be read (missing /proc, permission denied,
     *                              or process has exited between discovery and read)
     */
    public List<SmapsVma> collect(long pid) {
        Path smapsPath = Path.of("/proc", Long.toString(pid), "smaps");
        List<String> lines;
        try {
            lines = Files.readAllLines(smapsPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CollectorException(
                    "Failed to read " + smapsPath + ". This tool must run on Linux, with permission " +
                    "to read the target process's /proc entries (same user, or CAP_SYS_PTRACE). " +
                    "If you're on Windows/macOS right now, this is expected - run on the target Linux host.", e);
        }
        return parse(lines);
    }

    /** Package-visible for unit testing against captured smaps fixtures. */
    static List<SmapsVma> parse(List<String> lines) {
        List<SmapsVma> vmas = new ArrayList<>();

        // Mutable accumulator for the VMA currently being parsed. Flushed into
        // `vmas` whenever a new header line starts, and once more at end-of-file.
        VmaBuilder current = null;

        for (String line : lines) {
            Matcher header = HEADER_PATTERN.matcher(line);
            if (header.matches()) {
                if (current != null) {
                    vmas.add(current.build());
                }
                long start = Long.parseUnsignedLong(header.group(1), 16);
                long end = Long.parseUnsignedLong(header.group(2), 16);
                String perms = header.group(3);
                String path = header.group(4).trim();
                current = new VmaBuilder(start, end, perms, path);
                continue;
            }
            if (current == null) {
                continue; // stray line before the first header - shouldn't happen, ignore defensively.
            }
            Matcher field = FIELD_PATTERN.matcher(line);
            if (field.matches()) {
                String key = field.group(1);
                long valueKb = Long.parseLong(field.group(2));
                current.applyField(key, valueKb);
            }
        }
        if (current != null) {
            vmas.add(current.build());
        }
        return vmas;
    }

    /** Accumulates the {@code Key: value kB} fields for one VMA before it's turned into an immutable record. */
    private static final class VmaBuilder {
        private final long start;
        private final long end;
        private final String perms;
        private final String path;
        private long rssKb;
        private long pssKb;
        private long privateCleanKb;
        private long privateDirtyKb;
        private long sharedCleanKb;
        private long sharedDirtyKb;
        private long anonymousKb;
        private long referenceKb;

        VmaBuilder(long start, long end, String perms, String path) {
            this.start = start;
            this.end = end;
            this.perms = perms;
            this.path = path;
        }

        void applyField(String key, long valueKb) {
            switch (key) {
                case "Rss" -> rssKb = valueKb;
                case "Pss" -> pssKb = valueKb;
                case "Private_Clean" -> privateCleanKb = valueKb;
                case "Private_Dirty" -> privateDirtyKb = valueKb;
                case "Shared_Clean" -> sharedCleanKb = valueKb;
                case "Shared_Dirty" -> sharedDirtyKb = valueKb;
                case "Anonymous" -> anonymousKb = valueKb;
                case "Referenced" -> referenceKb = valueKb;
                default -> { /* many other smaps field exist (Swap, Locked, VmFlags, ...) - not needed here */ }
            }
        }

        SmapsVma build() {
            return new SmapsVma(start, end, perms, path, rssKb, pssKb,
                    privateCleanKb, privateDirtyKb, sharedCleanKb, sharedDirtyKb, anonymousKb, referenceKb);
        }
    }
}