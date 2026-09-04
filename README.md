# Smart Market Watchlist

A watchlist that answers the question a price table can't: **what actually
changed since you last looked, and does it deserve your attention?**

Built for Code, by Groww (Sep 2026).

---

## Setup

**Requirements:** Java 17+ and Node 18+. Nothing else — no Docker, no
database to install.

```bash
# 1. Backend (port 8080)
mvn spring-boot:run

# 2. Frontend (port 5173) — in a second terminal
cd frontend
npm install
npm run dev
```

Open **http://localhost:5173**.

```bash
# Run the tests
mvn test
```

### Optional: run against Postgres instead of H2

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=postgres
```

---

## The core idea

Most watchlists show you a price and a percentage. That's a data dump, not
help: it makes *you* do the work of remembering where things stood last
time, and it treats a 0.4% drift on a stock you don't own the same as a
stock breaking through the price you paid for it.

This build inverts that. The backend decides what is worth surfacing, ranks
it, and says why in a sentence you can act on. Rows with nothing notable
get out of the way.

### What counts as "meaningful"

Deliberately not "the price moved". A change is meaningful when it would
change what you *do*. Four rules, ranked by how loudly they should speak
(all in `ChangeDetector`, all configurable in `application.yml`):

| Signal | Why it's in | Severity |
|---|---|---|
| Crossed your average cost | The most personal number you have. Fires on the *crossing*, so a long-underwater position doesn't nag every visit. | Urgent |
| Hit a 52-week high / low | A genuine regime change, not noise. | Urgent |
| Volume spike vs its *own* expected pace | A quiet mid-cap at 5x its normal pace is a bigger story than a large-cap doing the same. Measured per-symbol as a *pace*, not a cumulative total — see below. | Notable |
| Price move past threshold since you last looked | The baseline. Anchored to your last acknowledgement, not to market open. | Notable / Info |

Cost basis is optional on purpose — a watchlist isn't a portfolio, and most
tracked symbols are ones you don't own.

**Volume is measured as pace, not total**, and the distinction is the whole
signal. Comparing cumulative volume against a daily average makes the ratio
climb all session, so by afternoon every symbol reads "spiking" and the
signal means nothing. Against expected-volume-by-now, an ordinary day sits
at 1.0 and 5x genuinely means five times the usual interest. (The naive
version shipped first; running the app for two minutes and watching every
row light up is what caught it.)

### The Delta Sparkline

Each row carries a mini chart that answers "what changed since I last
checked" before you read a single number:

- **Before your last acknowledgement:** thin, muted grey — history you've
  already seen, deliberately recessive.
- **Since:** thick, green or red by the direction of *that segment only*.
- A **marker** sits on the exact split point, with the acknowledged price
  carried across as a dashed baseline, so the size of the move is readable
  against where you left it.
- Never acknowledged? The whole line draws as new.

Inline SVG, no charting dependency — it's ~70 lines, and a library's default
styling would have to be fought to get this two-tone behaviour anyway.

### "Since you last looked" means *acknowledged*, not *loaded*

The anchor for change detection is the last time you explicitly marked a row
as seen (`POST /watchlist/{symbol}/ack`), not the last page load. If a page
refresh silently reset the anchor, an accidental reload would erase the
alert before you'd read it. Viewing is not the same as registering — so the
badge stays until you dismiss it.

---

## Architecture

```
React (Vite)  ──HTTP──▶  Spring Boot  ──▶  ChangeDetector   (what matters)
  localStorage             REST API    ──▶  JPA / H2|Postgres (your state)
  viewer id                            ──▶  MarketDataProvider (prices)
                                              └─ Resilient (last-known-good cache)
                                                   └─ Simulated (random walk)
