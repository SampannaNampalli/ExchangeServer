# ExchangeServer — Spec v6

**Supersedes:** Spec v5 (and v3, v4). Self-contained: nothing needs to be read from earlier specs.
**Companion:** `ExchangeServer_Vision_v3.md` (purpose, working style, principles, **roadmap**; the roadmap is not repeated here).
**Verified against the local working tree on 2026-10-17:** 133 tests, all green.
**Status:** roadmap step 1 complete (O(1) cancel + open-thread cleanup). Next: roadmap step 2 (order types / cancel flows), see §5.

---

## 1. Session rules (read first)

These refine Vision v3 §3 and are the agreed way of working:

1. **Code first, questions after** (Vision v3 §3.6). The LLM gives a short description of what to build (a few lines per behaviour, no hints, no edge-case lists, no code) -> the user designs and codes it -> the LLM reviews the saved code and shows where it goes wrong (location + concrete failing input, not the fix) -> the LLM tests the user's understanding of the reasoning behind each piece -> the user fixes; repeat until clean -> the LLM writes and runs the tests.
2. **The LLM writes the tests** (since 2026-10-17). It compiles and runs them against a scratch copy of the code before handing them over, and states the result. The user reads each test to understand it.
3. **The LLM never edits Java source files.** All code (tests included) is given in chat; the user pastes it. Editing the docs in `docs/` when asked is fine.
4. **Review only saved files.** Ask the user to save before review; check the IDE's unsaved-buffer diagnostics if available.
5. **Name the real reason** for every check or design choice; call out stale rationales (Vision principle 4).
6. **How to run the tests** (no build file exists): compile `src/*.java` with `javac`, classpath = JUnit jars from `~/.m2/repository` (`junit-jupiter-api`/`-engine` 5.11.4, `junit-platform-commons`/`-engine`/`-launcher` 1.11.4, `opentest4j` 1.3.0, `apiguardian-api` 1.1.2), and run them with a small `LauncherFactory` + `SummaryGeneratingListener` main. Use Windows-form paths (`C:/...`) in the classpath on this machine. IntelliJ runs them via its own JUnit 6 module library.

---

## 2. Current state

All classes are in the **default package** in `src/`. Single instrument, limit orders only.

