# AstraSyntax

**A scripting and automation platform for Minecraft servers.**
Write rules in two ways — a structured `Astra Language`, or plain English — and both are
compiled by the same pipeline into the same runtime rules.

| | |
|---|---|
| **Name** | AstraSyntax |
| **Purpose** | Script and automate a Minecraft server with `.ar` files: events, timers, commands, conditions, data, economy, HTTP/webhooks and integrations — hot-reloadable, sandboxed and profiled. |
| **Author** | AstraLab – LivingCritz DevSolentz |
| **Target version line** | `1.21.11-26.2` |
| **Supported software** | **Folia**, **Paper**, **Purpur**, **Spigot**, **Leaf** (one jar for all five) |
| **Native script extension** | `.ar` (never `.sk`) |
| **Java requirement** | **Java 21** (build and runtime) |
| **API version** | `1.21` (`plugin.yml: api-version`) |
| **Folia support** | declared (`folia-supported: true`) and implemented through a region-aware scheduler abstraction |

---

## Table of contents

1. [What it does](#1-what-it-does)
2. [Installation](#2-installation)
3. [Configuration files](#3-configuration-files)
4. [The `.ar` language — structured mode](#4-the-ar-language--structured-mode)
5. [The `.ar` language — natural language mode](#5-the-ar-language--natural-language-mode)
6. [Diagnostics](#6-diagnostics)
7. [Commands and permissions](#7-commands-and-permissions)
8. [Storage](#8-storage)
9. [Security model](#9-security-model)
10. [Folia support](#10-folia-support)
11. [Build with Gradle](#11-build-with-gradle)
12. [Build with Maven](#12-build-with-maven)
13. [Offline / fallback build (used for verification here)](#13-offline--fallback-build-used-for-verification-here)
14. [Dependencies](#14-dependencies)
15. [Tests and how to run them](#15-tests-and-how-to-run-them)
16. [JAR output locations and what is inside](#16-jar-output-locations-and-what-is-inside)
17. [Verification performed](#17-verification-performed)
18. [Delivered vs. roadmap](#18-delivered-vs-roadmap)
19. [Project layout](#19-project-layout)
20. [Troubleshooting](#20-troubleshooting)
21. [Development notes](#21-development-notes)

---

## 1. What it does

AstraSyntax watches the server, and when something happens that a script cares about it
runs that script's actions:

```
on player join:
    tell player "Welcome to the server!"
```

```
When a player joins, tell them "Welcome to the server!"
```

Both files above are compiled, at load time, into the same compiled rule — an event
binding, a filter list, a condition list and a list of resolved action calls. **Nothing is
parsed while events are firing**: scripts are compiled on load/reload, and the hot path
only evaluates already-resolved expressions.

Core concepts (all present in the code as first-class types):

| Concept | Where it lives |
|---|---|
| **Script** | `io.astra.runtime.script.Script` — one `.ar` file, keeps the active *and* the previous version |
| **Event / Trigger** | `io.astra.runtime.event.EventDefinition`, `EventBus`, 76 built-in triggers |
| **Condition** | `io.astra.runtime.condition.ConditionDefinition` — 33 built-in conditions |
| **Action** | `io.astra.runtime.action.ActionDefinition` — 47 built-in actions |
| **Expression** | `io.astra.runtime.expression.ExpressionDefinition` — 13 built-in expressions |
| **Value** | `io.astra.runtime.Value` (sealed: text, number, decimal, boolean, list, uuid, location, item) |
| **Context** | `io.astra.runtime.ExecContext` — one per invocation, with alias groups (`player/them`, `killer/damager`, …) |
| **Function** | `command`/`function` declarations → `CompiledFunction`, called with `call` |
| **Command** | `command /name:` → `CompiledCommand`, registered dynamically |
| **Timer** | `every 10 minutes:` → `CompiledRule` with a period in ticks |
| **Data reference** | `data coins: number persistent = 0` → typed, per-player or global, persisted |
| **Module contribution** | `io.astra.module.AstraModule` — jars in `modules/` that add vocabulary or services |
| **Diagnostic** | `io.astra.language.diagnostics.Diagnostic` — file, line, column, category, explanation, suggestion |

---

## 2. Installation

1. Put **`AstraSyntax-1.21.11-26.2.jar`** (see [§16](#16-jar-output-locations-and-what-is-inside))
   into your server's `plugins/` folder.
2. Start the server once. AstraSyntax creates `plugins/AstraSyntax/` and writes the nine
   configuration files, the `scripts/`, `modules/`, `packages/`, `data/`, `logs/`, `cache/`
   folders and the five example scripts.
3. Put your `.ar` files in `plugins/AstraSyntax/scripts/`.
4. Run `/astra reload` (or restart) and check `/astra info`.

Requirements: **Java 21**, and a server on the 1.21 line (Folia, Paper, Purpur, Spigot or
Leaf). Nothing else is required — SQLite is bundled inside the jar.

---

## 3. Configuration files

All nine files are read from `plugins/AstraSyntax/`. They ship inside the jar and are
written on first start. **Existing values are never silently reset**: AstraSyntax keeps a
state file of what it installed, and a file you have edited is left alone (new keys are
reported as a `ConfigIssue` instead of being forced in).

| File | What it controls |
|---|---|
| `config.yml` | general switches, script folder/extension, natural language, developer options, examples, and the 17 `features.*` switches |
| `storage.yml` | backend (`sqlite`, `mysql`, `mariadb`, `file`), connection settings, autosave interval, cache |
| `performance.yml` | compile cache, parallel loading, slow-rule threshold, profiler history, loop/task limits |
| `security.yml` | console commands, file access, HTTP, webhooks, domains, remote install, module trust, size/loop limits |
| `language.yml` | MiniMessage prefix and every message the plugin sends |
| `modules.yml` | module folder, auto-load, compatibility checks, failure isolation |
| `packages.yml` | package folder, auto-load, dependency resolution, remote-install switches |
| `integrations.yml` | Vault, PlaceholderAPI, Citizens, WorldGuard, Redis (`enabled` + `auto`) |
| `logging.yml` | log level, console/file output, separate error file, stack traces, script source in errors |

`features.*` is enforced, not decorative: a script that uses `give-money` (economy) is
refused at load time while `features.economy: false`, with a diagnostic naming
`features.economy`, and the previously loaded version of that script keeps running.

---

## 4. The `.ar` language — structured mode

### 4.1 Events

```ar
on player join:                 # every trigger phrase from the vocabulary
    tell player "Welcome!"

on block break:
    if block type is diamond ore:
        give 1 diamond to player

on player kills entity:
    if entity type is zombie:
        add 5 coins to player
```

Headers: `on …:`, `when …:`, `whenever …:`, `if …:` (a header **with a colon must be a
trigger** — a header that does not match any trigger is an error, never silently treated as
a sentence). 76 triggers ship with the plugin, including `player join/quit/death/respawn/
chat/command/move/teleport/damaged/kicked/advancement/sleeps/edits sign/…`, `block break`,
`block place`, `block explode`, `entity death/damaged/spawns/tamed/breeds/targets/shoots`,
`player kills entity`, `inventory click/open/close`, `player crafts/enchants/trades`,
`weather changes`, `time skip`, `world loads/unloads/saves`, `chunk loads/unloads`, and more.

### 4.2 Timers, commands, functions, data

```ar
every 10 minutes:
    tell everyone "Thanks for playing!"

command /heal:
    require permission "astra.heal"
    heal player
    tell player "You are healed!"

function reward(who, amount):
    give $amount diamonds to $who
    tell $who "Reward!"

data coins: number persistent = 0
data firstjoin: boolean persistent = false
```

### 4.3 Statements

- action calls: `give 5 diamonds to player`, `set time in world to 1000`, `cancel event`
- `if` / `else if` / `else`, with real conditions (`is day`, `has permission astra.vip`)
- `repeat 5 times`, `repeat 10 times as i`
- `wait 2 seconds` (delays inside a rule body)
- `call myAction(player)`, `return`, `stop`
- `set <key> of <who> to <value>`, `add … to <key>`, `remove … from <key>`

### 4.4 Filters

Filters are event properties checked *before* the body runs (cheap, and they describe the
event, not the script's own data):

```ar
on player kills entity:
    if killer is player and entity is zombie:
        ...
```

### 4.5 Built-in actions (47)

Messaging `tell`, `broadcast`, `broadcast-to-world`, `log`, `send-title`, `send-action-bar`,
`play-sound`; commands `console-command`, `player-command`, `kick`; items/state `give`,
`take`, `heal`, `damage`, `kill`, `feed`, `set-gamemode`, `give-experience`, `set-level`,
`clear-inventory`, `close-inventory`, `set-flight`, `set-speed`, `apply-effect`, `set-fire`,
`extinguish`; world `teleport`, `spawn-entity`, `set-block`, `break-block`, `lightning`,
`explode`, `drop-item`, `spawn-particle`, `set-time`, `set-weather`, `give-exp-orb`;
data `set-data`, `add-data`, `subtract-data`, `delete-data`, `save-data`;
economy `give-money`, `take-money`, `set-balance`; network `http-request`, `webhook`.

### 4.6 Built-in conditions (33) and expressions (13)

Conditions: `has-permission`, `is-op`, `is-sneaking`, `is-sprinting`, `is-flying`, `is-on-ground`,
`is-in-water`, `is-in-lava`, `is-inside-vehicle`, `is-alive`, `health-above`, `health-below`,
`gamemode-is`, `is-entity-type`, `is-day`, `is-night`, `is-raining`, `is-thundering`, `is-in-world`,
`block-is`, `is-canceled`, `data-is`, `data-above`, `data-below`, `data-exists`, `cooldown-ready`,
`start-cooldown`, `has-item`, `is-holding`, `inventory-empty`, `chance`, `script-enabled`, `has-money`.

Expressions: `random-number`, `random-decimal`, `distance-between`, `item-count`, `online-players`,
`world-time`, `entity-type-of`, `uuid-of`, `location-of`, `player-name`, `players-in-world`,
`balance-of`, `balance-formatted`.

---

## 5. The `.ar` language — natural language mode

Natural language is compiled **at load/reload**, exactly like the structured syntax; it is
never interpreted per event. Mixed mode (`natural-language.allow-mixed-mode`) lets one file
contain both.

```ar
When a player joins for the first time, welcome them and give them 5 diamonds.

Stop creepers from destroying blocks.

Every 10 minutes, tell everyone "Thanks for playing!"

Make /heal heal the player who runs it.

When it turns night, tell everyone "Good night".
```

Supported sentence patterns (19):

| Pattern | Example |
|---|---|
| first join | *When a player joins for the first time, welcome them and give them 5 diamonds* |
| join / quit / respawn / death | *When a player leaves, broadcast "Goodbye"* |
| kill mob | *When a player kills a zombie, give them 5 coins* |
| mob dies | *When a zombie dies, tell everyone "Zombie down"* |
| block break / block place | *When a player breaks a diamond ore, give them 1 diamond* |
| chat | *When a player chats, cancel the event if the message contains badword* |
| stop griefing / stop players | *Stop creepers from destroying blocks* |
| timer | *Every 5 minutes, tell everyone "Hello"* |
| command | *command heal, heal the player* |
| command with slash | *Make /heal heal the player who runs it* |
| time of day / sun | *When it turns night, tell everyone "Good night"*, *When the sun rises, …* |
| weather / time | *Make it rain*, *Set the time to night* |

Rules of the road:

* The word **"for the first time"** compiles to a persistent `firstjoin` flag plus a guard —
  it means what it says across restarts.
* Message text keeps your capitalisation and punctuation: `tell them "Welcome!"` stays
  `Welcome!`.
* `give them 5 coins` is translated to the data action (`add 5 coins to player`), because
  coins are stored data, not an item; `give them 5 diamonds` becomes the item action.
* A sentence that is not understood is **an error with suggestions**, never a silently
  ignored line.

---

## 6. Diagnostics

Every problem reports file, line, column, category, explanation and suggestion — in the
console log and in `/astra errors` alike:

```
AstraSyntax Error
File: welcome.ar
Line: 2

        give player 5 dimonds
                     ^^^^^^^

Unknown item: dimonds

The item name is not a Minecraft material or a known alias.

Did you mean:
    diamonds

Suggested:
        give player 5 diamonds
```

In game the same information is printed with the source excerpt and the caret under the
offending token, plus the ready-to-copy corrected line. `logging.include-script-source:
false` switches the console back to the compact `[ERROR] material: Unknown item: dimonds
(welcome.ar:2:18)` form.

The same diagnostics feed `/astra check`, `/astra errors` and `/astra explain`. A reload
that fails compilation **keeps the previous working version** of the script running.

---

## 7. Commands and permissions

| Command | What it does | Permission |
|---|---|---|
| `/astra reload [script]` | reload all scripts, or one | `astra.admin` or `astra.command.reload` |
| `/astra load <file>` | compile and start a script | `astra.command.load` |
| `/astra unload <script>` | stop and unload a script | `astra.command.unload` |
| `/astra scripts` (alias `list`) | list loaded scripts | `astra.command.scripts` |
| `/astra check [script]` | compile without applying | `astra.command.check` |
| `/astra info` | platform, storage, integrations, counts | `astra.command.info` |
| `/astra debug` | toggle developer diagnostics | `astra.command.debug` |
| `/astra explain <script>` | show what a script compiles to | `astra.command.explain` |
| `/astra trace <script\|off>` | record a rule-execution trace | `astra.command.trace` |
| `/astra performance [reset]` | profiling report (slow rules, averages) | `astra.command.performance` |
| `/astra errors` | recent diagnostics | `astra.command.errors` |

`/astra` itself needs `astra.command`; `astra.admin` grants everything. Aliases: `/ar`.
Every reply is rendered through `language.yml` (MiniMessage) — no command text is hard-coded.

---

## 8. Storage

Configured entirely by `storage.yml`. Default is SQLite at `data/astra.db`.

| Backend | When to use | Notes |
|---|---|---|
| `sqlite` (default) | single server | driver bundled in the jar as `astra/lib/sqlite-jdbc-3.47.1.0.jar` |
| `mysql` / `mariadb` | networks, shared data | driver bundled as `astra/lib/mysql-connector-j-9.1.0.jar`; `ssl` flag respected |
| `file` | no database at all | plain text store in `data/astra-data.txt` |

* The bundled drivers are **nested jars**, not shaded classes. On first start
  `io.astra.data.LibLoader` extracts them to `plugins/AstraSyntax/lib/` with SHA-256
  verification (only when missing or changed) and registers them through an isolated class
  loader, so AstraSyntax cannot collide with another plugin that bundles a different
  `sqlite-jdbc` build.
* Prepared statements everywhere; schema versioning (`SCHEMA_VERSION = 1`) with migrations.
* Autosave runs **async** (`autosave.interval`, default `5m`), player data is preloaded on
  join and flushed on quit, and no database call ever happens on the tick thread.
* Passwords are never logged: connection descriptions are credential-free and log lines are
  passed through the redactor.

---

## 9. Security model

`security.yml` is enforced by one gate (`io.astra.security.SecurityGate`) that every
sensitive action asks **before** it does anything. Because natural language compiles to the
same actions, a sentence cannot do more than a structured rule could.

| Capability | Default | Enforced at |
|---|---|---|
| console commands | `scripts.allow-console-commands: true` | before `Bukkit.dispatchCommand` |
| file access | `false` | before every file operation, with path traversal rejection |
| HTTP requests | `false` (+ `http.allowed-domains: []`) | before the request is scheduled |
| webhooks | `false` (+ `webhooks.allowed-domains: []`) | before the request is scheduled |
| remote package install | `false` | in the package manager |
| trusted modules | `modules.require-trusted-modules: true` | before a module jar is loaded |
| loop iterations | `10000` | inside `repeat` |
| tasks per script | `1000` | on task registration |
| HTTP response size | `2MB` | while reading the response (aborted, not buffered) |

Feature switches in `config.yml` are enforced a second time at load: `features.http: false`
refuses any script that uses `http-request`, with a diagnostic naming the exact key.

---

## 10. Folia support

`plugin.yml` declares `folia-supported: true`, and the plugin never calls a
scheduler directly — everything goes through `io.astra.platform.SchedulerService`, which has
two implementations chosen at startup by `PlatformDetector`:

* **`FoliaSchedulerService`** — global tasks, regionised location tasks
  (`runAtLocation*`), entity tasks (`runOnEntity*`) and async tasks, each mapped to the
  right Folia scheduler.
* **`BukkitSchedulerService`** — the same API on Paper/Purpur/Spigot/Leaf.

Every script-owned task is registered in a `TaskRegistry` keyed by script name and is
cancelled when the script unloads, when it fails to reload, or on shutdown — that is what
keeps hot reload leak-free. Blocking work (storage, HTTP) is always dispatched async; no
database or network call runs on a tick thread.

**Honest status:** the scheduler abstraction, cancellation and the reflection-based platform
detection are implemented and unit-tested as far as an offline sandbox allows, but the
runtime behaviour has **not** been exercised on a live Folia server here (see
[§17](#17-verification-performed)).

---

## 11. Build with Gradle

```bash
gradle clean build            # compiles, runs the 46 tests, writes build/libs/AstraSyntax-1.21.11-26.2.jar
gradle test                   # tests only
gradle processResources       # regenerates plugin.yml + astra/lib/*.jar staging
```

Requirements: Gradle 8.10+ with a Java 21 toolchain (Gradle downloads the toolchain if the
JDK is missing) and network access to the repositories declared in `build.gradle`.

What the build does:

* compiles `src/main/java` with `--release 21` and UTF-8;
* expands `${project.version}` in `plugin.yml` (`filesMatching('plugin.yml')`);
* copies the other eight YAMLs and `examples/*.ar` **byte-identical** into the jar;
* stages `sqlite-jdbc` and `mysql-connector-j` into `astra/lib/` via the `stageDriverJars`
  task (no shadow plugin — see [Dependencies](#14-dependencies));
* writes `Implementation-Title/Version/Vendor` and `Astra-Target: 1.21.11-26.2` to the
  manifest.

Artifact: **`build/libs/AstraSyntax-1.21.11-26.2.jar`**.

## 12. Build with Maven

```bash
mvn -B clean package          # compiles, runs the 46 tests, writes target/AstraSyntax-1.21.11-26.2.jar
mvn -B test                   # tests only
mvn -B dependency:copy-dependencies -DincludeScope=runtime   # fetch the drivers by hand if needed
```

Requirements: Maven 3.9+ and JDK 21. The repositories are declared in `pom.xml`
(`hub.spigotmc.org`, `repo.md-5.net`, PaperMC for anyone who switches the compile API).

The Maven build mirrors Gradle exactly: `maven-compiler-plugin` (release 21),
`maven-dependency-plugin` copies the two JDBC jars into `astra/lib/` during
`process-classes`, `maven-jar-plugin` adds the same manifest entries, `surefire` runs the
JUnit 5 suite.

Artifact: **`target/AstraSyntax-1.21.11-26.2.jar`**.

## 13. Offline / fallback build (used for verification here)

The environment this project was developed in could not reach Maven Central, the Gradle
distribution or the plugin portal, so the artifacts shipped in `dist/` were built with the
same steps, performed by script instead of by Maven/Gradle:

```bash
tools/build-jar.sh                 # -> dist/AstraSyntax-1.21.11-26.2.jar
tools/run-tests.sh                 # compiles and runs the JUnit suite
```

`tools/build-jar.sh` compiles `src/main/java` with the Eclipse batch compiler in `tools/`
(`-source 21 -target 21`, the same release Maven and Gradle use), copies
`src/main/resources` expanding `${project.version}`, stages the two driver jars into
`astra/lib/` and writes a real `META-INF/MANIFEST.MF`. It is deliberately dependency-free so
the build can always be reproduced; **Maven and Gradle remain the primary, documented build
paths** and their scripts are unchanged by it.

---

## 14. Dependencies

| Dependency | Version | Scope | Why | Licence |
|---|---|---|---|---|
| `org.spigotmc:spigot-api` | 1.21-R0.1-SNAPSHOT | provided (compile only) | lowest common API of all five supported platforms; a fork-only class cannot compile by accident | GPLv3 (Spigot) — used as a compile-time API only |
| `org.xerial:sqlite-jdbc` | 3.47.1.0 | runtime, bundled in `astra/lib/` | default storage backend from `storage.yml` | Apache-2.0 |
| `com.mysql:mysql-connector-j` | 9.1.0 | runtime, bundled in `astra/lib/` | only loaded when `storage.yml` selects MySQL/MariaDB | GPLv2 + FOSS exception |
| `org.junit.jupiter:junit-jupiter` | 5.11.4 | test | the test suite | EPL-2.0 |
| `org.junit.platform:junit-platform-launcher` | 1.11.4 | test | launcher used by the runner | EPL-2.0 |

Deliberately **not** used:

* **No shading.** The drivers stay whole inside `astra/lib/` and are loaded isolated at
  runtime (see [§8](#8-storage)); relocating a JDBC driver with a shade plugin is how
  class-path surprises start.
* **No Guava, no Adventure, no NBT library** at compile time — the server API plus the JDK
  is enough, and fewer hard dependencies means fewer server-side clashes.
* **Vault, PlaceholderAPI, Citizens, WorldGuard and Redis are optional** and are reached
  purely by reflection, driven by `integrations.yml` (`enabled` + `auto`). None of them is
  needed to start, and none is bundled.

---

## 15. Tests and how to run them

```
mvn -B test          # Maven
gradle test          # Gradle
tools/run-tests.sh   # offline fallback
```

The suite (46 tests, JUnit 5) covers:

| Test | What it locks down |
|---|---|
| `CompilerSmokeTest` | the five supplied example scripts parse, compile and produce the expected rules |
| `CompilerSmokeTest.typoSuggestsDiamonds` | the `dimonds` → `diamonds` diagnostic requirement |
| `NaturalLanguageCoverageTest` | 11 natural-language sentences produce real rules with real bodies, messages keep their case, both authoring modes produce the same runtime rule, unsupported sentences are reported |
| `FeatureGateTest` | `config.yml` feature switches are recorded by the compiler and refused by the loader with an actionable diagnostic |
| `FeatureFlagsTest` | all 17 `features.*` keys map to the matching flag |
| `EconomyTest` | affordability, deposit/withdraw maths behind `set`, "no provider" never looks like a zero balance |
| `HttpServiceTest` | security gate before the request, response-size cap, timeouts, webhook JSON, credential redaction |
| `LibLoaderTest` | nested driver extraction with SHA-256 reuse, unsafe entry names rejected, and a real SQLite connection opened through the isolated driver |
| `DiagnosticRendererTest` | the rendered diagnostic carries file, line, column, source excerpt, caret at the right column, explanation, suggestion and the corrected line; suggestions can be suppressed |
| `PackagingTest` | `plugin.yml` declares the real main class, `api-version`, `folia-supported` and every documented subcommand; the nine configs and five examples are on the class path under the exact names the loader reads; security defaults stay opt-in |

---

## 16. JAR output locations and what is inside

| Build | Artifact path |
|---|---|
| Maven | `target/AstraSyntax-1.21.11-26.2.jar` |
| Gradle | `build/libs/AstraSyntax-1.21.11-26.2.jar` |
| Offline fallback (this checkout) | `dist/AstraSyntax-1.21.11-26.2.jar` |

Inside the jar:

```
plugin.yml                     name/version/main/commands/permissions, folia-supported: true
config.yml … logging.yml       the nine supplied configuration files (verbatim)
examples/01-welcome.ar …       the five supplied example scripts
astra/lib/sqlite-jdbc-3.47.1.0.jar
astra/lib/mysql-connector-j-9.1.0.jar
io/astra/**/*.class            288 classes
META-INF/MANIFEST.MF           Implementation-Title/Version/Vendor, Astra-Target
```

Install by copying that single jar into `plugins/`. Nothing else has to be installed for
the defaults in `storage.yml`, `integrations.yml` and `security.yml` to work.

---

## 17. Verification performed

Everything below was executed in this checkout. Commands are given so they can be repeated.

| Check | Result |
|---|---|
| Main sources compile (release 21) | **exit 0, 0 errors** — `tools/build-jar.sh`, and the same file set with the Eclipse batch compiler |
| Test sources compile | **exit 0, 0 errors** |
| Test suite | **46 of 46 pass** — `tools/run-tests.sh` |
| Natural-language audit | 17 of 19 probe sentences compile into rules with real bodies; the 2 unsupported ones produce a diagnostic with suggestions (by design) |
| JAR built | `dist/AstraSyntax-1.21.11-26.2.jar`, 306 entries (288 classes), 17,226,018 bytes, `sha256 dd462a07aa560ebf29d12dc168ca8f39e66727f940b67693879c3ca020e19fa3`, and the packaging is deterministic (two runs produce the same bytes) — rebuild any time with `tools/build-jar.sh` |
| JAR integrity | `zipfile.testzip()` → no corrupt entry; main class, `LibLoader`, resources and both nested drivers present |
| Reproducible packaging | `tools/build-jar.sh` writes fixed timestamps and sorted entries: two consecutive builds produced the same sha256 |
| `plugin.yml` and the nine configs | parsed with a real YAML parser: `name`, `version` (expanded from `${project.version}`), `main: io.astra.plugin.AstraPlugin`, `api-version: 1.21`, `folia-supported: true`, 13 permissions and 11 documented subcommands; the configs inside the jar are byte-identical to the supplied files (SHA-256 compared) |
| Driver loading | real SQLite connection opened through `LibLoader`'s extracted jar + `DriverShim` (unit test) |
| Security enforcement | HTTP/domain/size/timeout denials covered by unit tests against a local HTTP server |
| Config paths | the field names read by `ConfigManager` are taken from the nine supplied files; no key was renamed or invented |

**What was *not* verified here, and is not claimed as verified:** running the plugin on a
live Minecraft server. This sandbox has no server jar, no network access to Maven Central
and no Minecraft server runtime, so the following are *implemented and compile-verified but
not behaviourally tested*: Folia region scheduling on a real Folia server, listener
registration, command registration, inventory/item APIs, Vault/PlaceholderAPI/Citizens/
WorldGuard reflection, and MySQL storage against a real database. Treat the first run on a
test server as part of your acceptance, and report anything that misbehaves.

---

## 18. Delivered vs. roadmap

**Delivered and covered by tests or the compiler** (phases 1–4, plus parts of 5–7):

* the `.ar` pipeline: lexer → parser → AST → compiler → IR → runtime, for both authoring modes
* 76 triggers, 47 actions, 33 conditions, 13 expressions, 251 vocabulary patterns
* hot reload with previous-version fallback, diagnostics, profiling, tracing, `/astra` tooling
* storage: SQLite/MySQL/File with migrations, autosave, caching, prepared statements
* security gate on every sensitive path, including natural language and outbound network
* feature switches from `config.yml` enforced at load time
* economy vocabulary over Vault (reflective, optional)
* HTTP requests and webhooks (async, domain-allowlisted, size-capped, redacted)
* modules (`AstraModule` API, isolated class loaders, failure isolation) and packages
  (manifests, dependency ordering, load/unload)
* the nine supplied configs, the five supplied examples, and Folia-safe scheduling

**Implemented as data/behaviour but needing a live server to confirm:** everything listed
in the *not verified* paragraph of [§17](#17-verification-performed).

**Roadmap — not implemented yet, and deliberately not claimed as working:**

| Item | State |
|---|---|
| Menu/GUI authoring (`features.gui`) | no `menu` statement exists in the language yet; the `RuleKind.MENU` slot and the feature switch are reserved |
| Custom items (`features.custom-items`) | reserved; today use `give` with vanilla materials |
| Scoreboards, bossbars, holograms (`scoreboards`, `bossbars`, `holograms`) | reserved; no vocabulary yet |
| Quests, regions, NPCs (`quests`, `regions`, `npc`) | reserved; WorldGuard/Citizens presence is *detected*, nothing is bound to them yet |
| Recipes (`recipes`) | reserved |
| Custom mobs and bosses (`custom-mobs`, `bosses`) | `spawn-entity`, `damage`, `kill` cover vanilla mobs; no custom entity definitions |
| Remote package installation | deliberately unimplemented; `security.yml` allows it to be enabled, and the manager reports it as unavailable rather than pretending |
| `.sk` import | optional later feature; `.sk` is never the native format |

Anything marked *reserved* is a `config.yml` switch with no runtime behind it — the honest
position is that the switch exists, the feature does not. AstraSyntax refuses a script that
*needs* a disabled feature, so enabling one of these switches today cannot silently change
behaviour.

---

## 19. Project layout

```
src/main/java/io/astra/
  plugin/       AstraPlugin (bootstrap), PlayerDataListener
  command/      AstraCommand (/astra + tab completion)
  config/       ConfigManager, ConfigFile, ConfigUpdater, AstraSettings, FeatureFlags, ConfigIssue
  language/     lexer, parser (AstraParser, vocabulary), ast, nl (NaturalLanguageCompiler),
                compiler (AST -> IR), diagnostics, docs
  runtime/      Value/ValueMath/ExecContext/Arguments/Targets/Registries,
                action|condition|expression|event|vocab|scheduler packages,
                script/ (Script, ScriptManager, RuleExecutor, compiled IR, DynamicCommands),
                builtin/ (BuiltinActions, BuiltinConditions, BuiltinEvents, BuiltinExpressions,
                          BuiltinEconomy, BuiltinNetwork, MaterialTable),
                economy/, net/ (HttpService)
  data/         Storage, SqlStorage, FileStorage, ValueCodec, DataStoreImpl, LibLoader
  security/     SecurityPolicy, SecurityGate, SecurityGateImpl
  platform/     ServerPlatform, PlatformDetector, SchedulerService (+ Folia/Bukkit), TextService
  profiler/     Profiler, SimpleProfiler, RuleStats, TraceSession
  module/       AstraModule (API), ModuleManager
  package_/     PackageManager
  integration/  IntegrationManager (Vault/PlaceholderAPI/Citizens/WorldGuard/Redis, reflective)
  logging/      AstraLogger, LogLevel, LogRedactor
  util/         Strings, Durations, Reflect, FileUtil, Hash
src/main/resources/   plugin.yml + the nine configs + examples/*.ar
src/test/java/        CompilerSmokeTest, NaturalLanguageCoverageTest, FeatureGateTest,
                      FeatureFlagsTest, EconomyTest, HttpServiceTest, LibLoaderTest
tools/                build-jar.sh, run-tests.sh, Eclipse compiler, sandbox-only test shims
```

Compared with the original project skeleton, the deliberate renames are: `bootstrap` →
`plugin` (one bootstrap class, no empty packages), `natural` → `language/nl` (it is part of
the language pipeline), `storage` → `data` (storage *and* the data model), and the public
extension surface lives in `module/AstraModule` plus `runtime/` rather than a wrapper `api`
package. No package was created without a responsibility.

---

## 20. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| *"Unknown item: dimonds (did you mean diamonds?)"* | typo in a material name; the suggestion is exact |
| *"Feature 'economy' is disabled in config.yml"* | the script uses `give-money`/`take-money`/`set-balance` while `features.economy: false`; enable it or rewrite the rule |
| *"No economy provider is available"* | Vault (or an economy plugin behind it) is missing; install Vault + e.g. EssentialsX, then `/astra reload` |
| *"Storage is unavailable"* | the database could not be opened; AstraSyntax falls back to reporting it in `/astra info`. Check the path in `storage.yml`, or set `storage.type: file` |
| *"http.allowed-domains is empty"* | by design: add the domain to `security.yml` **and** set `scripts.allow-http-requests: true` (plus `features.http: true`) |
| *"I could not understand this rule"* | the natural-language sentence has no matching pattern; `/astra errors` lists example sentences to copy |
| A reload did not change anything | the new version failed to compile — the previous one is still running by design; `/astra check <script>` shows why |
| Plugin does not start on Folia | check that you are on Folia 1.21+ and that the jar is the one from `dist/`/`target/`/`build/libs/` (it declares `folia-supported: true`) |
| `plugins/AstraSyntax/lib/` grows | it holds the extracted JDBC drivers (~16 MB); files are hash-checked and replaced only when the packaged jar changes |

Log files live in `plugins/AstraSyntax/logs/` (separate error file when
`logging.separate-error-file` is enabled), with stack traces and script source included
according to `logging.yml`.

---

## 21. Development notes

* Everything is compiled with `--release 21`; `javac`, Maven and Gradle all produce the same
  bytecode level.
* The `tools/sandbox/guava-shim/` sources exist **only** so the test suite can run in an
  environment without real Guava; they are never packaged into the plugin jar (see
  `tools/build-jar.sh`, which never copies `tools/`).
* Tests run against a fake-free code path where possible: the runtime depends on the
  `RuntimeServices` interface, so the compiler, executor, economy and HTTP layers are
  testable without a server.
* The Eclipse batch compiler in `tools/` is the offline fallback compiler, not a
  requirement of the project. `pom.xml`, `build.gradle` and `settings.gradle` are the
  authoritative build definitions.
* `AstraSyntax.7z` in the repository root is the originally supplied project archive and is
  kept untouched for reference.
