# Sequence Diagrams — Key Flows

## 1. Booking a (possibly recurring) meeting

```mermaid
sequenceDiagram
    actor Client
    participant SS as SchedulerService
    participant RES as RecurrenceExpansionService
    participant RLC as ResourceLockCoordinator
    participant RoomCal as TreeMapIntervalCalendar (Room)
    participant UserCal as TreeMapIntervalCalendar (per Attendee)
    participant MR as MeetingRepository
    participant BEL as BookingEventListener

    Client->>SS: bookMeeting(BookingRequest)
    SS->>SS: validate organizer/attendees/room exist
    alt recurring
        SS->>RES: expand(baseInterval, rule, zone, exceptions)
        RES-->>SS: List<TimeInterval> occurrences
    else one-off
        SS->>SS: occurrences = [baseInterval]
    end
    SS->>RLC: withLocks(sorted resource keys, timeout, criticalSection)
    activate RLC
    RLC->>RLC: acquire lock for each key in sorted order
    RLC->>SS: run criticalSection
    loop each occurrence
        SS->>RoomCal: findConflict(occurrence)
        SS->>UserCal: findConflict(occurrence)
    end
    note over SS: Phase 1 (validate) passed for every occurrence<br/>on every resource -- commit phase begins
    SS->>MR: save(new Meeting(...))
    loop each occurrence
        SS->>RoomCal: book(occurrence)
        SS->>UserCal: book(occurrence)
    end
    SS->>BEL: onMeetingBooked(meeting)
    RLC->>RLC: release all locks (reverse order)
    deactivate RLC
    SS-->>Client: BookingResult(meetingId, occurrences)
```

## 2. Conflict detection on a single resource (the DSA core)

```mermaid
sequenceDiagram
    participant SS as SchedulerService
    participant Cal as TreeMapIntervalCalendar
    participant Map as TreeMap<Instant, MeetingOccurrence>

    SS->>Cal: findConflict(candidate)
    Cal->>Map: floorEntry(candidate.start)
    Map-->>Cal: predecessor (or null)
    alt predecessor overlaps candidate
        Cal-->>SS: conflict = predecessor
    else
        Cal->>Map: ceilingEntry(candidate.start)
        Map-->>Cal: successor (or null)
        alt successor overlaps candidate
            Cal-->>SS: conflict = successor
        else
            Cal-->>SS: no conflict
        end
    end
```

## 3. Finding common free slots across attendees

```mermaid
sequenceDiagram
    actor Client
    participant SS as SchedulerService
    participant Cal as TreeMapIntervalCalendar (per attendee/room)
    participant FSF as FreeSlotFinder

    Client->>SS: findCommonFreeSlots(attendeeIds, roomId, window, minDuration)
    loop each attendee (+ room if present)
        SS->>Cal: occurrencesOverlapping(window)
        Cal-->>SS: busy intervals
    end
    SS->>FSF: findCommonFreeSlots(allBusyIntervals, window, minDuration)
    FSF->>FSF: sort by start -- O(n log n)
    FSF->>FSF: merge overlapping/touching intervals -- O(n)
    FSF->>FSF: walk gaps between merged blocks, keep gaps >= minDuration -- O(n)
    FSF-->>SS: List<TimeInterval> freeSlots
    SS-->>Client: freeSlots
```

## 4. Cancelling a single occurrence of a recurring meeting

```mermaid
sequenceDiagram
    actor Client
    participant SS as SchedulerService
    participant MR as MeetingRepository
    participant RLC as ResourceLockCoordinator
    participant RES as RecurrenceExpansionService
    participant Cal as TreeMapIntervalCalendar
    participant BEL as BookingEventListener

    Client->>SS: cancelMeeting(meetingId, SINGLE_OCCURRENCE, date)
    SS->>MR: findById(meetingId)
    MR-->>SS: Meeting
    SS->>RLC: withLocks(room+attendee keys, timeout, criticalSection)
    activate RLC
    SS->>RES: expand(...) to recompute current occurrences
    RES-->>SS: occurrences
    SS->>SS: filter occurrences matching `date`
    SS->>Cal: cancel(occurrence.start) [room + each attendee]
    SS->>SS: meeting.addExceptionDate(date)
    SS->>MR: save(meeting)
    SS->>BEL: onMeetingCancelled(meeting, occurrence)
    deactivate RLC
    SS-->>Client: void
```
