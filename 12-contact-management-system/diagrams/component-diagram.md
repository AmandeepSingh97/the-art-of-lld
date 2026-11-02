# Component Diagram

Shows the major runtime components and their dependency direction. The
read path (search, duplicate detection) and the write path (service layer)
are intentionally decoupled through the Observer-pattern `IndexManager`:
neither `ContactService` nor `ContactRepository` has any compile-time
knowledge that indexes exist.

```mermaid
flowchart TB
    subgraph Client
        Main["Main / Demo"]
    end

    subgraph Facade
        CMS["ContactManagementSystem\n(Facade - thin wiring only)"]
    end

    subgraph WritePath["Write path"]
        CS["ContactService"]
        GS["GroupService"]
        IES["ImportExportService"]
        HS["HistoryService\n(Memento caretaker)"]
        MERGER["ContactMerger\n(pure merge logic)"]
    end

    subgraph ReadPath["Read path"]
        SS["SearchService"]
        DDS["DuplicateDetectionService"]
    end

    subgraph Storage
        CR["ContactRepository\n(InMemoryContactRepository)"]
        GR["GroupRepository\n(InMemoryGroupRepository)"]
    end

    subgraph Indexing["Indexing (Observer subscriber)"]
        IM["IndexManager"]
        NI["NameIndex\n(Trie or SortedArray)"]
        PI["HashLookupIndex (phone)"]
        EI["HashLookupIndex (email)"]
    end

    Main --> CMS
    CMS --> CS
    CMS --> GS
    CMS --> SS
    CMS --> DDS
    CMS --> IES

    CS --> CR
    CS --> GR
    CS --> HS
    CS --> MERGER
    GS --> CS
    GS --> GR
    GS --> IM
    IES --> CS
    IES --> GS

    CR -. "ContactChangeEvent\n(Observer notification)" .-> IM
    IM --> NI
    IM --> PI
    IM --> EI

    SS --> IM
    SS --> CR
    DDS --> IM
    DDS --> CR
```
