package com.belowdeck.reconciler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * An augmented binary search tree supporting "stabbing queries": given a
 * point, find every stored half-open interval {@code [start, end)} that contains it.
 *
 * <p>This is the core data structure behind the reconciliation join described in
 * the KT doc S12.2: NMT reports large virtual-address reservations per category
 * ("Java Heap" spans this &nbsp;GB range, "Metaspace" spans that 1&nbsp;GB range, ...).
 * and for each of the (potentially many thousands of) smaps VMAs we need to answer
 * "which NMT reservation, if any, contains this VMA's start address?" Doing that with a
 * linear scan for every VMA against every NMT region would be O(regions x VMAs), but an
 * interval tree turns each lookup into O(log n + k) (k = number of matches) and is the
 * textbook right tool for exactly this kind of "which ranges contain point P" question, so
 * we use one - both for correctness at scale and because it's worth knowing this pattern.
 *
 * <h2>The augmentation: {@code maxEnd}</h2>
 * A plain BST order by interval start lets you binary-search on start, but that alone
 * can't answer "does interval contain point P", because an interval that starts very early
 * can still end very late (it might be the one range that contains P even though many
 * later-starting intervals don't). The standard fix (Cormen/Leiserson/Rivest/Stein "Interval Trees")
 * is to additionally track, at every node, the maximum {@code end} value anywhere in that
 * node's subtree. That lets the search prune entire subtrees: if a subtree's {@code maxEnd <= point},
 * nothing in it can possibly contain {@code point}, no matter how small its start values are.
 *
 * <h2>Why this stays deliberately unbalanced</h2>
 * A production interval tree would self-balance (red-black, AVL) to guarantee O(log n) even
 * under adversarial insert order. NMT typically reports a few dozen reservations per process, so
 * an unbalanced BST is never going to degrade meaningfully here - and keeping it as a plain BST keeps
 * the algorithm easy to read and verify, which matters more for a learning-focused tool than shaving
 * microseconds off a lookup that already runs a few dozen times per report.
 *
 * <h2>Nested / Overlapping interval</h2>
 * NMT reservations can in principle nest or overlap (KT doc S12.2 edge cases).
 * A stabbing query here returns <b>every</b> containing interaval, and it's the caller's job to decide
 * which one is "the" owner - see {@link #queryMostSpecific(long)}, which picks the smallest (most
 * specific) containing interval, matching the KT doc's guidance to prefer the most-specific match when
 * reservations nest.
 */

public class IntervalTree {

    /** One stored interval: a half-open virtual address range tagged with its owning NMT category. */
    public record Interval(long start, long end, String category) {
        public long length() {
            return end - start;
        }
    }

    private Node root;

    private static final class Node {
        final long start;
        final long end;
        final String category;
        /** Max `end` value across this node and its entire subtree - the augmentation that makes pruning possible. */
        long maxEnd;
        Node left;
        Node right;

        Node(long start, long end, String category) {
            this.start = start;
            this.end = end;
            this.category = category;
            this.maxEnd = end;
        }
    }

    public void insert(long start, long end, String category) {
        root = insert(root, start, end, category);
    }

    private Node insert(Node node, long start, long end, String category) {
        if (node == null) {
            return new Node(start, end, category);
        }
        if (start < node.start) {
            node.left = insert(node.left, start, end, category);
        } else {
            node.right = insert(node.right, start, end, category);
        }
        // The subtree just gained exactly one new interval, so it's maxEnd is either
        // unchanged or bumped up to this new interval's end - never anything else.
        node.maxEnd = Math.max(node.maxEnd, end);
        return node;
    }

    /** Return every stored interval that contains {@code point}, in no particular order. */
    public List<Interval> queryContaining(long point) {
        List<Interval> results = new ArrayList<>();
        queryContaining(root, point, results);
        return results;
    }

    private void queryContaining(Node node, long point, List<Interval> results) {
        if (node == null) {
            return;
        }
        // Prune: if the left subtree's furthest-reaching interval doesn't even reach
        // `point`, nothing in it can contain `point` - skip it entirely.
        if (node.left != null && node.left.maxEnd > point) {
            results.add(new Interval(node.start, node.end, node.category));
        }
        // Prune: every interval in the right subtree starts at or after node.start.
        // If `point` is before node.start, it's before every interval on the right too.
        if (point >= node.start) {
            queryContaining(node.right, point, results);
        }
    }

    /**
     * Convenience for the reconciler's join step: of all containing intervals, return
     * the smallest one (by address-range length), matching the KT doc's "query for the most
     * specific (smallest) containing interval" guidance for nested reservations.
     * Empty if no interval contains the point.
     */
    public Optional<Interval> queryMostSpecific(long point) {
        return queryContaining(point).stream().min(Comparator.comparingLong(Interval::length));
    }
}