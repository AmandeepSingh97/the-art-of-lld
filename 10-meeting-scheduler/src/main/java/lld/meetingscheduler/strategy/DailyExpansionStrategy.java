package lld.meetingscheduler.strategy;

import lld.meetingscheduler.model.RecurrenceRule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Expands a DAILY recurrence: every {@code interval} days starting at {@code seriesStartDate}. */
public final class DailyExpansionStrategy implements RecurrenceExpansionStrategy {

    @Override
    public List<LocalDate> expand(LocalDate seriesStartDate, RecurrenceRule rule, int maxOccurrences) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate cursor = seriesStartDate;
        int count = 0;
        while (count < maxOccurrences) {
            if (rule.until() != null && cursor.isAfter(rule.until())) {
                break;
            }
            if (rule.count() != null && count >= rule.count()) {
                break;
            }
            dates.add(cursor);
            count++;
            cursor = cursor.plusDays(rule.interval());
        }
        return dates;
    }
}
