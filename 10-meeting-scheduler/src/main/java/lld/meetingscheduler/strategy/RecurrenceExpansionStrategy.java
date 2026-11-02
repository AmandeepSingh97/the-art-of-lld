package lld.meetingscheduler.strategy;

import lld.meetingscheduler.model.RecurrenceRule;

import java.time.LocalDate;
import java.util.List;

/**
 * Strategy pattern: one implementation per {@link lld.meetingscheduler.enums.Frequency}.
 * Chosen over a single method with a {@code switch} on frequency because (a) each frequency's
 * date-generation logic is non-trivial and independently testable in isolation (see
 * {@code RecurrenceExpansionServiceTest}), and (b) adding a new frequency (e.g. MONTHLY) means
 * adding one new class implementing this interface and registering it -- the
 * {@link lld.meetingscheduler.service.RecurrenceExpansionService} dispatcher and every
 * existing strategy are untouched (Open/Closed Principle).
 */
public interface RecurrenceExpansionStrategy {

    /**
     * Generates the occurrence dates for a recurring meeting, starting from and including
     * {@code seriesStartDate}, bounded by whichever of {@code rule.until()}/{@code rule.count()}
     * is set, and never exceeding {@code maxOccurrences} regardless of the rule (safety cap).
     */
    List<LocalDate> expand(LocalDate seriesStartDate, RecurrenceRule rule, int maxOccurrences);
}
