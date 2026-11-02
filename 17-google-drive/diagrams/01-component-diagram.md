# Component Diagram

High-level module boundaries. Arrows show "depends on". Every arrow points toward an
interface, not an implementation, except where noted -- this is what keeps
`DriveService` swappable/testable (see README section 7, Dependency Inversion).

```mermaid
flowchart TB
    subgraph API["Facade"]
        DS[DriveService]
    end

    subgraph Core["Domain Services"]
        PS[PermissionService]
        TS[TrashService]
        SS[SearchService]
        NF[DriveNodeFactory]
    end

    subgraph Infra["Infrastructure"]
        QM[QuotaManager]
        BS[("BlobStore «interface»")]
        NR[("NodeRepository «interface»")]
        NS[NotificationService]
    end

    subgraph Impl["In-memory implementations"]
        IBS[InMemoryBlobStore]
        INR[InMemoryNodeRepository]
        HS[("HashingStrategy «interface»")]
        SHA[Sha256HashingStrategy]
    end

    subgraph Strategy["Permission Strategy"]
        PRS[("PermissionResolutionStrategy «interface»")]
        IAPS[InheritedAclPermissionStrategy]
    end

    DS --> PS
    DS --> TS
    DS --> SS
    DS --> NF
    DS --> QM
    DS --> BS
    DS --> NR
    DS --> NS

    PS --> PRS
    PRS -.implemented by.-> IAPS

    TS --> BS
    TS --> QM
    TS --> NR
    TS --> SS

    SS --> PS

    BS -.implemented by.-> IBS
    NR -.implemented by.-> INR
    IBS --> HS
    HS -.implemented by.-> SHA
```
