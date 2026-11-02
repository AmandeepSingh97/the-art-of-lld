package lld.slackchat.repository;

import java.util.Optional;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.UserId;
import lld.slackchat.model.WorkspaceId;

public interface ChannelRepository {
    Channel save(Channel channel);

    Optional<Channel> findById(ChannelId id);

    /** Finds the existing direct-message channel between exactly these two users, if any. */
    Optional<Channel> findDirectMessage(WorkspaceId workspaceId, UserId userA, UserId userB);
}
