package lld.slackchat.repository;

import java.util.Optional;
import lld.slackchat.model.Workspace;
import lld.slackchat.model.WorkspaceId;

public interface WorkspaceRepository {
    Workspace save(Workspace workspace);

    Optional<Workspace> findById(WorkspaceId id);
}
