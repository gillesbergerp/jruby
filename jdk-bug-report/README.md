# Automatic module loses its read edge to unnamed modules when the boot layer is restored from an AOT cache

**Status: draft, not submitted.** Scratch branch, not intended for merge into JRuby.

- **Component:** hotspot/runtime (cds) — or core-libs/java.lang, see Analysis
- **Affected:** OpenJDK 26.0.1+8-34 (only version tested; JDK 25 likely, untested)
- **Not affected:** OpenJDK 21.0.2+13 with CDS; OpenJDK 26.0.1 with CDS
- **Trigger:** `-XX:AOTCache` only

## Summary

An automatic module on the module path reads every unnamed module. When the boot layer is
restored from an AOT cache, that read edge is gone: `canRead` on an unnamed module returns
`false`, while readability of named modules is unaffected.

The module graph is otherwise intact, so this surfaces as scattered `IllegalAccessException`s
from `MethodHandles.Lookup` rather than as a module-system error, and only for classes loaded
by application-created class loaders.

## Reproducer

`Probe.java` is in this directory; no third-party code is involved.

```sh
mkdir -p out && javac -d out Probe.java
printf 'Automatic-Module-Name: com.example.probe\n' > mf.txt
jar --create --file probe.jar --manifest mf.txt -C out .

# 1. plain run
java --module-path probe.jar -m com.example.probe/probe.Probe

# 2. train an AOT cache
java -XX:AOTCacheOutput=probe.aot --module-path probe.jar -m com.example.probe/probe.Probe

# 3. run against the cache
java -XX:AOTCache=probe.aot --module-path probe.jar -m com.example.probe/probe.Probe
```

### Expected

Step 3 prints the same as step 1:

```
module      = com.example.probe (automatic=true)
canRead(java.logging) = true
canRead(unnamed)      = true
OK
```

### Actual

Step 3:

```
module      = com.example.probe (automatic=true)
canRead(java.logging) = true
canRead(unnamed)      = false
FAIL: automatic module lost its ALL_UNNAMED read edge
```

## Scope

| JDK | Archive mode | `canRead(unnamed)` |
| --- | --- | --- |
| 21.0.2 | `-XX:ArchiveClassesAtExit` | `true` |
| 26.0.1 | `-XX:ArchiveClassesAtExit` | `true` |
| 26.0.1 | `-XX:AOTCache` | **`false`** |
| 26.0.1 | `-XX:AOTCache -XX:-AOTClassLinking` | **`false`** |

Classic CDS is unaffected on both versions, and `-XX:-AOTClassLinking` does not help, so this
tracks the archived boot layer rather than AOT class linking.

## Analysis

`Module.defineModules` grants the edge, as a *reflective* one:

```java
// java.lang.Module
if (descriptor.isAutomatic()) {
    m.implAddReads(ALL_UNNAMED_MODULE, true);
}
```

`implAddReads` records it in `Module.ReflectionData.reads`, a `WeakPairMap`. `canRead`
deliberately cannot satisfy an unnamed `other` from the resolved `reads` set:

```java
// check if this module reads other
if (other.isNamed()) {                        // unnamed other skips the resolved set
    Set<Module> reads = this.reads;
    if (reads != null && reads.contains(other)) return true;
}
if (ReflectionData.reads.containsKeyPair(this, other)) return true;
if (!other.isNamed()
    && ReflectionData.reads.containsKeyPair(this, ALL_UNNAMED_MODULE)) return true;
return false;
```

So for an unnamed module the `ReflectionData` entry is the *only* thing that can return `true`.

`ModuleBootstrap.boot()` skips the code that creates it:

```java
ArchivedBootLayer archivedBootLayer = ArchivedBootLayer.get();
if (archivedBootLayer != null) {
    assert canUseArchivedBootLayer();
    bootLayer = archivedBootLayer.bootLayer();     // defineModules() never runs
    ...
} else {
    bootLayer = boot2();                           // only this path calls defineModules()
}
```

`Module.ArchivedData` archives only `ALL_UNNAMED_MODULE`, `ALL_UNNAMED_MODULE_SET`,
`EVERYONE_MODULE` and `EVERYONE_SET`. `ReflectionData` is not archived, and a `WeakPairMap`
arguably cannot be. The resolved `reads` sets ride along inside the archived `Module` objects,
which is why named-module readability survives and only unnamed modules regress.

Two further notes:

- `canUseArchivedBootLayer()` screens `--upgrade-module-path`, `--patch-module` and
  `--limit-modules`, but not `--module-path`. JDK 21's `ArchivedModuleGraph` path required
  `!haveModulePath`; dropping that is what exposes automatic modules here.
- Anything else that relies on `implAddReads`/`implAddExportsOrOpens` state established during
  `defineModules` is suspect for the same reason. The automatic-module edge is the one with an
  easy reproducer, not necessarily the only one.

## Impact

Any application shipped as an automatic module that loads classes through its own class loader
— plugin systems, script engines, embedded language runtimes — silently loses reflective and
`MethodHandles` access to those classes under `-XX:AOTCache`, while behaving normally without
it. Core reflection keeps working, because `Reflection.verifyMemberAccess` checks exports and
not readability, so failures appear only on the `Lookup` paths and are easy to misread as
application bugs.

Encountered in JRuby as jruby/jruby#9319: every Java class from a gem's jar lost its Ruby
proxy methods under an AOT cache, while JDK classes were fine.

## Suggested fix

Re-establish the reflective edges after restoring an archived boot layer — at minimum
re-running the `descriptor.isAutomatic()` branch of `defineModules` for the restored modules.
Alternatively make `canRead` consult the descriptor for automatic modules instead of relying
on `ReflectionData`, which would make the edge a property of the module rather than runtime
state that has to survive archiving.

## Workaround

Explicitly `addReads` the unnamed module of each loader whose classes the application needs
to reach.