```

**Backend layering:** controller (HTTP + error mapping) → service
(orchestration + transactions) → detector (the domain rule) → provider
(data access). The definition of "meaningful" lives in exactly one class, so
a second client — a mobile app — would inherit identical judgement for free
rather than reimplementing it.

**Market data is simulated.** A `MarketDataProvider` seam separates *where
prices come from* from everything else. A live demo that depends on a free
third-party API's uptime and rate limits is a demo that fails in front of
judges; swapping in a real feed is one new class implementing one interface.

---

## State persistence, across sessions and devices

A viewer ID (UUID) is generated client-side and persisted in `localStorage`,
sent as `X-Viewer-Id`. Watchlist rows and per-symbol acknowledgement state
live in the database keyed by that ID.

- **Across sessions:** close the browser, reopen it — same ID, same state.
  The default H2 store is *file-based*, not in-memory, so a server restart
  doesn't wipe it either.
- **Across devices:** paste the ID into the footer field on another device
  to pick up the same watchlist.

**This is not authentication, and the README says so rather than implying
otherwise.** Real auth is the correct answer; it's a scope cut, not an
oversight. Every layer treats `viewerId` as an opaque key, so swapping in a
JWT means changing one line in the controller and nothing else.

---

## Resilience: what happens when things go wrong

| Failure | Behaviour |
|---|---|
| **Upstream feed goes down** | `ResilientMarketDataProvider` serves last-known-good prices and flips `feedHealthy: false`. The UI shows a "Data currently delayed" banner over real-but-old prices. Never a crash, never stale data pretending to be live. |
| **Backend unreachable** | Frontend keeps the last rows on screen and switches the status to "Delayed" rather than blanking the page. |
| **Stale quote** | Every quote carries `asOf`; anything older than `stale-after-seconds` is flagged per-row, independently of overall feed health. |
| **Same stock added twice, same millisecond** | A unique `(viewer_id, symbol)` constraint lets the database settle the race; the loser is treated as success, because the stock is on the list either way. A read-then-write check would still lose that race. |
| **Two devices acknowledging at once** | `@Version` optimistic locking on `ViewState`. The stale writer gets a `409` telling it to reload — it does not silently clobber the newer acknowledgement. |
| **Zero / corrupt last-seen price** | Guarded explicitly; returns null rather than `Infinity`/`NaN`. Covered by a test. |
| **Stock split / corporate action** | A 1:10 split reads as a 90% crash against yesterday's anchor. Moves beyond `implausible-move-pct` are reported as a price-reference problem, not as a price move — the user should not act on that number. |
| **User returns after months** | An acknowledgement anchor older than `anchor-max-age-days` stops being a useful baseline. Rather than showing "+40% since you last looked", it says this is a first look in a while and falls back to today's move. |
| **A row fails to render** | An error boundary contains the failure to that row. One bad chart used to blank the whole page; now the rest of the watchlist survives it. |

You can demo the outage path live:

```bash
curl -X POST "http://localhost:8080/watchlist/debug/feed-outage?enabled=true"
```

(That endpoint would be admin-gated or absent in production — noted here
rather than left looking like an oversight.)

---

## How this scales

The thing that would kill a naive implementation is fetching prices
per-user-per-symbol. 10,000 users × 50 symbols is 500,000 lookups for maybe
200 distinct instruments.

- **Prices are ticked once per symbol**, independent of how many people
  watch them. The 10,000th viewer of RELIANCE costs one cache read, not
  another upstream fetch. Cost is O(distinct symbols), not O(watchlist rows).
- **The read path is 3 queries regardless of watchlist size** — items,
  view states, quotes — all batched. A 50-symbol watchlist costs the same
  number of round trips as a 5-symbol one.
- **Change detection is O(rows) pure computation** with no I/O per row.

**Where it would break, honestly:** the quote cache is in-process, so
running more than one instance means instances could disagree on price.
That's the point where the cache moves to Redis and one producer ticks and
fans out via pub/sub — a change isolated to `ResilientMarketDataProvider`.
It is not built here, because a single instance doesn't need it and shipping
unused infrastructure isn't engineering, it's decoration.

Polling (3s) over WebSockets is the same call: at this scale polling is
simpler, debuggable, and survives reconnects for free. WebSockets earn their
complexity at a tick rate and user count this build doesn't have.

---

## Trade-offs, stated plainly

- **H2 by default, Postgres by flag.** Anyone evaluating this can run it in
  one command; every extra dependency is another way for that to fail on
  someone else's machine. JPA mappings are identical, so the Postgres path
  is a profile switch, not a rewrite.
- **No Redis.** Idempotency is a database constraint; caching is in-process
  and sufficient for one instance. Adding Redis now would be a dependency
  carrying no weight.
- **Polling, not WebSockets.** See above.
- **No auth.** See above.
- **Simulated feed.** See above.

## What I'd do next

1. Real auth, replacing the viewer ID.
2. Redis-backed quote cache + single-producer tick, for multi-instance.
3. Persist a price history series to support "since yesterday" / "since last
   week" windows instead of only "since you last looked".
4. Push notifications for urgent signals, so the user doesn't have to open
   the app to learn their position went underwater.

## API

| Method | Path | Description |
|---|---|---|
| GET | `/watchlist` | Rows + signals + feed health |
| POST | `/watchlist` | Add `{symbol, costBasis?}` (idempotent) |
| DELETE | `/watchlist/{symbol}` | Remove |
| POST | `/watchlist/{symbol}/ack` | Mark as seen — moves the change anchor |
| GET | `/watchlist/instruments` | Supported symbols |
| POST | `/watchlist/debug/feed-outage?enabled=` | Demo: simulate feed failure |

## Bugs found by actually running it

Worth remembering, because "how did you find that?" is a question they can
ask, and "I ran it and watched" is a better answer than "I assumed".

1. **Idempotent add wasn't idempotent.** Catching
   `DataIntegrityViolationException` inside a `@Transactional` method does
   nothing — the transaction is already marked rollback-only, so the commit
   fails anyway. Fix: drop `@Transactional` from `add()` and let the
   repository's own transaction own the boundary. Found by an integration
   test, not by reading the code.

2. **Volume spike fired for everything.** Cumulative volume compared against
   a fixed daily average means the ratio climbs forever. Two minutes of
   uptime and every row read "5x normal volume" — the exact data-dump
   behaviour the product is meant to prevent. Fix: measure pace
   (`volumeToday / expectedVolumeByNow`). Found by watching the UI.

3. **"+774.61% since you last looked".** A persisted acknowledgement anchor
   survived a restart while the simulator reseeded prices. Simulator
   artifact on the surface, but it exposed two real cases: a stock split
   makes an old anchor meaningless, and so does a user returning after
   months. Fix: discontinuity guard + anchor expiry, both surfacing an
   explanation instead of a number.

4. **One bad sparkline blanked the entire page.** `findIndex` returns -1
   when the user acknowledges between ticks (no sample newer than the ack
   yet), and `history[-1].price` throws. Fix: treat -1 as "everything so far
   is seen", plus an error boundary so a single row can never take the app
   down again.


