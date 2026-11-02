package lld.slackchat.model;

/**
 * Type of a {@link Channel}.
 *
 * <ul>
 *   <li>{@code PUBLIC} -- discoverable and joinable by any workspace member without an invite.</li>
 *   <li>{@code PRIVATE} -- invite-only; membership is a closed list maintained by existing
 *       members/admins.</li>
 *   <li>{@code DIRECT_MESSAGE} -- exactly two distinct users; created lazily the first time
 *       either user messages the other, and is otherwise immutable (no renaming, no
 *       additional members, cannot be left without deleting the conversation).</li>
 * </ul>
 */
public enum ChannelType {
    PUBLIC,
    PRIVATE,
    DIRECT_MESSAGE
}
