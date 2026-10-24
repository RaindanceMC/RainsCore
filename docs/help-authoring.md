# Writing a module's guide (`/help`)

Since RainsCore 2.4.0, `/help` (aliases `/?`, `/guide`) and the guide standing at spawn explain every feature in
Minecraft dialog windows. Each module ships its own pages; Core finds them in the jar, fills in the server's live
settings and shows them. Nothing to register.

## Where

`<x>-module/src/main/resources/rains-help/<topic-id>.yml` — one file per topic, file name = `id`.
A module may ship several topics (economy: `economy`, `shop`, `casino`, `auctions`, …). Split by what a player
would ask about, not by Java package: one topic ≈ one subject with up to 16 questions.

## Shape

```yaml
id: shop                       # lower case, digits, dashes; unique on the whole server
title: The Shop                # button label in the guide
summary: Buying from and selling to the server, at prices that move.   # ≤ 140 chars, one line
intro: |                       # the guide's opening words on the topic page (optional; summary otherwise)
  Coins in, items out. Or the other way round — I do not judge.
icon: EMERALD                  # an item (Material name)
category: Economy & trade      # one of the shelves below
order: 20                      # position on its shelf
module: economy                # REQUIRED: the module id — the topic hides where the module is not running
permission: some.node          # optional: the whole topic only for readers holding it (staff topics)
when: economy:features.shop    # optional: the whole topic only while this holds
aliases: [store, buy]          # /help store opens it (lower case)
keywords: [prices, sell]       # extra search words
commands: [shop, sell]         # the commands this topic explains — /help sell opens it; listed under "Which commands?"
related: [economy, auctions]   # "About …" buttons at the bottom
wiki: https://wiki.raindancer118.de/shop   # optional "Read more" button
sections:
  - question: How do I sell items?     # the button — as a player would ask it, ≤ 40 chars
    title: Selling                     # optional short name for search results
    text: |                            # the answer — MiniMessage + placeholders
      Hold it and type {cmd:/sell hand}, or {cmd:/sell all} for everything of that kind.
      {if:economy:shop.sell-tax-percent > 0}The server keeps {setting:economy:shop.sell-tax-percent|percent} of every sale.{/if}
    when: economy:features.sell        # optional: only while this holds
    permission: some.node              # optional: only for readers holding it
```

Shelves (`category`): `Getting started`, `Economy & trade`, `Land & building`, `Getting around`,
`Chat & community`, `Guilds & teams`, `Progress & rewards`, `Items & enchantments`, `Games`, `Looks & cosmetics`,
`Worlds`, `Staff`, `Everything else`.

## Placeholders — the point of the whole thing

Never write a number, price, limit, cooldown or on/off fact that a setting holds. Quote the setting, so the page
is right on every server and after every change:

| Placeholder | Shows |
|---|---|
| `{setting:<schema-id>:<key>}` | the value as it is now (`on`/`off`, lists comma-joined) |
| `{setting:…\|percent}` `\|money` `\|seconds` `\|minutes` `\|hours` `\|days` `\|ticks` `\|yesno` `\|onoff` `\|count` `\|number` | formatted |
| `{money:250}` | an amount in the server's currency |
| `{fact:<plugin>:<name>}` | a runtime value the module provides via `HelpFacts.provide(...)` |
| `{player}` `{server}` | reader, server name |
| `{cmd:/sell <amount>}` | clickable: puts `/sell ` into the chat bar |
| `{run:/spawn}` | clickable: runs it — only for harmless commands that open something |
| `{topic:economy}` | link to another topic, by its title |
| `{url:https://…\|label}` | web link |
| `{if:CONDITION}…{else}…{/if}` | one of two texts (not nested) |
| `{{` `}}` | literal braces |

Setting keys are `<@Settings id>:<key>` — the id from the record's `@Settings(id = …)`, the key from `@Key` or the
component name in kebab case (`payTaxPercent` → `pay-tax-percent`). Placeholders cannot nest.

Conditions (`when:` and `{if:…}`): `key` (on / non-zero / non-empty), `!key`, `key > 0`, `key == personal`,
`@perm:node`, `@plugin:Name`, `@module:id`, joined with `&&` / `||` (no brackets).

## Writing

- The guide is a person (default "Hazel the Witch", renamable): a little playful, never smug, always useful.
  Answers are second person: "You get…", "Type…". Short paragraphs, the important thing first.
- **Explain every detail a player needs**: what it is, how to start, every command and option, limits, costs,
  cooldowns, what happens on death/logout, what staff can do (in `permission:`-guarded sections or a Staff topic).
  Read the module's code, settings record and messages.yml — never guess behaviour.
- Sections that depend on a feature toggle get `when:` so a switched-off feature is never explained.
- Staff-only commands go in sections/topics with the command's permission, not in player topics.
- Every command of the module must appear in some topic's `commands:` — the lint fails otherwise
  (`undocumented("x")` for callbacks nobody types).

## The test every module carries

```java
class GuideTest {
    @Test
    void theGuideIsComplete() {
        assertThat(HelpLint.of(Path.of("src/main/resources/rains-help"))
                .module("economy")
                .settings(EconomySettings.class, EconomySettings.DEFAULTS)
                .commands(new EconomyModule().commands().stream().map(ModuleCommand::name).toList())
                .problems()).isEmpty();
    }
}
```

`foreignSettings("claims")` / `otherTopics("claims")` for pages that quote another module; `facts(...)` for the
module's `HelpFacts`. On a live server, `/help check` re-checks everything against what is really installed.