| Class | What it is now |
|---|---|
| `Side` | `BUY`, `SELL`. |
| `Order` | Final `orderId`, `side`, `price` (ticks), `originalQuantity`, `timestamp`; mutable `filledQuantity`. Constants `MAX_ORDER_QUANTITY = 1_000_000_000`, `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (D20). Constructor rejects id/price/quantity `<= 0`, `price > MAX_PRICE`, `quantity > MAX_ORDER_QUANTITY`. **Does not yet reject a null side** (§6). `fill(q)` rejects `q <= 0` and overfill. |
| `OrderRequest` | Gateway-side stateless validation (D19): non-null side, `1..MAX_PRICE`, `1..MAX_ORDER_QUANTITY`, using `Order`'s constants. |
| `Trade` | `quantity`, `price`, `buyOrderId`, `sellOrderId`, `timestamp`. |
| `OrderNode` (pkg-private) | `final Order order`; `prev`, `next`, `level`. Real nodes require non-null order; sentinels only via `OrderNode.sentinel()`. |
| `PriceLevel` | Doubly linked list with sentinel `head`/`tail`; `totalVolume`, `noOfOrders`. `OrderNode addOrder(Order)` (pkg-private; checks price match, remaining > 0, volume overflow, all before mutating), `Order peekFirstOrder()` (null if empty; throws if head has 0 remaining), `long fillHeadOrder(q)` (fills head only, never removes), `void unlink(OrderNode)` (pkg-private; guards D9; O(1)), `isFullyConsumed()` (`head.next == tail`), test-only walks `ordersHeadToTail()` / `ordersTailToHead()`. |
| `OrderBook` | `bids` (`TreeMap`, reverse order), `asks` (`TreeMap`), `orderMap: HashMap<Long, OrderNode>`. `addOrderToBook(Order)` (all checks before any mutation; duplicate id throws), `removeOrderFromBook(id)` (pkg-private; missing id throws; unlink -> map remove -> eager level removal), private `removePriceLevel(level, side)` (checks `TreeMap.remove` returned that level), `CancelResult cancel(id)` (unknown id -> `UNKNOWN_ORDER`), `bestBid/bestAsk/bestLevel(side)`, `getVolume(side, price)`, `getNoOfBids/Asks()`, test-only `containsOrder(id)`. |
| `CancelResult`, `CancelStatus` | `record CancelResult(CancelStatus cancelStatus, long cancelledQuantity)`; `enum CancelStatus { CANCELLED, UNKNOWN_ORDER }` (D17). Note: the component is named `cancelStatus`. |
| `MatchingEngine` | Holds `OrderBook` and `nextOrderId` (starts at 1). `submit(OrderRequest)` -> `createOrder` -> `process(Order)`; `submit(Order)` is a test-only entry. `process()`: loops while incoming has remaining, a best resting level exists and it `crosses`; then rests any remainder. `match()`: peek resting head, `fillHeadOrder`, fill incoming, `removeOrderFromBook` if resting hit 0, build `Trade` at the resting price. `crosses(long, long, Side)` has no guard (D19). **No cancel entry point yet** (§5). |
| `Main` | Scratch harness, not the real entry point (§6). |

**Invariants (all tested):**
- `PriceLevel.totalVolume` = sum of remaining over linked nodes; `noOfOrders` = number of linked nodes.
- An id is in `orderMap` iff its order rests in a level.
- The book never holds an empty price level.
- `addOrderToBook` is all-or-nothing: if it throws, the book is unchanged.
- An invalid `Order` cannot be constructed.

**Tests (133):** `PriceLevelTest` 26, `OrderBookTest`, `MatchingEngineBookCleanupTest`, `OrderTest`, `OrderRequestTest`, `MatchingEngineCrossesTest`, `MatchingEngineMatchTest`, `MatchingEngineSubmitTest`, `MatchingEngineTest` (its `CrossesTest` duplicates `MatchingEngineCrossesTest`).

**Complexity:** cancel / fill-removal of a node O(1); removing an emptied level O(log L); best-level lookup `firstEntry()` O(log L) per match iteration.

**Git:** all of the above is committed and pushed as `f576a4f` ("Add cancel order type", 2026-10-17) to `origin/main` (`github.com/SampannaNampalli/ExchangeServer`). Verified: local = remote, working tree clean, the committed tree compiles and passes all 133 tests. **But `src/.gitignore` ends with `docs/`**, so the specs, Vision docs and other files in `docs/` are not in git (only `DesignDecisions.MD`, tracked earlier, is). See §6.

---

## 3. Decisions (settled)

| # | Decision | Reason |
|---|---|---|
| D1 | `OrderBook` owns the `orderId -> OrderNode` map. | Only the code that mutates levels can keep "id in map iff resting" true. The engine never touches the map. |
| D2 | One `unlink(node)` serves fill-removal and cancel. | One code path, one place for invariants. |
| D3 | `fillHeadOrder()` only fills; never removes. | Otherwise the book never learns of the removal and the map goes stale. |
| D4 | `match()` calls `removeOrderFromBook` when the resting order hits 0. | The engine already holds the book. |
| D5 | `OrderNode` wrapper, not link fields in `Order`. | `Order` stays a domain object; only `PriceLevel`/`OrderBook` ever hold nodes, so the engine cannot reach the links. Cost ~32 B + one pointer hop per resting order. Revisit at step 6 only if benchmarks show it; try node pooling before making `Order` intrusive. |
| D6 | Self-implemented doubly linked list with caller-held node handles. | `java.util.LinkedList` hides nodes (O(n) remove); a `ListIterator` is invalidated by other modifications, so it cannot sit in a map. |
| D7 | Sentinel head/tail. | Every real node has non-null `prev`/`next`, so link/unlink need no head/tail/only-node branches. |
| D8 | `unlink` nulls `prev`, `next`, `level`. | Stale nodes are detectable. |
| D9 | `unlink` throws `IllegalStateException` for a sentinel (`order == null`) or a node whose `level != this` (already unlinked or foreign). | Unguarded, a foreign node would be spliced out of the *other* level's list while this level's counters drop: silent corruption of both. |
| D10 | Eager removal of emptied levels. | Strict invariant, simple best-level lookup; O(log L) per emptied level. |
| D11 | Cached best-level pointer deferred to benchmarking. | Measure first. |
| D12 | Log source of truth = sequenced input commands, replayed through the real engine. | Live and replay paths cannot drift. Requires determinism (§4). |
| D13 | State-dependent rejects (e.g. cancel of unknown id) stay in the log. | Their outcome depends on engine state. |
| D14 | Drained-level removal happens in `removeOrderFromBook`, not in `process()`. | Follows from D1, D3, D4, D10. |
| D15 | Duplicate order id in `addOrderToBook` throws. | Ids come from our own code, so a duplicate is an internal invariant violation, not a client reject. |
| D16 | Order ids are never reused; monotonically increasing. | Logged cancels, trade history and client acks refer to ids and would become ambiguous. |
| D17 | `cancel` returns `CancelResult(status, cancelledQuantity)`; `UNKNOWN_ORDER` = not resting (never existed, filled, or already cancelled; the book need not tell these apart); `cancelledQuantity` = remaining at cancel time, 0 on reject. `NOT_OWNER` deferred until orders carry a participant id. | Clients need the reject reason and how much was cancelled vs filled. An enum can be branched on; a String or an exception cannot (sensibly). |
| D18 | Level removal is private to `removeOrderFromBook` and checks that `TreeMap.remove` returned the emptied level. The old public `removePriceLevel(Side)` is deleted. | One owner for level removal; wrong-side/wrong-key bugs fail loudly. |
| D19 | Validation in exactly two layers: `OrderRequest` (gateway, stateless, fail fast) and the `Order` constructor (the real invariant). No checks in `createOrder`; no guard in `crosses`. | Middle-layer copies added nothing and drifted apart. Stateless rejects never enter the log. The reason is the log and clear rejects, not latency. |
| D20 | Limits are code constants in `Order`, inclusive: `1 <= price <= MAX_PRICE`, `1 <= quantity <= MAX_ORDER_QUANTITY`; `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (9,223,372,036). | `price × quantity` (notional) can never overflow a `long`. Constants, not config: a config change between a live run and its replay would make logged commands poison pills. If ever moved to config, log the value with the run. |

