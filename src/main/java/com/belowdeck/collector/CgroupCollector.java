package com.belowdeck.collector;

import com.belowdeck.model.CgroupStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Read container memory accounting from the Linux cgroup filesystem and
 * computes the "working set" figure the same way Kubernetes does.
 *
 * <p>Background (KT doc Part 8): Kubernetes decides whether to OOM-kill a
 * container based on {@code container_memory_working_set_bytes}, computed as:
 * <pre>
 *     # cgroup v2
 *     working_set = memory.current - inactive_file                 (inactive_file from memory.stat)
 *     # cgroup v1
 *     working_set = memory.usage_in_bytes - total_inactive_file    (from memory.stat)
 * </pre>
 * This is the single most important number for explaining an {@code OOMKilled}
 * exit 137 with clean application logs (KT doc S9.2-9.3): the kernel isn't
 * watching heap usage or even raw RSS, it's watching this.
 *
 * <h2>cgroup v1 vs v2</h2>
 * Modern distros/clusters mostly use v2 (a single unified hierarchy mounted at
 * {@code /sys/fs/cgroup}), but v1 (a separate hierarchy per controller, e.g.
 * {@code /sys/fs/cgroup/memory}) is still common enough to be worth handling.
 * This collector auto-detects which one is present by checking for
 * {@code /sys/fs/cgroup/memory.max} (v2) first, then falling back to
 * {@code /sys/fs/cgroup/memory/memory.limit_in_bytes} (v1).
 *
 * <h2>Scope: assumes the tool runs inside the target's own cgroup</h2>
 * These are the conventional host-visible mount points, correct when this tool
 * executes in the same cgroup/container as the JVM it's inspecting (e.g. you
 * exec into the pod and run it there). Reading a *different* container's cgroup
 * from outside it needs to resolve that container's specific cgroup path
 * (via {@code /proc/<pid>/cgroup} plus knowledge of the host mount layout) -
 * a deployment/packaging concern the KT doc flags in S12.6 and which is
 * deliberately out of scope for this pass of the tool.
 */

public final class CgroupCollector {

    private static final Path CGROUP_V2_ROOT = Path.of("/sys/fs/cgroup");
    private static final Path CGROUP_V1_MEMORY_ROOT = Path.of("/sys/fs/cgroup/memory");

    /**
     * cgroup v1's "no limit" sentinel is a huge number close to LONG_MAX,
     * rounded down to a page boundary (typically 9223372036854771712). Any
     * limit at or above this threshold is treated as "unlimited" rather tha
     * a real, absurdly large limit.
     */
    private static final long V1_UNLIMITED_THRESHOLD = 1L << 62;

    /**
     * @return  cgroup stats if a supported cgroup hierarchy was found, or
     *          {@code Optional.empty()} if this process doesn't appear to be
     *          running under a memory cgroup at all (e.g. bare-metal, no container)
     * @throws  CollectorException if a cgroup was detected but its files couldn't be read
     */
    public Optional<CgroupStats> collect() {
        if (Files.exists(CGROUP_V2_ROOT.resolve("memory.max"))) {
            return Optional.of(collectV2());
        }
        if (Files.exists(CGROUP_V1_MEMORY_ROOT.resolve("memory.limit_in_bytes"))) {
            return Optional.of(collectV1());
        }
        return Optional.empty();
    }

    private CgroupStats collectV2() {
        long limit = parseLimitOrMax(CGROUP_V2_ROOT.resolve("memory.max"), "max");
        long current = readLongFile(CGROUP_V2_ROOT.resolve("memory.current"));
        long inactiveFileBytes = readStatField(CGROUP_V2_ROOT.resolve("memory.stat"), "inactive_file");
        long workingSet = Math.max(0, current - inactiveFileBytes);
        return new CgroupStats("v2", limit, current, inactiveFileBytes / 1024, workingSet);
    }

    private CgroupStats collectV1() {
        long rawLimit = readLongFile(CGROUP_V1_MEMORY_ROOT.resolve("memory.limit_in_bytes"));
        long limit = rawLimit >= V1_UNLIMITED_THRESHOLD ? Long.MAX_VALUE : rawLimit;
        long current = readLongFile(CGROUP_V1_MEMORY_ROOT.resolve("memory.usage_in_bytes"));
        long inactiveFileBytes = readStatField(CGROUP_V1_MEMORY_ROOT.resolve("memory.stat"), "total_inactive_file");
        long workingSet = Math.max(0, current - inactiveFileBytes);
        return new CgroupStats("v1", limit, current, inactiveFileBytes / 1024, workingSet);
    }

    private long parseLimitOrMax(Path path, String unlimitedLiteral) {
        String content = readTrimmed(path);
        if (unlimitedLiteral.equals(content)) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(content);
    }

    private long readLongFile(Path path) {
        return Long.parseLong(readTrimmed(path));
    }

    private String readTrimmed(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (IOException e) {
            throw new CollectorException("Failed to read cgroup file " + path, e);
        }
    }

    /** Parse a "key value" or "key value\n..." style stats file and returns the value for one key, in bytes. */
    private long readStatField(Path path, String key) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new CollectorException("Failed to read cgroup stats file " + path, e);
        }
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals(key)) {
                return Long.parseLong(parts[1]);
            }
        }
        throw new CollectorException("Field '" + key + "' not found in " + path +
                " - cgroup kernel version may differ from what this collector excepts.");
    }
}