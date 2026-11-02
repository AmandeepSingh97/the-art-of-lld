package lld.slackchat.factory;

import java.time.Instant;
import java.util.Set;
import lld.slackchat.exception.InvalidChannelOperationException;
import lld.slackchat.model.Channel;
import lld.slackchat.model.ChannelId;
import lld.slackchat.model.ChannelType;
import lld.slackchat.model.UserId;
import lld.slackchat.model.WorkspaceId;

/**
 * Centralizes the validated construction of {@link Channel} instances for each
 * {@link ChannelType}, so {@code ChatServiceImpl} never builds a {@code Channel} by hand and
 * risks skipping a type-specific invariant (e.g. "a DM has exactly two distinct members").
 * This is a plain static Factory rather than an Abstract Factory/Factory-Method hierarchy
 * because there is only one product type ({@code Channel}) with construction-time validation
 * that varies by an enum, not a family of related products that varies by platform/context --
 * introducing a class hierarchy here would be unjustified ceremony.
 */
public final class ChannelFactory {

    private ChannelFactory() {}

    public static Channel createPublicChannel(
            WorkspaceId workspaceId, String name, UserId creatorId, Set<UserId> initialMembers) {
        Set<UserId> members = new java.util.HashSet<>(initialMembers);
        members.add(creatorId);
        return new Channel(
                ChannelId.generate(), workspaceId, name, ChannelType.PUBLIC, creatorId, Instant.now(), members);
    }

    public static Channel createPrivateChannel(
            WorkspaceId workspaceId, String name, UserId creatorId, Set<UserId> initialMembers) {
        Set<UserId> members = new java.util.HashSet<>(initialMembers);
        members.add(creatorId);
        return new Channel(
                ChannelId.generate(), workspaceId, name, ChannelType.PRIVATE, creatorId, Instant.now(), members);
    }

    public static Channel createDirectMessage(WorkspaceId workspaceId, UserId userA, UserId userB) {
        if (userA.equals(userB)) {
            throw new InvalidChannelOperationException("Direct message requires two distinct users");
        }
        String name = "dm:" + userA.value() + ":" + userB.value();
        return new Channel(
                ChannelId.generate(),
                workspaceId,
                name,
                ChannelType.DIRECT_MESSAGE,
                userA,
                Instant.now(),
                Set.of(userA, userB));
    }
}
