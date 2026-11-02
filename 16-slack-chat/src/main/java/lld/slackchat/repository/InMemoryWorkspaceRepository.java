package lld.slackchat.repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lld.slackchat.model.Workspace;
import lld.slackchat.model.WorkspaceId;

/** In-memory {@link WorkspaceRepository} backed by a {@link ConcurrentHashMap}. */
public final class InMemoryWorkspaceRepository implements WorkspaceRepository {

    private final Map<WorkspaceId, Workspace> workspaces = new ConcurrentHashMap<>();

    @Override
    public Workspace save(Workspace workspace) {
        workspaces.put(workspace.id(), workspace);
        return workspace;
    }

    @Override
    public Optional<Workspace> findById(WorkspaceId id) {
        return Optional.ofNullable(workspaces.get(id));
    }
}
