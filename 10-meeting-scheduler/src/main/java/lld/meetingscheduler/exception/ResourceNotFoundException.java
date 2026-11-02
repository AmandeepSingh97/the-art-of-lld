package lld.meetingscheduler.exception;

/** Thrown when a referenced {@code UserId}/{@code RoomId}/{@code MeetingId} does not exist. */
public final class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
