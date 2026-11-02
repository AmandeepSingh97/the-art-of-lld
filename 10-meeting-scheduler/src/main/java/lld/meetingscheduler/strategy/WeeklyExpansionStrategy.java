package lld.meetingscheduler.strategy;

import lld.meetingscheduler.model.RecurrenceRule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Expands a WEEKLY recurrence on one or more days-of-week, every {@code interval} weeks.
 * E.g. "every 2 weeks on Mon and Wed, starting the week of {@code seriesStartDate}".
 *
 * <p>Algorithm: walk week-by-week in steps of {@code interval} weeks starting from the Monday
 * of {@code seriesStartDate}'s week; within each visited week, emit one candidate date per
 * configured day-of-week (in day-of-week order, matching RRULE's documented iteration order).
 * Candidates before {@code seriesStartDate} (i.e. in the partial first week, for a day-of-week
 * that already passed that week) are skipped, since the series has not started yet.
 */
public final class WeeklyExpansionStrategy implements RecurrenceExpansionStrategy {

    @Override
    public List<LocalDate> expand(LocalDate seriesStartDate, RecurrenceRule rule, int maxOccurrences) {
        List<LocalDate> dates = new ArrayList<>();
        List<DayOfWeek> sortedDays = new ArrayList<>(new TreeSet<>(rule.daysOfWeek()));
        LocalDate mondayOfStartWeek = seriesStartDate.with(DayOfWeek.MONDAY);

        int count = 0;
        outer:
        for (int weekOffset = 0; ; weekOffset += rule.interval()) {
            LocalDate weekStart = mondayOfStartWeek.plusWeeks(weekOffset);
            for (DayOfWeek day : sortedDays) {
                LocalDate candidate = weekStart.with(day);
                if (candidate.isBefore(seriesStartDate)) {
                    continue; // partial first week: day-of-week already elapsed before series start
                }
                if (rule.until() != null && candidate.isAfter(rule.until())) {
                    break outer; // dates only increase from here on, safe to stop entirely
                }
                if (rule.count() != null && count >= rule.count()) {
                    break outer;
                }
                if (count >= maxOccurrences) {
                    break outer;
                }
                dates.add(candidate);
                count++;
            }
            // Termination is guaranteed without an extra guard: RecurrenceRule validation
            // requires exactly one of until/count to be set, and candidate dates strictly
            // increase with weekOffset, so one of the `break outer` conditions above (or the
            // maxOccurrences cap) is always eventually reached.
        }
        return dates;
    }
}
