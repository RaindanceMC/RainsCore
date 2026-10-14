# RainsCore 2.0 — building for a network

From 2.0.0 every plugin's data lives in a database: a server's own SQLite on its own, the network's one
central PostgreSQL when `plugins/RainsCore/network.yml` says `type: postgresql`. Nothing a plugin keeps is a
file any more. This page is what a module needs to know to be correct on a network of many servers.

## What Core already did for every plugin

| A plugin used | On 2.0 it is | Shared between servers? |
|---|---|---|
| `new YamlStore(path)` under `plugins/` | a document in the database (`Documents`); the 1.x file is brought in on first use and kept as `*.imported-2.0.0` | no — each server its own, as its file was |
| `context.settings(...)` / `config.yml` | layered settings: the network's values, a group's, this server's own | yes, in layers |
| `core().databases().of(name, schema)` | this server's own namespace (`<name>_<server>`); the 1.x `<name>.db` imported | no |
| `messages.yml`, `prefix.yml`, custom items, loot, achievements, land flags, grants, playtime, known names | Core's own network stores | yes |

So every module already runs on a network without losing data — but its data is **per server** until the
module chooses otherwise. Choosing otherwise is the work of moving a module.

## Choosing what is shared

| Data | Scope | How |
|---|---|---|
| bound to this server's worlds (claims, walls, mannequins, farm worlds, rtp spots) | server | `new YamlStore`, `DocumentFolder.of`, `databases().of` — as is |
| about a player, wherever they play (nicknames, ignores, styles, quest progress, mentions, reports) | network | `YamlStore.network(path)` / `DocumentFolder.network(folder)` / `databases().shared(...)` |
| catalogues an owner edits once for everybody (ranks, roles, shop packs, rules) | network | `YamlStore.network` + `onChange(this::reload)` |
| money, items, escrow — anything that can be duplicated | one writer | keep it per server, or hold a `NetworkRoles` role and answer `NetworkCalls` |

## Rules for shared data (each one was a real bug)

1. **Read first, replace after.** `store.readIfReadable()`: empty means "could not be read" — keep what is
   in memory. Never treat a failed read as "nothing there" (`exists()` answers true when it cannot tell).
2. **Reload on change.** A network `YamlStore` calls `onChange(listener)` when another server writes it — not
   for its own writes. Before reloading, write what this server changed (or keep it) so it is not lost.
3. **Write only what you changed.** `YamlStore.write` merges this server's changes onto another's (value by
   value); a store that keeps totals must instead write operations (`+ delta`) in SQL.
4. **Database stores**: `refresh(key)` / `refreshAll()`, `connect(bus)`, publish after commit, keep unwritten
   and in-flight changes on refresh (`Marks.drainInto`), read back after your own write, `refreshAll` on
   `bus.onResync`. Core's `Punishments`, `PoiStore`, `Identities` are the reference.
5. **Tests run twice.** Tag a store test `@Tag("database")` and open databases with
   `TestDatabases.open(path, schema)`; the build runs it on SQLite and PostgreSQL. Two "servers" in one test:
   two stores on one database, `LocalBus.Hub` for the bus.

## The 1.x link and what replaces it

On a network the link stands down (`link.yml` is ignored). Use:

| 1.x | 2.0 on a network |
|---|---|
| `core().link()` requests, `ServerLink.on/request` | `core().networkCalls()` — `answer(topic, handler)`, `ask(server, topic, data, timeout)` |
| `playersElsewhere()`, `playersByServer()` | still work (presence), or `core().networkPlayers()` — `where`, `named`, `everyone`, `byServer` |
| `links().serverOf(name)`, `links().tell(...)`, `whois` | `networkPlayers().named(name, false)`, `networkPlayers().tell(uuid, key, values...)` |
| `ProxyTransfer.send(plugin, player, server)` | `core().networkTravel().send(player, server, Optional.of(destination))` — lands them at a place, says if it failed |
| `LinkedEconomy`, `linkFollows()` economy | nothing: Core provides `NetworkEconomy` — the server running the economy is the bank |
| `shareProfile(part, provide, adopt)` | keep that per-player data in a network store instead (it is a no-op on a network) |
| `syncFile(path, changed)` | make that store `YamlStore.network` + `onChange` (a no-op on a network); `config.yml` is already shared by its layers |
| `sharePlayerData(namespace)` | unchanged — on a network it is kept in the database and follows players |
| a topic broadcast to "the other server" | `core().bus().publish(topic, key, payload)` / `subscribe` — every server hears it |

All of these are `Optional`/no-op on a server of its own; check `core().network().isNetwork()` where a
module has to behave differently.

## Not storage

World folders, schematics, resource-pack assets, log files and the game's own player files stay files.
