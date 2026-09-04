# 100-word product pitch

## Draft (98 words)

Most watchlists show a price and a percentage, leaving you to remember where
things stood last time. This one decides what deserves your attention and
says why: it flags crossings of your own average cost, 52-week breaks, and
volume spikes measured against each symbol's own norm — anchored to when you
last acknowledged a row, not when the page last loaded, so a refresh can't
erase an alert you haven't read.

Backend owns that judgement in one class, so any future client inherits it.
Prices tick once per symbol, not per user. When the feed dies, you get last
known prices, clearly labelled — never a blank screen.

---

## Notes for tightening

- Word count: 98. Ceiling is ~100, so there's no room to add without cutting.
- The three things it deliberately spends words on: the *definition* of
  meaningful (product thinking), the ack-vs-load distinction (originality),
  and degradation behaviour (resilience). Those map to three of the five
  things they said they evaluate.
- What's deliberately left out, because the README covers it: stack choice,
  H2-vs-Postgres, scaling numbers. The pitch should make them want to open
  the README, not replace it.

## Likely follow-up questions, and honest answers

**"Why is the market data simulated?"**
A demo that depends on a free third-party API's uptime and rate limit is a
demo that fails in front of you. The provider interface means a real feed is
one new class. I'd rather show a working system with a stubbed edge than a
broken system with a real one.

**"Why polling, not WebSockets?"**
At a 3-second tick and this user count, polling is simpler, easier to debug,
and reconnects for free. WebSockets earn their complexity at a tick rate
this doesn't have. I'd switch when the tick rate goes sub-second or the
connection count makes per-request overhead dominant.

**"Why H2 and not Postgres?"**
Postgres is the production answer and it's one profile flag away — the JPA
mappings are identical. H2 file-based is the *default* so that anyone can
run this in one command. I optimised the default for the person evaluating
it, not for the deployment that doesn't exist yet.

**"What breaks first if this got real traffic?"**
The in-process quote cache. Two instances would disagree on price. The fix
is Redis plus a single producer ticking and fanning out via pub/sub, and
it's isolated to one class. I didn't build it because one instance doesn't
need it.

**"Why is 'meaningful' defined that way?"**
Because a change matters when it would change what you do. A 0.4% drift on
something you don't own is noise; the same move crossing the price you paid
is a decision. That's why cost basis is the loudest signal and why volume is
measured per-symbol rather than against a global constant.

**"What would you build next?"**
Price history, so the window can be "since yesterday" rather than only
"since you last looked". That's the biggest gap in the current model.
