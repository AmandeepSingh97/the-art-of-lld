package lld.meetingscheduler.service;

import lld.meetingscheduler.model.RecurrenceRule;
import lld.meetingscheduler.model.TimeInterval;
import lld.meetingscheduler.strategy.DailyExpansionStrategy;
import lld.meetingscheduler.strategy.RecurrenceExpansionStrategy;
import lld.meetingscheduler.strategy.WeeklyExpansionStrategy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches to the correct {@link RecurrenceExpansionStrategy} for a meeting's
 * {@link lld.meetingscheduler.enums.Frequency} and converts the resulting occurrence
 * <i>dates</i> into concrete {@link TimeInterval}s by reusing the base meeting's
 * time-of-day and duration on every occurrence date, in the base interval's own timezone
 * (so a 9am recurring meeting stays at 9am local time for its organizer across DST
 * transitions, rather than drifting by an hour -- matching how Google Calendar/Outlook
 * anchor recurring meetings to a local time-of-day, not a fixed UTC offset).
 */
public final class RecurrenceExpansionService {

    private final Map<lld.meetingscheduler.enums.Frequency, RecurrenceExpansionStrategy> strategies =
            new EnumMap<>(lld.meetingscheduler.enums.Frequency.class);

    public RecurrenceExpansionService() {
        strategies.put(lld.meetingscheduler.enums.Frequency.DAILY, new DailyExpansionStrategy());
        strategies.put(lld.meetingscheduler.enums.Frequency.WEEKLY, new WeeklyExpansionStrategy());
    }

    /**
     * Expands {@code baseInterval} according to {@code rule}, anchored in {@code zone}, skipping
     * any date present in {@code exceptionDates}. Always returns at least the base occurrence
     * itself as the first element when it is not excepted.
     */
    public List<TimeInterval> expand(
            TimeInterval baseInterval, RecurrenceRule rule, ZoneId zone, java.util.Set<LocalDate> exceptionDates) {
        RecurrenceExpansionStrategy strategy = strategies.get(rule.frequency());
        if (strategy == null) {
            throw new IllegalStateException("No expansion strategy registered for " + rule.frequency());
        }

        ZonedDateTime baseStartLocal = baseInterval.start().atZone(zone);
        ZonedDateTime baseEndLocal = baseInterval.end().atZone(zone);
        Duration duration = Duration.between(baseStartLocal, baseEndLocal);
        LocalDate seriesStartDate = baseStartLocal.toLocalDate();

        List<LocalDate> occurrenceDates =
                strategy.expand(seriesStartDate, rule, RecurrenceRule.MAX_OCCURRENCES);

        List<TimeInterval> occurrences = new ArrayList<>();
        for (LocalDate date : occurrenceDates) {
            if (exceptionDates.contains(date)) {
                continue;
            }
            ZonedDateTime occurrenceStart = date.atTime(baseStartLocal.toLocalTime()).atZone(zone);
            ZonedDateTime occurrenceEnd = occurrenceStart.plus(duration);
            occurrences.add(new TimeInterval(occurrenceStart.toInstant(), occurrenceEnd.toInstant()));
        }
        return occurrences;
    }
}
