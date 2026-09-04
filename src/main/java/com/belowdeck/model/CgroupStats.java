package com.belowdeck.model;

/**
 * Container memory accounting, read from the Linux cgroup that the target
 * process belongs to.
 *
 * <p>This is the number that actually gets you killed. Kubernetes does not
 * evict/OOM-kill a container based on the Java heap, and not even on raw RSS -
 * it watches {@code container_memory_working_set_bytes}, which the kubelet
 * computes the same way this class does (KT doc S7.3, S8.2):
 *
 * <pre>
 *     working_set = memory.current - inactive_file     (cgroup v2)
 *     working_set = memory.usage_in_bytes - total_inactive_file (cgroup v1)
 * </pre>
 *
 * <p>{@code inactive_file} is reclaimable page cache - file-backed ages the
 * kernel would drop under memory pressure before it ever reaches for the OOM
 * killer. Excluding it from the "working set" avoids penalizing a process for
 * memory the kernel can free for free.
 *
 * <p><b>Scope note:</b> this collector reads cgroup files from the conventional
 * mount points ({@code /sys/fs/cgroup/...}), which is correct when this tool runs
 * <i> inside the same cgroup/container</i> as the target JVM (e.g. you exec into
 * pod/container and run it there, or it runs as part of the same process tree).
 * Reading another container's cgroup from outside it (a separate sidecar with a
 * different cgroup) needs extra plumbing - see KT doc S12.6 - which is a packaging/
 * deployment concern explicitly out of scope for this pass of the tool.
 *
 * @param cgroupVersion     "v1" or "v2" - which cgroup hierarchy was detected
 * @param limitBytes        the hard memory limit ({@code memory.max} / {@code memory.limit_in_bytes}).
 *                          {@code Long.MAX_VALUE} if the cgroup reports "max" / no limit configured.
 * @param currentBytes      total charged memory right now ({@code memory.current} / {@code memory.usage_in_bytes})
 * @param inactiveFileKb    reclaimable file-backed page count, in KB, from {@code memory.stat}
 * @param workingSetBytes   {@code currentBytes - inactiveFile}, clamped at 0 - the number
 *                          Kubernetes actually compares against {@code limitBytes} to decide an OOM kill
 *
 */

public record CgroupStats(
        String cgroupVersion,
        long limitBytes,
        long currentBytes,
        long inactiveFileKb,
        long workingSetBytes
) {
    /** Convenience: how close the working set is to the hard limit, as a fraction in [0,1+]. */
    public double utilizationFraction() {
        if (limitBytes <= 0 || limitBytes == Long.MAX_VALUE) {
            return 0.0; // no limit configured - can't be OOM-killed on memory grounds.
        }
        return (double) workingSetBytes / (double) limitBytes;
    }
    public boolean hasLimit() {
        return limitBytes > 0 && limitBytes != Long.MAX_VALUE;
    }
}
