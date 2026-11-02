# Sequence Diagram — Send Message (happy path + fan-out)

```mermaid
sequenceDiagram
    actor Alice
    participant CS as ChatService
    participant CR as ChannelRepository
    participant RL as RateLimiter
    participant MP as MentionParser
    participant MS as MessageStore
    participant DP as ChannelDeliveryPolicy
    participant Strat as ChannelDeliveryStrategy
    participant EB as EventBus
    participant NS as NotificationService
    participant Bob as Bob's client (EventListener)

    Alice->>CS: sendMessage(channelId, aliceId, "@bob check this")
    CS->>CR: findById(channelId)
    CR-->>CS: Channel
    CS->>CS: requireChannelMember(channel, aliceId)
    CS->>RL: tryAcquire(aliceId)
    RL-->>CS: true (token available)
    CS->>MP: parseMentions("@bob check this")
    MP-->>CS: {bobId}
    CS->>MS: append(channelId, aliceId, content, {bobId})
    MS-->>CS: Message(seq=42)
    CS->>DP: strategyFor(channel)
    DP-->>CS: FanOutOnWriteStrategy (channel is small)
    CS->>Strat: deliver(channel, message, eventBus)
    Strat->>EB: publish("user:bob", MessageSentEvent)
    EB->>Bob: onEvent(MessageSentEvent)
    CS->>EB: publish("mention", MentionEvent(bob))
    EB->>NS: onEvent(MentionEvent)
    NS->>NS: inboxes[bob].add(Notification)
    CS-->>Alice: Message(seq=42)
```

**Notes:**
- Membership and rate-limit checks happen *before* any state mutation, so a rejected send
  never allocates a sequence number or appears in history (no "phantom" partial writes).
- Delivery and mention notification are both just publishes on the same `EventBus` — the
  Observer pattern is applied uniformly rather than having a separate mechanism per feature.
