package lld.meetingscheduler.service;

import lld.meetingscheduler.model.RecurrenceRule;
import lld.meetingscheduler.model.TimeInterval;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecurrenceExpansionServiceTest {

    private final RecurrenceExpansionService service = new RecurrenceExpansionService();
    private final ZoneId utc = ZoneOffset.UTC;

    private TimeInterval baseInterval(LocalDate date, int hour) {
        ZonedDateTime start = date.atTime(hour, 0).atZone(utc);
        return new TimeInterval(start.toInstant(), start.plusHours(1).toInstant());
    }

    @Test
    void dailyExpansionByCountProducesExactNumberOfOccurrences() {
        LocalDate start = LocalDate.of(2025, 6, 2); // Monday
        RecurrenceRule rule = RecurrenceRule.daily(1, 5);
        List<TimeInterval> occurrences = service.expand(baseInterval(start, 9), rule, utc, Set.of());

        assertEquals(5, occurrences.size());
        for (int i = 0; i < 5; i++) {
            LocalDate expectedDate = start.plusDays(i);
            assertEquals(expectedDate, occurrences.get(i).start().atZone(utc).toLocalDate());
        }
    }

    @Test
    void dailyExpansionEveryOtherDaySkipsDays() {
        LocalDate start = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.daily(2, 3);
        List<TimeInterval> occurrences = service.expand(baseInterval(start, 9), rule, utc, Set.of());

        assertEquals(List.of(
                start, start.plusDays(2), start.plusDays(4)
        ), occurrences.stream().map(o -> o.start().atZone(utc).toLocalDate()).toList());
    }

    @Test
    void weeklyExpansionOnMultipleDaysRespectsOrderAndCount() {
        LocalDate monday = LocalDate.of(2025, 6, 2); // Monday
        RecurrenceRule rule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 4);
        List<TimeInterval> occurrences = service.expand(baseInterval(monday, 14), rule, utc, Set.of());

        List<LocalDate> dates = occurrences.stream().map(o -> o.start().atZone(utc).toLocalDate()).toList();
        assertEquals(List.of(
                LocalDate.of(2025, 6, 2),  // Mon week 1
                LocalDate.of(2025, 6, 4),  // Wed week 1
                LocalDate.of(2025, 6, 9),  // Mon week 2
                LocalDate.of(2025, 6, 11)  // Wed week 2
        ), dates);
    }

    @Test
    void weeklyExpansionStartingMidWeekSkipsAlreadyElapsedDay() {
        // Series starts on Wednesday; the Monday of that same week must NOT be produced.
        LocalDate wednesday = LocalDate.of(2025, 6, 4);
        RecurrenceRule rule = RecurrenceRule.weekly(1, Set.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), 2);
        List<TimeInterval> occurrences = service.expand(baseInterval(wednesday, 14), rule, utc, Set.of());

        List<LocalDate> dates = occurrences.stream().map(o -> o.start().atZone(utc).toLocalDate()).toList();
        assertEquals(List.of(
                LocalDate.of(2025, 6, 4),  // Wed week 1 (Mon already elapsed, skipped)
                LocalDate.of(2025, 6, 9)   // Mon week 2
        ), dates);
    }

    @Test
    void weeklyExpansionWithIntervalTwoSkipsAlternateWeeks() {
        LocalDate monday = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.weekly(2, Set.of(DayOfWeek.MONDAY), 3);
        List<TimeInterval> occurrences = service.expand(baseInterval(monday, 9), rule, utc, Set.of());

        List<LocalDate> dates = occurrences.stream().map(o -> o.start().atZone(utc).toLocalDate()).toList();
        assertEquals(List.of(
                LocalDate.of(2025, 6, 2),
                LocalDate.of(2025, 6, 16),
                LocalDate.of(2025, 6, 30)
        ), dates);
    }

    @Test
    void exceptionDatesAreExcludedFromExpansion() {
        LocalDate start = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.daily(1, 5);
        List<TimeInterval> occurrences =
                service.expand(baseInterval(start, 9), rule, utc, Set.of(start.plusDays(2)));

        assertEquals(4, occurrences.size());
        assertTrue(occurrences.stream()
                .noneMatch(o -> o.start().atZone(utc).toLocalDate().equals(start.plusDays(2))));
    }

    @Test
    void untilBoundedDailyRecurrenceStopsOnBoundary() {
        LocalDate start = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.daily(1, start.plusDays(2));
        List<TimeInterval> occurrences = service.expand(baseInterval(start, 9), rule, utc, Set.of());

        assertEquals(3, occurrences.size()); // start, start+1, start+2 inclusive
    }

    @Test
    void preservesTimeOfDayAndDurationAcrossOccurrences() {
        LocalDate start = LocalDate.of(2025, 6, 2);
        RecurrenceRule rule = RecurrenceRule.daily(1, 3);
        List<TimeInterval> occurrences = service.expand(baseInterval(start, 9), rule, utc, Set.of());

        for (TimeInterval occurrence : occurrences) {
            assertEquals(9, occurrence.start().atZone(utc).getHour());
            assertEquals(java.time.Duration.ofHours(1), occurrence.duration());
        }
    }
}
