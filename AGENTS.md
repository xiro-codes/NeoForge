# AGENTS.md

NeoForge is a modern JavaFX graphical user interface (`forge-gui-neo`) built on top of the [Card-Forge/forge](https://github.com/Card-Forge/forge) Magic: The Gathering rules engine. This repository does **not** contain the upstream Forge engine; it holds the `forge-gui-neo` Maven module and platform packaging/testing scripts (`linux/`, `mac/`, `.github/workflows/`).

---

## 1. Golden Rule: Upstream Forge Isolation

- **Never modify upstream Forge files.** `forge-gui-neo` consumes `forge-core`, `forge-game`, `forge-ai`, `forge-gui`, and `forge-gui-mobile-dev`. The only change ever made to an upstream Forge checkout during a build is inserting `<module>forge-gui-neo</module>` right after `<module>forge-gui-desktop</module>` in Forge's root `pom.xml`.
- **Never depend on `forge-gui-desktop` (Swing).**
- **Accessing `protected` or package-private Forge members:** Do not patch upstream Forge or use fragile reflection. Place a helper class inside the matching upstream package under `forge-gui-neo/src/main/java/` so it compiles against the same runtime package and fails loudly at compile time if upstream renames a field:
  - `forge.deck.NeoDeckUrlLoader`
  - `forge.game.cost.NeoCostRelease`
  - `forge.gamemodes.match.input.NeoPaymentPeek`
- **Shadow copies (`COPIA SOMBRA`):** To hook into Forge's libGDX Adventure mode without modifying upstream, `forge-gui-neo` includes shadow copies of `forge.adventure.scene.DuelScene` and `forge.adventure.scene.DeckEditScene` (loaded first on the classpath).
  - `forge.neo.adventure.AdventureCheck` verifies the SHA-256 hashes of the upstream originals (`DUEL_SCENE_SHA256`, `DECK_EDIT_SCENE_SHA256`), ensures the shadow copies are loaded from `forge-gui-neo`, and checks that the `NEOFORGE` hook blocks are present. If `FORGE_REF` is bumped and upstream changed those scenes, re-copy the upstream file, re-apply the `NEOFORGE` blocks, and update the SHA-256 constants in `AdventureCheck.java`.

---

## 2. Build, Run, and Packaging Commands

### Requirements
- **JDK 17** (Eclipse Temurin 17)
- **Maven 3.9+**
- **Upstream Forge commit (`FORGE_REF`)**: Pinned in `.github/workflows/linux.yml` and `.github/workflows/macos.yml` (must always be updated together).

### Building Inside a Forge Reactor
Because `forge-gui-neo` inherits from the `forge:forge` parent POM, Maven commands must run from an upstream Forge checkout that contains `forge-gui-neo/` (copied or symlinked) and has `<module>forge-gui-neo</module>` in its root `pom.xml`:

```bash
export MAVEN_OPTS="-Dfile.encoding=UTF-8 -Xmx2g"

# Standard development build (copies runtime dependencies to forge-gui-neo/target/lib)
mvn -B package -DskipTests -pl forge-gui-neo -am

# Fast CI / packaging build (skips checkstyle, mobile fat-jar assembly, and Windows launch4j)
mvn -B -ntp install -DskipTests -Dcheckstyle.skip=true -Dassembly.skipAssembly=true -DskipLaunch4j=true -pl forge-gui-neo -am
```

- **Linux ARM64 (`aarch64`) note:** Pass `-Djavafx.version=21.0.1` when building on Linux ARM64 (Maven Central only publishes `linux-aarch64` JavaFX 21 artifacts up to `21.0.1`, whereas `pom.xml` defaults to `21.0.5`).

### Running From `forge-gui-neo/`
Always run with `forge-gui-neo` as the working directory (so default `forge.assetsDir=../forge-gui/` resolves `../forge-gui/res`) and pass the required `--add-opens` flags:

```bash
cd /path/to/forge/forge-gui-neo

java -Dfile.encoding=UTF-8 -Xmx2g \
  -Dneo.dataDir="$HOME/neoforge-data" \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
  --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  -cp "target/classes:target/lib/*" forge.neo.NeoMain ui
```
*(Use `;` instead of `:` in `-cp` on Windows).*

### Packaging & End-to-End Package Verification
`linux/empaquetar.sh` and `mac/empaquetar.sh` automate fetching Forge at `FORGE_REF`, injecting `forge-gui-neo`, compiling, bundling with `jpackage`, zipping `res/cardsfolder` into `cardsfolder.zip` (and deleting the loose `.txt` files so cards are not loaded twice), and producing the distributable archive:

```bash
# Linux (.tar.gz in _linux/out/)
FORGE_REF=3e1fa4ed132edd5a782ac2ba044d938c2b08ebc4 bash linux/empaquetar.sh
bash linux/probar.sh

# macOS (.dmg in _mac/out/)
FORGE_REF=3e1fa4ed132edd5a782ac2ba044d938c2b08ebc4 bash mac/empaquetar.sh
bash mac/probar.sh
```

---

## 3. Testing & Self-Check Architecture (`*Check`)

NeoForge does **not** use JUnit in `src/test/`. Instead, all automated verification lives in `*Check.java` classes inside `src/main/java/forge/neo/**` and is dispatched via `forge.neo.NeoMain <subcommand>` (or standalone `main` methods like `forge.neo.adventure.AdventureCheck`).

### Running Checks
Replace `ui` with the check subcommand when invoking `forge.neo.NeoMain`:

```bash
java -Dfile.encoding=UTF-8 -Xmx2g \
  --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED \
  --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  -cp "target/classes:target/lib/*" forge.neo.NeoMain <check-command>
```

### Key Check Subcommands in `NeoMain`
1. **Fast / Pre-Engine Checks (run before `NeoLock`, `NeoLog`, and `FModel.initialize` — take <1s):**
   - `portablecheck` (`PortableCheck`) — verifies `NeoPortable` profile generation and non-ASCII path handling.
   - `shortcutcheck` (`ShortcutCheck`) — keyboard shortcuts defaults, styles, and persistence.
   - `damagecheck` (`DamageCheck`) — combat damage assignment rules (trample, blocker ordering).
   - `imagecheck` (`ImageScaleCheck`) — Lanczos card rescaling.
   - `discordcheck` (`DiscordCheck`) — Discord Rich Presence formatting.
   - `updatecheck` (`UpdateCheck`) — itch.io version checking, offline resilience, and Android bytecode compatibility.
   - `audiocheck` (`AudioCheck`) — pure-Java MP3 decoding via JLayer (`JavaSound`).
   - `backupcheck` (`BackupCheck`) — data export/import zip round-tripping.
2. **Pre-Engine Checks Requiring `NeoGuiBase` Only:**
   - `streamercheck` (`StreamerCheck`) — IP masking in UI and chat.
   - `selfupdatecheck` (`SelfUpdateCheck`) — in-app update and rollback on a mock installation.
3. **Engine, Deck, and Localization Checks (load the 33,000+ card database):**
   - `deckcheck` (`DeckRulesCheck`) — comprehensive deck builder rules, formats, translations, search indexing, and deck generation.
     - **Gotcha:** When running `deckcheck` in CI or on an English OS without saved settings, pass `-Duser.language=es -Duser.country=ES` (or `JAVA_TOOL_OPTIONS="-Duser.language=es -Duser.country=ES"`) if verifying translated illegality messages as done in `linux/probar.sh` and `mac/probar.sh`.
   - `librarycheck` (`LibraryCheck`), `deckgencheck` (`FastDeckGenCheck`), `themedeckcheck` (`ThemeDeckCheck`), `banlistcheck` (`BanListCheck`), `commandercheck` (`CommanderCheck`), `artlesscheck` (`ArtlessCheck`).
   - `langcheck` (`LanguageCheck`) — verifies Forge's 10 languages plus NeoForge's `ar-MA` (Arabic RTL) and placeholder parity.
   - `textcheck` (`TextCheck`) — verifies `EngineText` target prompt translation composition.
   - `menucheck` (`MenuLayoutCheck`), `lookcheck` (`LookCheck`), `soundcheck` (`SoundCheck`), `destroysoundcheck` (`DestroySoundCheck`).
4. **Match, AI, and Rules Checks (headless or simulated table):**
   - `play` (human auto-play vs AI) and `watch` (AI vs AI) with `--deck=<name> --opponents=<N> --timeout=<S> -v`.
   - `gamescheck` (`GamesCheck`) — full 4-player Commander matches on the real JavaFX table (`FxUiDispatcher`).
   - `leakcheck` (`LeakCheck`) — verifies finished matches release `TableScreen`/`Game` memory references (`LkiPruner`, `NeoCostRelease`).
   - Targeted match checks: `manacheck`, `combatcheck`, `attackcheck`, `blockcheck`, `cantplaycheck`, `stackblockcheck`, `filtercheck`, `actionscheck`, `devotioncheck`, `xtargetcheck`, `improvisecheck`, `aistallcheck`, `sacoutletcheck`, `anytypecheck`, `extracombatcheck`, `planechasecheck`, `archenemycheck`, `twoheadedcheck`, `twopilescheck`, `mulligancheck`, `phasestopcheck`, `attachcheck`, `topcheck`, `plotcheck`, `mayhemcheck`, `harmonizecheck`, `exilecastcheck`, `commandercolorcheck`, `optionalpaycheck`, `handabilitycheck`, `loglinkscheck`, `handlogcheck`, `devmodecheck`, `affectedcheck`, `outcheck`, `foreachcheck`, `friendlycheck`, `bo3check`, `replaycheck`, `askingcardcheck`, `triggerdetailcheck`, `wardcheck`, `tapcheck`, `moxchaincheck`, `abilitygroupcheck`, `bannercheck`, `readingcheck`, `macrocheck`, `yieldcheck`, `relicpasscheck`, `phaseguardcheck`, `floatingcheck`, `affordablexcheck`, `copycheck`.
5. **Game Mode & Multiplayer Checks:**
   - **Quest:** `questcheck`, `questdraftcheck`, `dailypackcheck`, `colosseumcheck`, `questunlockcheck`, `questcupcheck`, `questprefscheck`, `packcheck`, `questmake` (sandbox generator).
   - **Ascent (Roguelike mode):** `ascentcheck` (runs `AscentProbe` + `AscentCheck`), `ascentdraftcheck`, `seedcheck`, `reliccheck` (`--solo=<relic>`).
   - **Rogue Commander:** `roguecheck` (`RogueCheck`).
   - **Draft / Sealed / Tournament / Tutorial:** `draftcheck`, `cmddraftcheck`, `sealedcheck`, `tournamentcheck`, `tutorialcheck`.
   - **Network:** `lobbycheck` (`LobbyCheck` spawns a `lobbyguest` child process over localhost), `neteventcheck` (`NetEventCheck` for net draft/sealed), `netcheck` (`NetCheck`).
6. **UI Snapshot & Headless Verification Flags (`ui` mode):**
   - `ui --snapshot=/path/to/out.png --wait=20` renders the UI and saves a PNG screenshot.
   - `ui --live --auto --snapshot=/path/to/game.png --wait=90` starts a live match with auto-pilot and captures the board.
   - `-Dneo.win=1920x1080` forces logical window dimensions for deterministic layout testing on headless/virtual displays (`xvfb-run`).

---

## 4. Architecture & Control Flow

### Startup Sequence (`NeoMain` -> `NeoBoot` -> `NeoApp`)
The startup order in `NeoMain.main()` is strictly constrained by Forge's static initializers:
1. **`applySystemProperties(args)`**: Native `jpackage` launchers pass CLI flags *after* the main class name, so the JVM treats `-Dfoo=bar` as program arguments. `NeoMain` manually parses any `-D...` in `args` and sets `System.setProperty(...)` first.
2. **`NeoPortable.apply()`**: Must run **before any class touches `ForgeConstants`**. When `-Dneo.dataDir=<dir>` is set, `NeoPortable` writes `forge.profile.properties` in the assets directory pointing user/cache/deck paths to `dataDir`. When `-Dneo.dataDir` is *not* set (development mode), `NeoPortable.dropStrayProfile()` deletes any leftover `forge.profile.properties` written by a previous portable test so development runs don't silently lose their `%APPDATA%/Forge` or `~/.forge` profile.
3. **`NeoFonts.beforeJavaFx()` & `PrismGuard.beforeJavaFx()`**: Configures Prism font rendering (under Wine/Proton) and raises JavaFX `prism.maxvram` before JavaFX initializes.
4. **Fast Checks & `adventure` Subprocess Branch**: Handled before single-instance locking so child processes (`adventure`) or fast checks don't collide with a running game instance.
5. **`NeoLock.acquire()`**: Single-instance file channel lock in `ui` mode. If another instance is already open and the user double-clicked a `.frp` replay file, `NeoLock.handOver(replay)` passes the file to the running instance and exits before opening `NeoLog`.
6. **`NeoLog.start()` & `NeoBackup.applyPending(...)`**: Starts log redirection (`neo.log`) and applies any pending data import zip before the engine reads preferences.
7. **`GuiBase.setInterface(new NeoGuiBase(dispatcher))`**: Registers `NeoGuiBase` with `FxUiDispatcher` (in `ui` and `gamescheck` modes) or `ConsoleUiDispatcher` (headless modes).
8. **Engine Loading (`NeoBoot`)**:
   - In **headless** modes, `NeoBoot.loadEngine(null)` runs synchronously on the main thread.
   - In **`ui`** mode, `NeoBoot.prepareTexts()` initializes `Localizer` and `Lang` *before* `Application.launch(NeoApp.class, args)` (because `NeoApp` fields reference `NeoFormat` -> `forge.game.GameType`, whose static initializer calls `Localizer.getInstance()`). `NeoApp` then shows `LoadingScreen` immediately and runs `NeoBoot.loadEngine(bar)` on a background thread.

### Threading Model (`NeoGuiBase`, `NeoMatchUI`, `TableBinder`)
- **UI Thread (`FThreads` / `UiDispatcher`)**: Forge asserts `FThreads.assertExecutedByEdt()`. `NeoGuiBase.isGuiThread()` and `invokeInEdt*` delegate to `UiDispatcher` (`FxUiDispatcher` wraps `Platform.isFxApplicationThread()` / `Platform.runLater()`; `ConsoleUiDispatcher` uses a single-thread executor).
- **Never Block the Engine Thread or UI Thread on Each Other**:
  - `NeoMatchUI` (extends `NetworkGuiGame` so multiplayer delta sync packets via `applyDelta` work automatically) uses a dedicated single-thread `responder` (`neo-responder`) to send player decisions back to the engine.
  - `TableBinder` bridges `GameView` updates to `TableScreen`. It **only reads view objects** (`GameView`, `PlayerView`, `CardView`) — never live mutable engine objects — and coalesces multiple engine events into a single JavaFX pulse via an `AtomicBoolean pending` flag.
  - **Bo3 PlayerView Identity Gotcha (`TableBinder.setGameView`)**: In Best-of-3 matches, each game creates a new `Game` instance that resets player IDs starting from `0`. Because Forge's `TrackableObject.equals()` compares only `id + class`, a `PlayerView` from Game 1 compares `.equals()` to a new `PlayerView` in Game 2. Be careful not to retain stale `PlayerView` references across games.

### Adventure Mode Dual-Process Bridge (`forge.neo.adventure`)
Forge's Adventure mode uses libGDX and registers its own `GuiMobile` in `GuiBase`. Because a single JVM cannot share `GuiBase` between NeoForge and libGDX:
- `AdventureLauncher` spawns a second process running `NeoMain adventure` -> `AdventureNeoMain`.
- **Important (`NeoOs.relaunch`)**: When launched from a `jpackage` binary, `jpackage` sets `_JPACKAGE_LAUNCHER` in the environment. If inherited by a child process, the native launcher misinterprets arguments as raw JVM flags and fails with `Could not find or load main class adventure`. Always use `NeoOs.relaunch(...)` when spawning the NeoForge binary, and pass system properties via the `NEO_ADVENTURE_PROPS` environment variable for `AdventureNeoMain`.
- Inside `AdventureNeoMain`, JavaFX is started alongside libGDX (`Platform.startup(...)`, `Platform.setImplicitExit(false)`). When the player enters a duel or opens the deck editor on the libGDX world map, the shadow copies `DuelScene` and `DeckEditScene` hand control to `NeoDuelBridge` and `NeoDeckBridge`, which open NeoForge's JavaFX `TableScreen` or `DeckBuilderScreen` directly over the Adventure window.

### Audio on Linux / SteamOS (`forge.neo.platform.JavaSound`)
JavaFX Media on Linux requires system `libav`/`ffmpeg` packages that are absent on SteamOS and minimal Linux distros. On Linux, MP3 sound effects and music are decoded in pure Java via `com.badlogicgames.jlayer:jlayer` (`JavaSound`, verified by `audiocheck`).

---

## 5. Localization & Resource Conventions

- **11 Supported Languages**: Forge's 10 built-in languages (`en-US`, `es-ES`, `de-DE`, `fr-FR`, `it-IT`, `pt-BR`, `ru-RU`, `ja-JP`, `ko-KR`, `zh-CN`) plus Arabic (`ar-MA`, right-to-left).
  - Why `ar-MA` instead of `ar-SA`? Java's `Locale` for `ar-MA` formats numbers with Western Arabic digits (`20`) matching Magic card costs/life totals rather than Eastern Arabic numerals (`٢٠`).
  - Engine strings for `ar-MA` live in `src/main/resources/ar-MA.properties` at the classpath root so Forge's `Localizer` finds them without writing into Forge's `res/languages/` directory.
- **NeoForge UI Strings (`NeoText`)**:
  - Stored in `src/main/resources/forge/neo/lang/neo-<lang>.properties` (UTF-8).
  - Read via `NeoText.get("key", args...)`. Note: `NeoText.get` uses direct `{0}`, `{1}` replacement rather than `java.text.MessageFormat` so single quotes/apostrophes in card names (`Wak'dern`) are never stripped.
  - **Every new user-facing UI key must be added to all 11 `neo-*.properties` files.** Several checks (`AdventureCheck`, `UpdateCheck`, `DeckRulesCheck`, `QuestPrefsCheck`, `LanguageCheck`) assert that required keys exist and are non-blank across all language files.
- **Maven Resource Filtering Gotcha (`pom.xml`)**:
  - **Only** `forge/neo/build.properties` has Maven resource filtering enabled (`<filtering>true</filtering>`). All other resources (`neo-*.properties`, relic scripts, etc.) contain `${...}` or braces that Maven would corrupt if filtered.
- **Single Source of Truth for Version**:
  - `<neo.version>` in `forge-gui-neo/pom.xml` is the **only** place the NeoForge version is defined. `build.properties`, `linux/empaquetar.sh`, and `mac/empaquetar.sh` all read it from `pom.xml`.

---

## 6. Code Style & Cross-Platform (Android) Constraints

- **Language & Comments**: Code identifiers (classes, methods, variables) are primarily in English, while Javadocs, inline explanations, test assertion messages, and commit messages are written in Spanish.
- **Android API 26 Compatibility for Shared Pure-Java Classes**:
  - The companion NeoForge Android app consumes `forge-gui-neo`'s JAR directly for pure-Java logic (such as `ItchVersion`, `PatchNotes`, `MenuLayout`, `NeoLanguage.installProvided`, `NeoDeckUrlLoader`, `DeckFilter`, `ColourFilter`, `DamageRules`, `GameStats`, `SignatureMotion`, `NeoSounds`, and headless classes in `forge.neo.rogue`, `forge.neo.ascent`, `forge.neo.deck`, `forge.neo.quest`).
  - **Do not import `javafx.*` or desktop-only settings (`NeoSettings`) in shared model/utility classes.**
  - **Avoid Java 9+ stdlib methods missing on Android API 26 (Android 8.0)** in shared classes—specifically `InputStream.readAllBytes()`, `InputStream.readNBytes()`, and `URLEncoder.encode(String, Charset)`. (`UpdateCheck.androidCanUseIt()` inspects compiled `.class` constant pools to enforce this).