---

## 4. Log, replay, determinism (design for roadmap steps 3 and 7)

- Sequenced **input commands** (new order, cancel) are the source of truth; outputs (acks, trades, fills, market data) are derived by replay.
- **Write-ahead:** a command is logged before the engine processes it.
- **Reject split:** stateless rejects are caught at the gateway and never logged; state-dependent rejects are decided by the engine and logged.
- **Determinism:** sequence number, timestamp and order id are stamped on the command *before* logging. No wall clock, randomness or `HashMap` iteration order in matching (the id map is only looked up). Single writer thread.
- **Known gaps:** `createOrder()` and `match()` call `Instant.now()`; the engine (not a sequencer) assigns order ids. Fix when the sequencer arrives: use the command's stamped timestamp and id.
- **Poison pill:** a logged command that crashes the engine crashes every replay. Needs a policy (skip/quarantine); roadmap step 7.
- **Output event shape:** an order that never rests: received -> match(es) -> done. "Open/rested" only for orders that go on the book. No fabricated events.

---

## 5. Next: roadmap step 2 — cancel flows and order types

Not started. Short descriptions only (session rule 1): the user designs and codes, then review.

**A. Cancel through the engine** (do first)
- A client cancels a resting order by id **through `MatchingEngine`**, not by calling `OrderBook` directly, because every command (new order or cancel) must go through the engine, the single writer.
- The client gets back whether it was cancelled and how much was cancelled (D17).

