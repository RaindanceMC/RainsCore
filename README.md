<div align="center">

# 🌧️ Rain's Core

**One implementation of everything Rain's plugins do to the game.**

[![Build](https://github.com/Raindancer118/RainsCore/actions/workflows/build.yml/badge.svg)](https://github.com/Raindancer118/RainsCore/actions/workflows/build.yml)
[![Version](https://img.shields.io/badge/version-1.39.0-4c9aff)](https://github.com/Raindancer118/RainsCore/releases/latest)
[![Paper](https://img.shields.io/badge/Paper-26.2-1f6feb)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-f89820)](https://adoptium.net/)
[![Tests](https://img.shields.io/badge/tests-1021%20unit%20%2B%20101%20live-2ea043)](#-working-on-it)
[![Dependencies](https://img.shields.io/badge/external%20dependencies-none-8957e5)](THIRD-PARTY.md)

[The idea](#-the-idea-in-one-minute) · [Quick start](#-quick-start) · [What is in it](#-what-is-in-it) ·
[Recipes](#-recipes) · [Resource packs](#-resource-packs) · [Settings](#-settings) ·
[Working on it](#-working-on-it)

</div>

---

Rain's Core is a Paper **library plugin**. It ships as a jar in `plugins/`, registers no commands of
its own, and does nothing until another plugin calls into it.

It is **not a framework**: it has no opinion about how your plugin is laid out, what it is called, or
which of its features you use. It owns exactly one category of thing — **whatever a server or a
player can only have one of** — and hands you the rest as ordinary, well-behaved utilities.

```java
RainsCore core = RainsCore.get();

Chat chat = core.chatFor("Claims");
chat.ok(player, "Claimed <n> blocks.", Chat.arg("n", 256));

core.actionBars().countdown(player.getUniqueId(), "tpa", Duration.ofSeconds(5),
        ActionBarPriority.NORMAL,
        left -> Component.text("Teleporting in " + Math.ceilDiv(left, 1000)));
```

---

## 🧭 The idea in one minute

A player has **one** action bar. **One** sidebar. **One** resource pack. **One** row in the tablist.
**One** next line of chat they are about to type.

Bukkit hands each of those to whichever plugin asks last. That is fine with one plugin and a disaster
with nine: the ghast line writes flight progress to the action bar every tick, the claims plugin
writes *"you have entered Raindancer118's claim"* when you cross a border, neither knows the other
exists, and the player sees a flicker several times a second. Nothing you can fix inside either
plugin will fix it. Somebody has to arbitrate.

> [!IMPORTANT]
> **The rule: if a player can only have one of it, Core owns it. Plugins ask.**
>
> Asking means saying who you are, how important this is, and how long it should last. Core decides
> what is shown, remembers what it interrupted, and puts that back when yours expires.

The second half is duller and just as valuable. Nine plugins had grown their own copy of the same
code — five menu frameworks, four ideas of what a chat prefix was, seven hand-rolled "write this YAML
file safely" helpers, and nine `getLogger()` calls with nothing written down anywhere. Fixing a bug in
one copy left it live in the other four. Everything in [What is in it](#-what-is-in-it) is there
because at least two plugins wanted it before it was written.

---

## 🚀 Quick start

### 1 · Get the jar

Drop `RainsCore.jar` from the [latest release](https://github.com/Raindancer118/RainsCore/releases/latest)
into your server's `plugins/`, or build it yourself:

```bash
cd RainsCore && mvn install    # → your local Maven repository
```

### 2 · Compile against it — never shade it

<table>
<tr><th>Maven</th><th>Gradle</th></tr>
<tr valign="top">
<td>

```xml
<dependency>
    <groupId>de.raindancer</groupId>
    <artifactId>RainsCore</artifactId>
    <version>1.39.0</version>
    <scope>provided</scope>
</dependency>
```

</td>
<td>

```kotlin
dependencies {
    compileOnly("de.raindancer:RainsCore:1.39.0")
}
```

</td>
</tr>
</table>

`provided` / `compileOnly` is the important part. Shading Core in gives you a *second* copy of every
registry — your own action bar arbiter, your own item registry, your own scoreboard owner — none of
which knows anybody else's exists. That is precisely the problem this library removes.

### 3 · Declare the dependency to the server

```yaml
# paper-plugin.yml
dependencies:
  server:
    RainsCore:
      load: BEFORE
      required: true
      join-classpath: true      # ← this line is what puts Core's classes on your classpath
```

> [!WARNING]
> **The one thing everybody gets wrong.** `depend: [RainsCore]` is legacy `plugin.yml` syntax and is
> **silently ignored** inside a `paper-plugin.yml` — no warning, no log line. Your plugin then loads
> without Core on its classpath and dies at first touch with a `NoClassDefFoundError` naming a class
> you never wrote.
>
> Still on an old-style `plugin.yml`? Then `depend: [RainsCore]` *is* correct — classloader isolation
> is a paper-plugin thing.

### 4 · Use it

```java
RainsCore core = RainsCore.get();          // RainsCore.isAvailable() if you treat it as optional
```

A complete, compiling example lives in [`examples/DemoPlugin.java.txt`](examples/DemoPlugin.java.txt)
— and it is not decoration: `ConsumerCompilesTest` compiles it on every build, so an API change that
would break a downstream plugin fails *this* build rather than yours.

---

## 🚫 It registers no commands

None. Not `/warp`, not `/settings`, not `/home` — taking a command name on somebody else's server is
not a library's decision, and a server that already runs a warp plugin should not have to fight this
one.

Instead Core ships the *handlers* and you decide the names:

```java
public final class MyBootstrap implements PluginBootstrap {
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            CoreCommands.clickCallback(event.registrar());              // chat buttons need this
            CoreCommands.settings(event.registrar(), "settings", "cfg");
            CoreCommands.profile(event.registrar(), "profile");
            CoreCommands.worlds(event.registrar(), "world", "w");
        });
    }
}
```

```java
// in onEnable, so buttons know where their callbacks live
core.buttons().callbackCommand(getName().toLowerCase(Locale.ROOT) + ":rcclick");
```

**Chat buttons are the one feature that genuinely needs a command.** A click in chat can only open a
URL, prefill the chat box, or run a command — so a server-side callback *is* a command, by necessity.
Until you register one, `buttons()` still renders readable labels, nothing is clickable, and Core
tells you once, with the exact fix.

> [!CAUTION]
> **Register in a bootstrapper, never in `onEnable`.** Paper fires the `COMMANDS` lifecycle event
> during bootstrap, so a handler registered in `onEnable` never runs — silently, no exception,
> nothing in the log. Core itself got this wrong, and *every chat button in the library was dead* on a
> real server until one was booted and checked.

---

## 🧰 What is in it

Everything below hangs off `RainsCore.get()`.

<details open>
<summary><b>🎯 &nbsp;Things there is only one of &nbsp;<sub>— the arbitrated ones</sub></b></summary>
<br>

| | What it does, and what Bukkit does not |
|:---|:---|
| **`actionBars()`** | Every message carries an owner, a priority and a lifetime, so plugins stop overwriting each other. What a refusal interrupts comes back when the refusal expires. `countdown()` redraws itself — there is no repeating task for you to cancel. |
| **`scoreboards()`** | The sidebar, arbitrated the same way. Packet-level underneath, so it neither flickers nor fights other plugins over team slots. Degrades to doing nothing if the server's internals move ahead of the vendored code, rather than throwing. |
| **`bossBars()`** | These *stack*, so this is a cap and a ranking rather than a single winner. Also **shared bars**: one bar with an audience that changes — a flight's passengers get on and off, and leaving takes the bar with them. |
| **`tablists()`** | Grouped by world, so the list says who is *where*. **Sorted by rank** (staff at the top, not wherever the alphabet put them) via scoreboard-team keys — the same lever Velocitab pulls. Your own title and logo, animated header and footer, and each player's ping as a number, because 30 ms and 130 ms look identical in five bars. |
| **`resourcePacks()`** | Plugins *contribute* assets; Core decides what is sent, builds it reproducibly, serves it and applies it. [See below](#-resource-packs). |
| **`prompts()`** | Asking a player to type something. The next line a player types is a thing only one plugin can have — three chat listeners each claiming it is three plugins fighting over one answer. |
| **`messages()`** | Every word the server says, in a file an owner can edit. Four layers, lowest first: your code default, the wording in the jar, the owner's `messages.yml`, and — above even that — anything a plugin insists on. A key missing from their file falls back instead of blanking, player text is escaped, and broken markup still renders. Two tones: refusals come with a dry remark by default; the *Message tone* setting switches to `SERIOUS`, which says the same lines from `messages-serious.yml` (a module ships its own beside its `messages.yml` and hands it over with `seriousFrom`). Lines the owner reworded stay theirs in both. |
| **`travelShow()`** | The sound and sparkle of every teleport, whichever plugin's `Travel` sent it: a departure sound, particles while somebody stands still for the warm-up, an arrival. Three cues an owner can rebind (`core:teleport-depart`, `core:teleport-wait`, `core:teleport`); a cosmetics plugin registers `TravelLooks` so a player's own choice follows them everywhere. `Trip.quiet()` for a trip with none. |

</details>

<details open>
<summary><b>💬 &nbsp;Saying things</b></summary>
<br>

| | |
|:---|:---|
| **`chatFor(tag)`** | One plugin's voice: `tell` / `ok` / `warn` / `no` / `row`. Consistent prefix, consistent colours. Player-supplied text goes in through `Chat.arg()` and is never parsed as markup. |
| **`buttons()`** | Clickable chat buttons with **real server-side callbacks** — bound to one player, one-shot, revocable, with an expiry. `ask()` is a two-answer question where answering removes the other button. |
| **`identities()`** | Who a player is as everyone else sees them: chat prefix, suffix, name colour, and the prefix above their head. Set it once and it shows in chat, on the nametag *and* in the tablist. |
| **`de.raindancer.core.ui.menu`** | The one menu framework. Six rows, three bands, chrome the framework owns so every plugin's menus look like the same server. `PaginatedMenu` and `ConfirmMenu` included. A page opened with no parent while another page is on screen goes back to that page (2.50.0), so a plugin's "open the bank" opener used from a button keeps the way back; `Menu.cameFrom(player, page)` does the same for a page that closes to run a command. Back names the page it goes to; every click plays Core's click cue, a page turn its page cue. A window's title is the page's alone (`Server › All claims`) — the plugin's tag no longer stands in front of it (2.51.0). A typed value — a name, a price — is asked for in the game's own dialog (`ui.prompt.TextDialog`, 2.52.0); `AnvilInput.open` goes there, so every plugin that asked in an anvil asks in a dialog now; "are you sure?" (`ConfirmMenu`) is the game's confirmation dialog too (2.53.0). `-Drains.anvil=true` keeps the anvil and the chest. |
| **`de.raindancer.core.ui.choose`** | Ready-made catalogue pickers: every item sorted into the creative tabs **and into families within them** (Oak, Deepslate, Red, Diamond); every sound with an icon of the thing that makes it; every particle; every player who has ever visited, ranked by how recently. |
| **`de.raindancer.core.ui.banner`** | The startup splash, with a logo drawn from your plugin's own name. |
| **`effects()`** | Every sound and particle any plugin makes, asked for **by meaning** (`Cues.NO`) rather than by sound name. 23 cues shipped; rebind one and every plugin's changes with it. Repeats are suppressed, so a per-tick loop cannot deafen anybody. |

</details>

<details open>
<summary><b>💾 &nbsp;Remembering things</b></summary>
<br>

| | |
|:---|:---|
| **`places()`** | Every saved place, from every plugin, in one store — homes, stops on a ghast line, warps, death points. Which is exactly why a ghast line can fly somebody to their own home without either plugin knowing the other exists. |
| **`punishments()` · `punishmentGuard()` · `banBridge()`** | Bans, mutes, kicks, freezes; what actually stops a punished player and what they are told; and a two-way bridge keeping Core's record and vanilla's `banned-players.json` in agreement. Nothing is ever deleted — lifting a ban records the lifting. |
| **`audit()`** | What was done on this server, and by whom. Two questions an ordinary logfile cannot answer: what has this moderator been doing, and what has been done to this player. Recording never touches disk on the calling thread. |
| **`achievements()`** | Custom achievements, earned exactly once, with progress towards a goal. |
| **`items()` · `itemFactory()` · `itemAbilities()`** | Custom items with abilities, cooldowns, charges and recipes. Recognised by a key in the item's PDC, so an anvil cannot forge a counterfeit. |
| **`lootTables()` · `lootFiller()`** | Weighted tables by tier, rolled and poured into a real container. An entry may be one of your custom items, so a supply drop can contain one that actually works. |
| **`databases()`** | SQLite for a plugin that wants tables rather than files — no driver to declare, Paper ships it. Reads and writes must be off the server's threads, and Core says so loudly in the log when they are not. |
| **`de.raindancer.core.data.store`** | `YamlStore` — a YAML file read and written without ever losing it. A server killed mid-write has the old file or the new one, never half of each. Use it for anything your plugin keeps. |

</details>

<details open>
<summary><b>🌍 &nbsp;The world</b></summary>
<br>

| | |
|:---|:---|
| **`land()` · `landPolicies()`** | Who may do what on protected ground. Core owns the question, the vocabulary and the enforcement; the ground itself comes from whichever plugin owns regions, via a registered `LandProvider`. With no provider installed every question answers **`UNKNOWN`**, never "allowed" — that distinction is what stops a farm-world regeneration deleting somebody's house because the claims plugin happened to be uninstalled. |
| **`combat()`** | Who may hurt whom: PvP, and players against creatures. Per server and per world, and nothing is switched off until somebody asks. The valuable part is working out *who* attacked — the server names the arrow, the wolf, the lit TNT, the lingering cloud, and every one of those has a person behind it. Register a more specific opinion with `alsoAsk` rather than adding a second damage listener. |
| **`safety()`** | Is it safe to put a player here, and if not, where instead. Two blocks of room, solid ground, no lava/fire/portal, a survivable drop — optionally checking the surrounding blocks too. Never loads a chunk to answer; that is `chunks()`'s job, first. |
| **`chunks()` · `pregeneration(...)`** | Keeping chunks loaded — for a moment, or until somebody lets go. Every permanent hold carries the name of whoever asked, because the flag is written into the world and survives a restart. `pregeneration` is a throttled walk that warms a region before anybody has to wait on it. |
| **`worldEntryPoints()`** | Where each player was standing right before their world last changed — what a world regeneration sends somebody back to when the ground is deleted out from under them, instead of a generic spawn. |
| **`worldRegenerator()` · `seedHistory()`** | Creating, deleting and resetting worlds — one world, or a group that belongs together (`regenerateAll`: a run's overworld, nether and end), where every occupant of *every* world is moved outside the group and waited for before anything is unloaded. Each takes a `WorldSeed`: `random()`, `same()` (the map it had) or `fixed(seed)`; `WorldSeed.parse` reads what people type, words hashed the way the vanilla world screen hashes them. Every seed created, replaced or deleted goes into `seedHistory()` first — a regenerated world's old seed exists nowhere else once its folder is gone. |
| **`PlayerTargets`** | `resolve(server, sender, "@a[distance=..10]")` — names and every vanilla selector, through the server's own parser, players only, each once. `suggest(...)` for tab completion. |
| **`worldEntryRules()`** | "May this player go into that world right now?" A plugin that locks worlds registers a rule (worded refusal, or empty); anything that moves people asks `refusal(player, world)` first. Neither needs the other installed. |
| **`WorldFamily`** | `x`, `x_nether`, `x_the_end` as one family — and `redirect(...)` for the portal in a runtime-made world that the server would otherwise send to *its own* nether. |
| **`WorldSnapshot`** | Game rules, difficulty, border and spawn read off a world and put back on its replacement — `worldRegenerator()` does this on every regeneration, the spawn only when the seed is the same. |
| **`de.raindancer.core.world.teleport`** | Travel with a reason: departures and returns, companions and entourages (pets and passengers come along), scatter, waypoints, and watchers that can veto or observe a trip. |
| **`de.raindancer.core.world.time`** | `Times` — reads what people actually type: `2min`, `2m`, `1h30m`, `2 weeks`, `perm`. **`m` is minutes and `M` is months**, deliberately. |

</details>

<details open>
<summary><b>⚙️ &nbsp;Running a server</b></summary>
<br>

| | |
|:---|:---|
| **`settingsFor(...)`** | Your whole configuration from one annotated record — [see below](#-settings). |
| **`settingsNavigation()`** | Every plugin's settings as one tree, which is what `/settings` and the shared settings menu both walk. |
| **`commands()`** | Every command on the server as reported by whoever owns it — one sentence and its options each. The only place the full list exists: no plugin can see past its own jar, and Bukkit's command map has every vanilla name and none of the words a reader needs. |
| **`grants()`** | A small permission grant list for a server without LuckPerms — no groups, no inheritance, no wildcards. It layers on top of a real permissions plugin rather than fighting it. `reapplyGrants(player)` applies a promotion *now*, because a moderator whose commands refuse them until they relog reports the promotion as broken. |
| **`players()` · `powers()`** | Heal, feed, starve, damage, effects, flight, gamemode, kick — every action answers what happened instead of throwing at the edges. `powers()` is who cannot be hurt and who hurts everything in one hit; neither survives a restart, on purpose. |
| **`inventoryViews()` · `inventories()`** | Invsee. One editor at a time (two editors duplicates items), armour protected by default, windows closing when their owner leaves. `inventories()` also reads **offline** players out of their save file — which is the half that matters, since the players a moderator most needs to inspect are the ones who logged out. |
| **`vanish()`** | Being properly not here: hidden, uncounted, silent joins, no collisions. Ask `visibleOf()` rather than `getOnlinePlayers()`, and `isVanished` before naming anybody — nearly every vanish leak is a place that skipped one of those two calls. |
| **`votes()`** | Ask everybody, or a named few, a question and count the answers. One ballot each, changeable until the deadline, and a tie stays a tie. |
| **`de.raindancer.core.platform.log`** | One logger, one logfile per day, rotated and pruned. Never blocks, never throws. |

</details>

---

## 🍳 Recipes

<details>
<summary><b>Say something in your plugin's voice</b></summary>
<br>

```java
Chat chat = core.chatFor("Claims");

chat.ok(player,   "Claimed <n> blocks.", Chat.arg("n", 256));
chat.no(player,   "<who> already owns this.", Chat.arg("who", other.getName()));
chat.warn(player, "You have <n> blocks left.", Chat.arg("n", remaining));
```

Anything passed through `Chat.arg()` is text, never markup — a home called `<red>` is nine
characters, not a colour code.

</details>

<details>
<summary><b>Ask a yes/no question in chat</b></summary>
<br>

```java
Component question = core.buttons().ask(
        target.getUniqueId(), Duration.ofSeconds(30),
        yes -> accept(requester, target),
        no  -> chat.tell(requester, "They declined."));

chat.raw(target, question);
```

Bound to that one player, answerable once, and answering removes the other button. A refusal says
*which* refusal it was — "that is not your button", "you already answered" and "that expired" are
three different things a player needs to hear.

</details>

<details>
<summary><b>Hold the action bar without fighting anybody</b></summary>
<br>

```java
core.actionBars().countdown(player.getUniqueId(), "tpa", Duration.ofSeconds(5),
        ActionBarPriority.NORMAL,
        left -> Component.text("Teleporting in " + Math.ceilDiv(left, 1000)));
```

`"tpa"` is the key: sending again with the same key replaces your own message rather than stacking a
second one. Whatever a higher-priority message interrupts comes back when that message expires.

</details>

<details>
<summary><b>Check before you move somebody</b></summary>
<br>

```java
// load the ground before asking anything about it
core.chunks().forAMoment(ChunkAt.ofBlock(world, x, z));

// may they even be here? UNKNOWN when no land plugin is installed — never "yes"
if (!core.land().can(player, destination, LandAction.ENTER)) return;

// and is there room to stand, within eight blocks of where they asked for?
core.safety().findSafe(new Spot(world, x, y, z), 8)
        .thenAccept(spot -> spot.ifPresent(safe -> travelTo(player, safe)));
```

A warp into somebody's house, a teleport accepted across a border, and a ghast line landing in a
stranger's garden are the same question asked three times. Before Core, each plugin either asked
nobody or wrote its own answer.

</details>

---

## 🎨 Resource packs

A player has **one** resource pack. So a plugin never sends its own — it offers what it has, and Core
decides:

```java
core.resourcePacks().contribute(
        PackContribution.of("Claims", "icons", getDataFolder().toPath().resolve("icons.zip"))
                .describedAs("The icons the claim menu uses")
                .priority(10));
```

A zip or a plain folder both work. Core collects every contribution, builds, serves and applies them.

**By default they go out stacked** — one pack per plugin, applied in order, all in a single request.
Clients have supported that since 1.20.3 and it is the better default: nothing has to guess how two
plugins' files combine, packs for different game versions coexist happily, and each pack is cached
separately — so adding a plugin costs players *that plugin's* download rather than everything again.

**Flip `packs-combine` on** to merge everything into a single zip instead: one download, one entry in
the client's list, with `lang/*.json` and `sounds.json` genuinely merged key by key. The cost is that
any change rebuilds the whole thing — and in that mode, two plugins wanting the same file is
**reported by name** rather than silently resolved.

Either way:

- the build is **byte-reproducible**, so clients cache instead of redownloading on every restart;
- a contribution that is not a usable pack is **dropped with a reason**, not served broken;
- `isWearing(player)` answers whether a given player can actually see your glyphs.

> [!TIP]
> Already have a CDN? Turn the built-in web server off in `config.yml` and set a public address.

---

## 🎛️ Settings

Declare a record. Everything else is derived from it.

```java
@Settings(id = "claims", topics = {
    @Topic(path = "config/limits", title = "Limits", icon = Material.BARRIER),
})
public record ClaimConfig(
    @In("config/limits") @Title("Blocks per player") @Range(min = 0, max = 100_000)
    int blocksPerPlayer,

    @In("config/limits") @Title("Fence tint")
    NamedTextColor fenceTint
) {
    public static final ClaimConfig DEFAULTS = new ClaimConfig(40_000, NamedTextColor.AQUA);
}
```

```java
SettingsStore<ClaimConfig> settings =
        core.settingsFor(this, ClaimConfig.class, ClaimConfig.DEFAULTS);

if (settings.current().blocksPerPlayer() > 0) { ... }
settings.onChange(config -> rebuildFences());
```

From that single declaration you get, for free:

| | |
|:---|:---|
| 📄 **a documented `config.yml`** | written into your own data folder, comments and all |
| ✅ **validation** | `@Range` and friends enforced on load and on every change |
| ⌨️ **`/settings` with real tab completion** | offering a setting's *actual* allowed values |
| 🖱️ **a page in the shared settings menu** | with the right widget per type — including a colour picker |
| 🔁 **change notifications** | `onChange` fires on edit, whether from the file, the command or the menu |

Nothing is written twice, so nothing can drift.

**Categories are yours.** Bring whatever you like, at whatever depth. Use a name Core already knows —
`player`, `management`, `config`, `appearance`, `moderation`, `modules` — and the button arrives with a
title and an icon for free, and your settings share a page with everybody else's. That merging is the
whole point: a player hunting for a setting does not know which of nine jars owns it.

---

## 🔨 Working on it

```bash
mvn test        # 1021 tests, no server needed
mvn install     # to the local Maven repository
```

**Everything testable without a server, is.** That is what the seams are for: `ActionBars` takes a
sink and a clock, `Chat` takes an `Audiences`, `ChunkHolds` takes a `ChunkLoader`. The arbitration,
the arithmetic, the persistence and the failure paths are all tested below the server line.

**What cannot be, is tested on a real one.** `../RainsCoreTestPlugin` runs **101 checks against a live
Paper server** and prints one line the run is judged by. It exists because no unit test can prove the
jar loads, the descriptor is right, or that Bukkit accepts what you built — and it earned its keep
immediately by discovering that *every chat button in the library was dead*, because a `COMMANDS`
lifecycle handler registered in `onEnable` never fires.

**No external dependencies.** Vendored code lives under `core.internal`, is recorded in
[`THIRD-PARTY.md`](THIRD-PARTY.md) with its licence and version, and is kept unmodified so upgrading
stays unpack-and-rename. `NoExternalDependenciesTest` enforces all three.

**CI does the rest.** Every push builds and tests; master additionally cuts a rolling `latest`
release, an immutable `build-N`, a `vX.Y.Z` release when the pom version is new, and publishes to
`packages.tstieh.de` and `plugins.raindancer118.de`.

---

## 📐 Conventions worth knowing before reading the code

- **A refusal says which refusal it was.** "That is not your button", "you already answered" and
  "that expired" are three different messages. A silent no just gets clicked again.
- **Anything a player typed is never markup.** A home called `<red>` is nine characters.
- **A button somebody may not use is shown, greyed out, with the reason** — not hidden, and never
  live-then-refusing.
- **Every store writes through `store.YamlStore`**, so a server killed mid-write has the old file or
  the new one, never half of each. Written once, used by all seven stores in here — it exists because
  those seven each had their own copy, which is the exact thing this library is for.
- **Ask by meaning, not by mechanism.** `Cues.NO`, not a sound name. `land().mayEnter(...)`, not your
  own region lookup.
- **Comments say why, not what.** Most of them exist because something went wrong once.

---

<div align="center">
<sub>Paper 26.2 · Java 25 · <a href="examples/README.md">Example plugin</a> ·
<a href="THIRD-PARTY.md">Third-party notices</a> ·
<a href="https://github.com/Raindancer118/RainsCore/releases/latest">Latest release</a></sub>
</div>
