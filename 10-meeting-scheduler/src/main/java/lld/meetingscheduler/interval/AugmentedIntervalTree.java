package lld.meetingscheduler.interval;

import lld.meetingscheduler.model.MeetingOccurrence;
import lld.meetingscheduler.model.TimeInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * General-purpose augmented interval tree supporting resources where <b>multiple overlapping
 * bookings are legal</b> (e.g. a shared open-seating room with a capacity of 8, or "find
 * every meeting that touches this time range" reporting queries across an attendee's whole
 * history). Each node is augmented with {@code maxEnd}: the maximum end-time in its subtree,
 * which lets {@link #findAllOverlapping} prune entire subtrees that cannot possibly contain an
 * overlapping interval.
 *
 * <p><b>This class is intentionally not used on {@link lld.meetingscheduler.service.SchedulerService}'s
 * hot path.</b> {@link TreeMapIntervalCalendar} is strictly better for the actual problem
 * (single-occupancy room/attendee calendars where bookings are pairwise disjoint by
 * construction): same asymptotic complexity for the conflict check, but built on the JDK's
 * battle-tested, self-balancing {@link java.util.TreeMap} (red-black tree) instead of a
 * hand-rolled, unbalanced BST. This class exists to make the "when would a full interval tree
 * actually earn its complexity?" trade-off concrete and testable (see README §10) rather than
 * asserted without evidence -- the answer is: when the disjointness invariant does not hold,
 * i.e. a resource can legitimately have overlapping bookings and you need to enumerate *all*
 * of them, not just detect whether a conflict exists.
 *
 * <h2>Complexity</h2>
 * With a balanced tree of n intervals: insert O(log n), point/overlap query O(log n + k) where
 * k is the number of reported overlaps. This implementation is a plain (unbalanced) BST for
 * clarity, so these bounds are average-case, degrading to O(n) on adversarial insertion order;
 * a production version would use a red-black or AVL tree (or, as this codebase does for the
 * disjoint case, piggy-back on {@link java.util.TreeMap}'s balanced tree and the simpler
 * neighbour-check trick instead of reimplementing balancing).
 */
public final class AugmentedIntervalTree {

    private static final class Node {
        final MeetingOccurrence occurrence;
        Instant maxEnd;
        Node left;
        Node right;

        Node(MeetingOccurrence occurrence) {
            this.occurrence = occurrence;
            this.maxEnd = occurrence.interval().end();
        }
    }

    private Node root;
    private int size;

    public void insert(MeetingOccurrence occurrence) {
        root = insert(root, occurrence);
        size++;
    }

    private Node insert(Node node, MeetingOccurrence occurrence) {
        if (node == null) {
            return new Node(occurrence);
        }
        if (occurrence.interval().start().isBefore(node.occurrence.interval().start())) {
            node.left = insert(node.left, occurrence);
        } else {
            node.right = insert(node.right, occurrence);
        }
        if (node.maxEnd.isBefore(occurrence.interval().end())) {
            node.maxEnd = occurrence.interval().end();
        }
        return node;
    }

    /** Returns every booked occurrence whose interval overlaps {@code query}. */
    public List<MeetingOccurrence> findAllOverlapping(TimeInterval query) {
        List<MeetingOccurrence> results = new ArrayList<>();
        findAllOverlapping(root, query, results);
        return results;
    }

    private void findAllOverlapping(Node node, TimeInterval query, List<MeetingOccurrence> results) {
        if (node == null) {
            return;
        }
        // Prune: if the whole left subtree's max end is before the query starts, nothing in
        // it can overlap the query (every interval in it ends too early).
        if (node.left != null && !node.left.maxEnd.isBefore(query.start())) {
            findAllOverlapping(node.left, query, results);
        }
        if (node.occurrence.interval().overlaps(query)) {
            results.add(node.occurrence);
        }
        // Prune: only descend right if this node starts before the query ends -- anything
        // further right starts even later and so cannot overlap the query either.
        if (node.right != null && node.occurrence.interval().start().isBefore(query.end())) {
            findAllOverlapping(node.right, query, results);
        }
    }

    public int size() {
        return size;
    }
}
