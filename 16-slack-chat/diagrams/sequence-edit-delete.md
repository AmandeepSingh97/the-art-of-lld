# Sequence Diagram — Edit and Delete with Audit

```mermaid
sequenceDiagram
    actor Alice
    participant CS as ChatService
    participant MS as MessageStore
    participant Msg as Message (synchronized)
    participant EB as EventBus

    Alice->>CS: editMessage(channelId, messageId, aliceId, "new content")
    CS->>MS: findById(messageId)
    MS-->>CS: Message
    CS->>CS: verify message.channelId == channelId
    CS->>Msg: edit(aliceId, "new content", newMentions)
    activate Msg
    Msg->>Msg: check senderId == aliceId (else Unauthorized)
    Msg->>Msg: check state != DELETED (else InvalidState)
    Msg->>Msg: editHistory.add(previousContent)
    Msg->>Msg: content = "new content"; state = EDITED
    deactivate Msg
    CS->>EB: publish("channel:<id>", MessageEditedEvent)

    Alice->>CS: deleteMessage(channelId, messageId, aliceId)
    CS->>MS: findById(messageId)
    MS-->>CS: Message
    CS->>Msg: delete(aliceId)
    activate Msg
    Msg->>Msg: check senderId == aliceId
    Msg->>Msg: check state != DELETED (else InvalidState, no double delete)
    Msg->>Msg: editHistory.add(previousContent); content = tombstone; state = DELETED
    deactivate Msg
    CS->>EB: publish("channel:<id>", MessageDeletedEvent)
```

**Why the lock is on `Message`, not on `Channel` or a global lock:** editing message A and
deleting message B in the same channel are logically independent operations and must not
contend with each other. Scoping the lock to the individual `Message` instance gives the
narrowest correct granularity — see README §11 for the full concurrency justification,
including why `synchronized` (not a `CAS` on a single field) is appropriate here: the
check-then-act sequence spans three fields (`content`, `state`, `mentionedUserIds`) that must
change together atomically.
