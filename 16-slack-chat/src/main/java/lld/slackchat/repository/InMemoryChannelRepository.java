package lld.slackchat.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.ChannelType;
import lld.slackchat.model.UserId;
import lld.slackchat.model.WorkspaceId;

/**
 * In-memory {@link ChannelRepository}.
 *
 * <p>Maintains a secondary index for direct-message lookup keyed by
 * {@code (workspaceId, sorted pair of the two user ids)} so {@code getOrCreateDirectMessage}
 * in {@code ChatServiceImpl} can check "does this DM already exist" in O(1) instead of
 * scanning every channel in the workspace.
 */
public final class InMemoryChannelRepository implements ChannelRepository {

    private final Map<ChannelId, Channel> channels = new ConcurrentHashMap<>();
    private final Map<String, Channel> directMessageIndex = new ConcurrentHashMap<>();

    @Override
    public Channel save(Channel channel) {
        channels.put(channel.id(), channel);
        if (channel.type() == ChannelType.DIRECT_MESSAGE) {
            directMessageIndex.put(dmKey(channel.workspaceId(), channel.memberSnapshot()), channel);
        }
        return channel;
    }

    @Override
    public Optional<Channel> findById(ChannelId id) {
        return Optional.ofNullable(channels.get(id));
    }

    @Override
    public Optional<Channel> findDirectMessage(WorkspaceId workspaceId, UserId userA, UserId userB) {
        return Optional.ofNullable(directMessageIndex.get(dmKey(workspaceId, List.of(userA, userB))));
    }

    private static String dmKey(WorkspaceId workspaceId, Iterable<UserId> members) {
        List<String> sorted = new java.util.ArrayList<>();
        members.forEach(m -> sorted.add(m.value()));
        sorted.sort(String::compareTo);
        return workspaceId.value() + "::" + String.join(":", sorted);
    }
}
