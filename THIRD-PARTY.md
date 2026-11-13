# Third-party code in this repository

Nothing is fetched at runtime: everything RainsCore needs is inside its own jar. Copied source is kept
unmodified apart from its package name, so that upgrading one is a copy rather than a merge. One library
is shaded as a jar instead of copied as source, because it is far too large to keep by hand.

## PostgreSQL JDBC driver (pgjdbc)

- **Where** inside the plugin jar, relocated to `de.raindancer.core.internal.postgresql` by maven-shade
  (`pom.xml`); not in the source tree
- **Upstream** <https://github.com/pgjdbc/pgjdbc> — `org.postgresql:postgresql` 42.7.8
- **License** BSD-2-Clause (Copyright (c) 1997, PostgreSQL Global Development Group); the licence text
  travels inside the jar as `META-INF/LICENSE`
- **Added** 2026-10-14, for RainsCore 2.0.0 — a network keeps all its data in one central PostgreSQL
  (NETWORK-PLAN.md, decisions N1 and N10), and Paper ships drivers for SQLite and MySQL only
- **How it fits our code** only `data.sql.PostgresPool` touches it, and it constructs the driver
  directly rather than through `DriverManager` (which only sees drivers its caller's class loader
  registered — the relocated one is not registered at all)
- **Why shaded, not downloaded** Paper's `libraries:` would fetch it at startup: an offline server
  would not load, and a repository outage would take the network down. Relocated so another plugin's
  copy of the driver cannot collide with ours.
- **Upgrading** raise the version in `pom.xml` and here; nothing else changes

## FastBoard

- **Where** `src/main/java/de/raindancer/core/internal/fastboard/`
- **Upstream** <https://github.com/MrMicky-FR/FastBoard> — version 2.2.0
- **Author** MrMicky
- **License** MIT
- **Copied** 2026-08-03
- **Changed** the package, from `fr.mrmicky.fastboard` to
  `de.raindancer.core.internal.fastboard`. Nothing else — no local edits, so upgrading is
  unpack-and-rename rather than a merge. The String-based `FastBoard` class was not copied; only the
  Adventure one is used.
- **How it fits our code** everything of ours reaches it through `de.raindancer.core.ui.scoreboard`:
  `Board`/`BoardFactory` are the seam, `FastBoardFactory` is the only class allowed to import it, and
  `Scoreboards` adds what a library has no business deciding — which plugin owns a player's sidebar.
  It never logs; it throws, and `Scoreboards` turns that into our `Log` and into "this server has no
  sidebars, stop asking". `NoExternalDependenciesTest` keeps the import boundary and the copyright
  headers honest.
- **Why** a packet-level scoreboard does not flicker, can be written from any thread, and does not
  fight other plugins over Bukkit's team API. Version 2.2.0 is the first release to state Paper 26.2
  support, and its Adventure variant is generic over `net.kyori.adventure.text.Component` — the same
  class Adventure 5.2 ships, so it is binary-compatible with what Paper 26.2 provides.

The MIT license requires that the copyright notice travels with the code. Each copied file still
carries MrMicky's header, and that must stay.

### Upgrading

    mvn dependency:copy -Dartifact=fr.mrmicky:fastboard:<version>:jar:sources \
        -DoutputDirectory=target/fbsrc

Unpack, re-apply the package rename, and check `THIRD-PARTY.md` and the version noted above.

## Kenney — Input Prompts Pixel

- **Where** `src/main/resources/de/raindancer/core/pack/assets/rainscore/textures/font/hints/` (Java) and
  `src/main/resources/de/raindancer/core/bedrock/font/glyph_F8.png`, `pack_icon.png` (Bedrock)
- **Upstream** <https://kenney.nl/assets/input-prompts-pixel> — version 1.0, tiles 77–79 (mouse buttons), the SHIFT
  key and tile 166 (⇧ key, Bedrock)
- **License** CC0 1.0 (public domain); credit not required, given here anyway
- **Added** 2026-11-08, for RainsCore 2.61.0 — click hints in menus as mouse and key icons (`ui.menu.ClickHints`)
- **Changes** cropped to the drawn width; the Bedrock glyph page places them in cells 0–3 of page F8

## Kenney — Input Prompts Pixel

- **Where** `src/main/resources/de/raindancer/core/pack/assets/rainscore/textures/font/hints/` (Java) and
  `src/main/resources/de/raindancer/core/bedrock/font/glyph_F8.png`, `pack_icon.png` (Bedrock)
- **Upstream** <https://kenney.nl/assets/input-prompts-pixel> — version 1.0: the mouse tiles 77–79 and the SHIFT key
- **License** CC0 1.0 (public domain); credit not required, given here anyway
- **Added** 2026-11-08, for RainsCore 2.61.0 — click hints in menus as mouse and key icons (`ui.menu.ClickHints`)
- **Changes** cropped to the drawn width; on Bedrock's glyph page F8 the SHIFT key is split over cells 0 and 4

## Not third party, but shaded in: rains-client-protocol

- **Where** shaded under its own package `de.raindancer.client.protocol` (it is part of `platform.client`'s API)
- **Upstream** <https://github.com/RaindanceMC/client-protocol> — our own, no dependencies; the client mod carries the same jar
- **Added** 2026-11-13, for RainsCore 2.90.0 — see `NoExternalDependenciesTest.SHADED` for why it is not copied in
