# Component Diagram

```mermaid
flowchart TB
    subgraph Client["Client / Demo"]
        Demo["Demo.main()"]
    end

    subgraph CommandLayer["Command Layer (lld.filesystem.command)"]
        Shell["Shell\n(dispatcher + tokenizer)"]
        Cmds["Command implementations\nMkdir / Cd / Pwd / Ls / Touch /\nCat / Write / Cp / Mv / Rm"]
        CommandResult["CommandResult\n(value object)"]
    end

    subgraph CoreLayer["Core Domain (lld.filesystem)"]
        FileSystem["FileSystem\n(facade + tree lock)"]
        Session["Session\n(per-caller cwd)"]
        PathResolver["PathResolver\n(stateless path math)"]
        Directory["Directory\n(composite)"]
        File["File\n(leaf, own content lock)"]
    end

    subgraph ExceptionLayer["Exceptions (lld.filesystem.exception)"]
        FSEx["FileSystemException + subtypes"]
    end

    Demo --> Shell
    Shell --> Cmds
    Cmds --> FileSystem
    Cmds --> CommandResult
    Shell -. catches .-> FSEx
    FileSystem --> PathResolver
    FileSystem --> Directory
    FileSystem --> Session
    Directory --> File
    Directory --> Directory
    FileSystem -. throws .-> FSEx
```

**Layering rules this enforces:**
- The Command layer depends on the Core layer, never the reverse (`FileSystem` has zero
  knowledge of `Command`/`Shell`), so the core domain is reusable from a GUI, a gRPC service, or
  a test harness without pulling in shell-parsing concerns.
- `Directory`/`File` are never referenced from the Command layer directly; all access goes
  through `FileSystem`, which is the only class allowed to acquire the tree lock. This is what
  makes "every mutation is correctly locked" a property of the architecture instead of a
  convention every command author has to remember.