**B. Order types** (one at a time, after A)
- **Limit (today's behaviour):** match what crosses; the remainder rests at its price.
- **IOC (immediate-or-cancel):** match what crosses now; the remainder is cancelled instead of resting.
- **FOK (fill-or-kill):** either the whole quantity fills right now, or nothing happens at all (book untouched).
- **Market:** no price; takes whatever is available on the other side until filled; never rests.
- **Post-only:** must add liquidity; if it would match anything on arrival, it is rejected instead.

---

## 6. Open threads

**Repo hygiene**
- `docs/` is git-ignored (last line of `src/.gitignore`), so this spec and the Vision exist only on the local disk. Decide: un-ignore and commit them, ignore only private files (e.g. `Prompts.MD`, the GPT text), or keep private and back up elsewhere.
- Git `user.name` is still the placeholder "Your Name" (6 of 7 commits). Set it with `git config --global user.name "..."` for future commits; do not rewrite pushed history.
- `src/.gitignore` mostly duplicates the root `.gitignore`.

**Small code items**
- `Order` constructor should reject a `null` side (D19; a null side currently passes `process()` and is treated as SELL). Add check + test.
- `OrderBook.removePriceLevel` still has checks that `pl != priceLevel` already covers (`isEmpty`, `containsKey`, `isFullyConsumed`); only the `TreeMap` choice and the return-value check are needed.
- `removeOrderFromBook` looks the id up twice (`containsKey` + `get`); one `get` + null check suffices. Its "Illegal State" message is fine.
- `submit(OrderRequest)` duplicates `createOrder(OrderRequest)`; could be `process(createOrder(req))`.
- Header comment in `OrderBook.java` (draft Q&A answers, stale `HashMap<Long, Order>` note) belongs in `docs/DesignAnswers.txt`.
- `Main.java` is a scratch harness and builds orders with id 0, which `Order` now rejects. Delete it, or keep it as a demo once the gateway exists.
- `CancelResult` component name `cancelStatus` (accessor `cancelStatus()`) vs the shorter `status`: cosmetic, user's choice.

**Design threads**
- Participant id on `Order`: needed for `NOT_OWNER` cancel rejects and self-trade prevention. When added, decide whether a non-owner cancel returns `NOT_OWNER` or `UNKNOWN_ORDER` (avoids revealing that someone else's id exists). Or drop self-trade prevention from scope.
- Principle 2 tension: `PriceLevel.fillHeadOrder()` holds fill logic and `match()` calls it directly. Acceptable, or should `OrderBook` expose the fill?
- Thread model: gateway connections -> ordered queue -> single writer (roadmap step 4).
- Spring Boot vs plain TCP gateway (Vision v3 §8).
- Real-world anchors: lot sizes, tick sizes, typical large-trade sizes, and how they should shape the D20 limits.

**Deferred on purpose**
- **Move tests out of `src/`** (user deferred this, 2026-10-17). Plan: a sibling source root `Exchange Server/test/` marked "Test Sources Root" in IntelliJ, files keep **no package line** (same default package, so package-private access still works). **Not** `src/test/`: that would force `package test;`, and a named package cannot import default-package classes at all.
- **Merge test files to one per production class** with `@Nested` per method, at project completion (Vision v3 §7).
- **Build file** (`pom.xml`): one classpath for IntelliJ, VS Code and the command line; needed for JMH at step 6. With it, move to Maven layout (`src/main/java`, `src/test/java`) and a real package. VS Code currently cannot resolve `org.junit` because it does not read IntelliJ's `.iml` (stopgap: `java.project.referencedLibraries` in `.vscode/settings.json`, JUnit 6.0.0 jars only).
- Cached best-level pointer and node pooling: step 6 benchmarks.
- Poison-pill policy: step 7.

---

## 7. Opening message for the next session

> Attach `ExchangeServer_Vision_v3.md` and `ExchangeServer_Spec_v6.md`. Follow the session rules in Spec v6 §1 (code first, questions after). We are in roadmap step 2 (§5). I will code from the short descriptions; review my saved code, show me where I went wrong, test my understanding, then write and run the tests. The code is in the local repo (`src/`, default package); read it directly rather than asking me to paste it.
