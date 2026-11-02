# Class Diagram

```mermaid
classDiagram
    direction LR

    class FileSystemEntry {
        <<abstract>>
        -long id
        -String name
        -Directory parent
        -Instant createdAt
        -Instant modifiedAt
        +getId() long
        +getName() String
        +getParent() Directory
        +isDirectory() boolean*
        +size() long*
        ~isAncestorOf(FileSystemEntry) boolean
    }

    class File {
        -ReadWriteLock contentLock
        -byte[] content
        +read() String
        +readBytes() byte[]
        +write(String text, boolean append) void
        ~copyAs(String, Directory) File
    }

    class Directory {
        -Map~String,FileSystemEntry~ children
        ~getChild(String) FileSystemEntry
        ~hasChild(String) boolean
        ~isEmpty() boolean
        ~listChildren() List~FileSystemEntry~
        ~putChild(FileSystemEntry) void
        ~removeChild(String) FileSystemEntry
        $createRoot() Directory
    }

    FileSystemEntry <|-- File
    FileSystemEntry <|-- Directory
    Directory "1" o-- "0..*" FileSystemEntry : children
    FileSystemEntry --> Directory : parent

    class Session {
        -AtomicReference~Directory~ cwd
        ~getCwd() Directory
        ~setCwd(Directory) void
        ~cwdSegments() List~String~
    }

    class PathResolver {
        <<utility>>
        $isAbsolute(String) boolean
        $normalize(String) List~String~
        $splitParentAndName(String, List) ParentAndName
        $resolveToAbsoluteSegments(String, List) List~String~
    }

    class FileSystem {
        -Directory root
        -ReadWriteLock treeLock
        +newSession() Session
        +pwd(Session) String
        +cd(Session, String) void
        +ls(Session, String) List~FileSystemEntry~
        +mkdir(Session, String, boolean) Directory
        +touch(Session, String) File
        +cat(Session, String) String
        +write(Session, String, String, boolean) void
        +rm(Session, String, boolean, boolean) void
        +cp(Session, String, String, boolean, boolean) void
        +mv(Session, String, String, boolean) void
    }

    FileSystem "1" --> "1" Directory : root
    FileSystem ..> PathResolver : uses
    FileSystem "1" o-- "0..*" Session : serves
    Session --> Directory : cwd

    class Command {
        <<interface>>
        +name() String
        +execute(FileSystem, Session, String[]) CommandResult
    }

    class CommandResult {
        -boolean success
        -String output
        -String errorMessage
        $ok(String) CommandResult
        $failure(String) CommandResult
    }

    class Shell {
        -Map~String,Command~ commands
        -FileSystem fileSystem
        -Session session
        +register(Command) void
        +execute(String) CommandResult
        $withDefaultCommands(FileSystem, Session) Shell
    }

    class MkdirCommand
    class CdCommand
    class PwdCommand
    class LsCommand
    class TouchCommand
    class CatCommand
    class WriteCommand
    class CpCommand
    class MvCommand
    class RmCommand

    Command <|.. MkdirCommand
    Command <|.. CdCommand
    Command <|.. PwdCommand
    Command <|.. LsCommand
    Command <|.. TouchCommand
    Command <|.. CatCommand
    Command <|.. WriteCommand
    Command <|.. CpCommand
    Command <|.. MvCommand
    Command <|.. RmCommand

    Shell "1" o-- "0..*" Command : registry
    Shell --> FileSystem : delegates to
    Shell --> CommandResult : returns
```

**Why this matches the implementation:** `FileSystemEntry` is the Composite base (abstract,
holds identity/name/parent); `File` is the leaf, `Directory` is the composite holding a
`LinkedHashMap<String, FileSystemEntry>`. `FileSystem` is the facade/orchestrator owning the
single `root` and the tree-structure lock; it never exposes `Directory`/`File` mutator methods
(`putChild`, `removeChild`, `setParent`, ...) outside the package -- those are package-private so
only `FileSystem` can call them under lock, which is what makes the locking policy enforceable
rather than just documented. `Session` is a pure value holder for one cwd pointer. `Command` /
`CommandResult` / `Shell` are the Command-pattern layer that sits *on top of* `FileSystem` and
never touches `Directory`/`File` directly.
