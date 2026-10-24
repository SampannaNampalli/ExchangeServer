# ExchangeServer — Spec v7

**Supersedes:** Spec v6 (and v3–v5). Self-contained: nothing needs to be read from earlier specs.
**Companion:** `ExchangeServer_Vision_v3.md` (purpose, working style, principles, **roadmap**; the roadmap is not repeated here).
**Verified on 2026-10-20 against the committed tree** (`5069565`, pushed, local = `origin/main`, working tree clean): compiles, **204 tests, all green**.
**Status:** roadmap steps 1 and 2 complete. Next: roadmap step 3 (sequencer + event log), see §5.

---

## 1. Session rules (read first)

These refine Vision v3 §3 and are the agreed way of working:

1. **Code first, questions after** (Vision v3 §3.6). The LLM gives a short description of what to build (a few lines per behaviour, no hints, no edge-case lists, no code) -> the user designs and codes it -> the LLM reviews the saved code and shows where it goes wrong (location + concrete failing input, not the fix) -> the LLM tests the user's understanding of the reasoning behind each piece -> the user fixes; repeat until clean -> the LLM writes and runs the tests.
2. **When the user is stuck** after a round on the same point, or asks directly: the LLM may give options with trade-offs and a recommendation, or a small snippet / a neutral-example pattern. The user still writes the code.
3. **The LLM writes the tests.** It compiles and runs them against a scratch copy of the saved code before handing them over, and states the result. It also **breaks the code on purpose** (a few one-line mutations) and reports how many of the new tests fail, so a green run means something. The user reads each test.
4. **The LLM never edits Java source files.** All code (tests included) is given in chat; the user pastes it. Bulk mechanical test changes are given as IntelliJ *Replace in Files* (Ctrl+Shift+R) with **file mask `*Test.java`** (production code contains similar text, e.g. `Instant.now())` in `new Trade(...)`). Editing the docs in `docs/` when asked is fine.
5. **Review only saved files.** Read the working tree (`git diff`, untracked files) directly; if what the user describes is not in the file, say so and ask them to save.
6. **Name the real reason** for every check or design choice; call out stale rationales and non-reasons ("it's only nanoseconds", "the code is optimal") (Vision principle 4).
7. **How to run the tests** (no build file exists): compile `src/*.java` with `javac` (JDK 17), classpath = JUnit jars from `~/.m2/repository` (`junit-jupiter-api`/`-engine` 5.11.4, `junit-platform-commons`/`-engine`/`-launcher` 1.11.4, `opentest4j` 1.3.0, `apiguardian-api` 1.1.2), and run them with a small `LauncherFactory` + `SummaryGeneratingListener` main that takes test class names as arguments. Use Windows-form paths (`C:/...`) and `;` as the classpath separator. IntelliJ runs them via its own JUnit module library.

---

## 2. Current state

All classes are in the **default package** in `src/`. Single instrument.

| Class | What it is now |
|---|---|
| `Side` | `BUY`, `SELL`. |
| `OrderType` | `LIMIT`, `IOC`, `FOK`, `MARKET`, `POST_ONLY`. All five are implemented. |
| `OrderStatus` | `FILLED`, `RESTED`, `CANCELLED`, `REJECTED` (outcome of a submit). |
| `Order` | Final `orderId`, `side`, `price` (ticks), `originalQuantity`, `timestamp`, `orderType`; mutable `filledQuantity`. Constants `MAX_ORDER_QUANTITY = 1_000_000_000`, `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (D20), `NO_PRICE = -1` (D26). Constructor (the real invariant, D19) rejects with `IllegalArgumentException`: null/invalid side, id/quantity `<= 0`, `price > MAX_PRICE`, `quantity > MAX_ORDER_QUANTITY`, null type, MARKET with a price other than `NO_PRICE`, any other type with `price <= 0`. **Never defaults the type.** `getPrice()` throws `IllegalStateException` for MARKET. `fill(q)` rejects `q <= 0` and overfill. |
| `OrderRequest` | Gateway-side stateless validation (D19), same rules as `Order`, all `IllegalArgumentException`. Public constructor `(side, price, quantity, type)`; factories `limit(side, price, quantity)` (the "client didn't say a type" case) and `market(side, quantity)` (stores `NO_PRICE`; no price parameter). The old three-argument constructor is gone. |
| `Response` | `record Response(long orderId, List<Trade> trades, OrderStatus orderStatus, long remainingQuantity)` (D22). |
| `Trade` | `quantity`, `price` (always the resting level's price), `buyOrderId`, `sellOrderId`, `timestamp`. |
| `CancelResult`, `CancelStatus` | `record CancelResult(CancelStatus status, long cancelledQuantity)`; `enum CancelStatus { CANCELLED, UNKNOWN_ORDER }` (D17). |
| `OrderNode` (pkg-private) | `final Order order`; `prev`, `next`, `level`. Real nodes require non-null order; sentinels only via `OrderNode.sentinel()`. |
| `PriceLevel` | Doubly linked list with sentinel `head`/`tail`; `totalVolume`, `noOfOrders`. `OrderNode addOrder(Order)` (pkg-private; all checks before mutating), `Order peekFirstOrder()`, `long fillHeadOrder(q)` (fills head only, never removes), `void unlink(OrderNode)` (pkg-private; guards D9; O(1)), `isFullyConsumed()`, test-only walks `ordersHeadToTail()` / `ordersTailToHead()`. |
| `OrderBook` | `bids` (`TreeMap`, reverse order), `asks` (`TreeMap`), `orderMap: HashMap<Long, OrderNode>`. `addOrderToBook(Order)` (all checks before any mutation; duplicate id throws), `removeOrderFromBook(id)` (pkg-private; one `get` + null check; unlink -> map remove -> eager level removal), private `removePriceLevel(level, side)`, `CancelResult cancel(id)`, `bestBid/bestAsk/bestLevel(side)`, `getVolume(side, price)`, `getNoOfBids/Asks()`, **read-only** `askPriceLevelIterator()` / `bidPriceLevelIterator()` (over `Collections.unmodifiableCollection(values())`, best price first; `remove()` throws) (D30), test-only `containsOrder(id)`. |
| `MatchingEngine` | Holds `OrderBook` (passed in by the caller) and `nextOrderId` (starts at 1). Public: `Response submit(OrderRequest)` = `process(createOrder(req))`; `CancelResult cancel(long id)` (delegates to the book, D21); test-only `Response submit(Order)`. Pkg-private: `createOrder(...)` (both overloads), `process`, `canBeFilled`, `match`, `crosses` (both overloads). `process` shape in D24. `crosses(Order, PriceLevel)` returns `true` for MARKET before reading any price. |
| `Main` | Empty `main` plus commented-out scratch code (§6). |

**Order types (behaviour, all tested):**

| Type | Fully fills now | Partly fills now | Nothing crosses |
|---|---|---|---|
| LIMIT | FILLED, 0 | RESTED, remainder rests | RESTED, all rests |
| IOC | FILLED, 0 | CANCELLED, remainder | CANCELLED, all |
| FOK | FILLED, 0 | *(cannot happen: killed instead)* CANCELLED, all, no trades | CANCELLED, all |
| MARKET (no price; crosses every level) | FILLED, 0 | CANCELLED, remainder (other side ran out) | CANCELLED, all (other side empty) |
| POST_ONLY | *(cannot happen: rejected)* | *(cannot happen: rejected)* | RESTED, all rests |
| POST_ONLY that would cross on arrival (equal price included) | REJECTED, all, no trades, book unchanged | | |

**Invariants (all tested):**
- `PriceLevel.totalVolume` = sum of remaining over linked nodes; `noOfOrders` = number of linked nodes.
- An id is in `orderMap` iff its order rests in a level.
- The book never holds an empty price level.
- `addOrderToBook` is all-or-nothing: if it throws, the book is unchanged.
- An invalid `Order` cannot be constructed.
- For every `Response`: original quantity = sum of trade quantities + `remainingQuantity`.
- A FOK or post-only that does not go ahead leaves the book exactly as it was (no match-then-undo; resting orders keep their time priority).
- Only LIMIT and POST_ONLY orders ever rest.

**Tests (204):** `PriceLevelTest` 26, `OrderBookTest` 20, `OrderTest` 20, `MatchingEngineMatchTest` 18, `OrderRequestTest` 17, `MatchingEngineFokTest` 14, `MatchingEngineIocTest` 13 (includes LIMIT `Response` tests), `MatchingEnginePostOnlyTest` 13, `OrderTypeValidationTest` 13, `MatchingEngineCancelTest` 9, `MatchingEngineMarketTest` 9, `MatchingEngineSubmitTest` 9, `MatchingEngineCrossesTest` 8, `MatchingEngineTest` 8 (its `CrossesTest` duplicates `MatchingEngineCrossesTest`), `MatchingEngineBookCleanupTest` 7.

**Complexity:** cancel / fill-removal of a node O(1); removing an emptied level O(log L); best-level lookup `firstEntry()` O(log L) per match iteration. FOK pre-check walks at most the crossing levels and at most `quantity` levels (each level holds >= 1 unit); a FOK that fills walks those levels twice (check, then match). Post-only pre-check: one best-level lookup.

**Git:** step 2 is committed and pushed: `8f91bc5` ("Add order type IOC": engine cancel, `Response`, IOC) and `5069565` ("Add remaining order types - FOK, MARKET and POST_ONLY"). `docs/` is now tracked (the `docs/` line was removed from `src/.gitignore`), including `Prompts.MD` and the GPT text: if the GitHub repo is public, so are they. Commit author is now set correctly (one older commit still says "Your Name"; pushed history is not rewritten).

**Pace (for estimating):** step 2 took about 22 review rounds over 2026-10-18..20 (A cancel 4, IOC 7, FOK 4, Market 5, Post-only 2) against an estimate of 10–15. Most extra rounds came from the response-shape design and from type lists kept in several places.

---

## 3. Decisions (settled)

| # | Decision | Reason |
|---|---|---|
| D1 | `OrderBook` owns the `orderId -> OrderNode` map. | Only the code that mutates levels can keep "id in map iff resting" true. The engine never touches the map. |
| D2 | One `unlink(node)` serves fill-removal and cancel. | One code path, one place for invariants. |
| D3 | `fillHeadOrder()` only fills; never removes. | Otherwise the book never learns of the removal and the map goes stale. |
| D4 | `match()` calls `removeOrderFromBook` when the resting order hits 0. | The engine already holds the book. |
| D5 | `OrderNode` wrapper, not link fields in `Order`. | `Order` stays a domain object; only `PriceLevel`/`OrderBook` hold nodes. Cost ~32 B + one pointer hop per resting order. Revisit at step 6 only if benchmarks show it; try node pooling before making `Order` intrusive. |
| D6 | Self-implemented doubly linked list with caller-held node handles. | `java.util.LinkedList` hides nodes (O(n) remove); a `ListIterator` is invalidated by other modifications, so it cannot sit in a map. |
| D7 | Sentinel head/tail. | Every real node has non-null `prev`/`next`, so link/unlink need no head/tail/only-node branches. |
| D8 | `unlink` nulls `prev`, `next`, `level`. | Stale nodes are detectable. |
| D9 | `unlink` throws `IllegalStateException` for a sentinel or a node whose `level != this`. | Unguarded, a foreign node would be spliced out of the *other* level's list while this level's counters drop: silent corruption of both. |
| D10 | Eager removal of emptied levels. | Strict invariant, simple best-level lookup; O(log L) per emptied level. |
| D11 | Cached best-level pointer deferred to benchmarking. | Measure first. |
| D12 | Log source of truth = sequenced input commands, replayed through the real engine. | Live and replay paths cannot drift. Requires determinism (§4). |
| D13 | State-dependent rejects (cancel of unknown id, post-only that would cross) stay in the log. | Write-ahead logs a command before the engine processes it; the gateway has no book, so nobody can know it is a reject until after it is logged. Replaying it in the same position reproduces the same reject. |
| D14 | Drained-level removal happens in `removeOrderFromBook`, not in `process()`. | Follows from D1, D3, D4, D10. |
| D15 | Duplicate order id in `addOrderToBook` throws. | Ids come from our own code, so a duplicate is an internal invariant violation, not a client reject. |
| D16 | Order ids are never reused; monotonically increasing; gaps allowed. | Logged cancels, trade history and client acks refer to ids and would become ambiguous. An id used by an order that never rests (filled, IOC/FOK/market leftover cancelled, post-only rejected) is still in its `Response` and the log, so a gap is harmless. |
| D17 | `cancel` returns `CancelResult(status, cancelledQuantity)`; `UNKNOWN_ORDER` = not resting (never existed, filled, already cancelled, or an order type that never rests); `cancelledQuantity` = remaining at cancel time, 0 on reject. `NOT_OWNER` deferred until orders carry a participant id. | Clients need the reject reason and how much was cancelled vs filled. The common `UNKNOWN_ORDER` case is a race (the order filled while the cancel was in flight), a normal outcome, so a return value, not an exception. |
| D18 | Level removal is private to `removeOrderFromBook` and checks that `TreeMap.remove` returned the emptied level. | One owner for level removal; wrong-side/wrong-key bugs fail loudly. |
| D19 | Validation in exactly two layers: `OrderRequest` (gateway, stateless, fail fast) and the `Order` constructor (the real invariant: replay and tests build `Order`s directly). No checks in `createOrder`; no guard in `crosses`. | Middle-layer copies added nothing and drifted apart. Stateless rejects never enter the log. |
| D20 | Limits are code constants in `Order`, inclusive: `1 <= price <= MAX_PRICE`, `1 <= quantity <= MAX_ORDER_QUANTITY`; `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (9,223,372,036). MARKET is the one exception to the price range (D26). | `price × quantity` (notional) can never overflow a `long`. Constants, not config: a config change between a live run and its replay would make logged commands poison pills. |
| D21 | Clients cancel through `MatchingEngine.cancel(id)` (public), which delegates to `OrderBook.cancel`. The engine never calls its own public `cancel` while processing another command. | One entry point on one thread gives **one total order of commands**; replay runs the same sequence through the same code. A self-call would log a cancel nobody sent and run twice on replay. Enforcement is by convention for now (§6). |
| D22 | `submit` returns `Response(orderId, trades, orderStatus, remainingQuantity)`. `remainingQuantity` means: FILLED -> 0; RESTED -> rests on the book; CANCELLED -> cancelled; REJECTED -> all. Filled quantity = sum of `trades`. | Fields cannot contradict each other (original = filled + remaining); the status alone says how to read the quantity. The `orderId` is how a client cancels an order that rested without trading. |
| D23 | Order outcomes use four statuses; **REJECTED is only for post-only** that would cross. A FOK that cannot fill and an IOC/market leftover are CANCELLED. | The client's next action differs: "not enough liquidity" (same as IOC finding nothing) vs "my price crossed, reprice". FIX also reports an unfilled FOK as cancelled/expired, not rejected. |
| D24 | `process` shape: (1) type-specific **pre-checks that change nothing** (FOK `canBeFilled`, post-only best-level cross check); (2) the shared matching loop, which FOK runs only if fillable and post-only never runs; (3) the outcome as a `switch` **expression over `OrderType` with no `default`**. A fillable FOK left with a remainder throws `IllegalStateException`. | All checks before any mutation (same rule as `addOrderToBook`): undoing a match is not possible (removed resting orders would lose time priority; trades may already be published). The compiler rejects a `switch` expression that misses an enum value, so a new type cannot silently fall into another type's behaviour. The FOK throw turns a prediction/matching disagreement into a loud failure instead of a partial fill reported as FILLED. |
| D25 | The LIMIT default lives only at the gateway, as `OrderRequest.limit(...)`. A null type throws in both layers. | "The client didn't say" means "didn't pass a type". Past the gateway, a null type is our bug (e.g. a replay losing the field) and must not silently become a LIMIT that rests. |
| D26 | A market order has no price: stored as `Order.NO_PRICE` (`-1`); both layers enforce MARKET ⇔ `NO_PRICE`; `Order.getPrice()` throws for MARKET; clients build it with `OrderRequest.market(side, quantity)`, never by sending `-1`. | A fake price would lie ("pay at most 1") and make a risk check compute a tiny notional for an order that can sweep the book. A sentinel keeps a primitive `long` on the hot path; the throwing getter makes any accidental read fail loudly. Keeping the sentinel out of the client API lets it change later (e.g. to a wrapper type) without breaking clients. |
| D27 | Exception convention: `IllegalArgumentException` = bad input (both validation layers); `IllegalStateException` = our bug / broken invariant. | One meaning per exception type, so a reader knows whose fault it is. |
| D28 | `canBeFilled` lives in the engine and uses the engine's `crosses`; the book exposes levels through read-only iterators. | One definition of "crosses" (a second copy in the book could disagree with matching). Principle 9: the book exposes state, the engine decides what it means. `entrySet().iterator()` would have let callers `remove()` a level and break the id-map invariant. |
| D29 | Post-only that would cross is rejected, not slid one tick away. | Respects the client's price; the cost is that the client must resend (a round trip) and may lose queue time. Slide would trade price control for queue time. |
| D30 | "Is this order type supported?" is a stateless check: gateway rejects it (IAE); if it ever gets past, the engine throws (ISE). | It depends only on the type field and the code version, not on the book. (Moot now that all five types exist, but it is the rule for any future type.) |

---

## 4. Log, replay, determinism (design for roadmap steps 3 and 7)

- Sequenced **input commands** (new order, cancel) are the source of truth; outputs (`Response`s, trades, fills, market data) are derived by replay.
- **Write-ahead:** a command is logged before the engine processes it.
- **Reject split:** stateless rejects are caught at the gateway and never logged; state-dependent rejects are decided by the engine and logged (D13).
- **Determinism:** sequence number, timestamp and order id are stamped on the command *before* logging. No wall clock, randomness or `HashMap` iteration order in matching (the id map is only looked up; level walks use `TreeMap` order). Single writer thread.
- **Known gaps:** `createOrder()` and `match()` call `Instant.now()`; the engine (not a sequencer) assigns order ids (`nextOrderId`). Fix when the sequencer arrives: use the command's stamped timestamp and id.
- **Poison pill:** a logged command that crashes the engine crashes every replay. Needs a policy (skip/quarantine); roadmap step 7. Note: `IllegalStateException`s thrown by the engine (D24, D27) are exactly such crashes if they ever fire.
- **Output event shape:** an order that never rests: received -> match(es) -> done. "Open/rested" only for orders that go on the book. No fabricated events.

---

## 5. Next: roadmap step 3 — sequencer + event log

Not started. Short descriptions only (session rule 1): the user designs and codes, then review. Suggested order:

- **A. Commands as data.** A new order and a cancel each become a command object that carries everything the engine needs to process it, including a sequence number, a timestamp and (for new orders) the order id.
- **B. Sequencer.** One component gives every command its sequence number, timestamp and order id, in arrival order. The engine uses those stamped values and no longer reads the clock or counts ids itself.
- **C. Write-ahead input log.** Every sequenced command is appended to a durable log before the engine processes it. Stateless rejects never reach it.
- **D. Replay.** A fresh engine fed the log from the start ends with the same book and produces the same outputs as the live run.

---

## 6. Open threads

**Deferred on purpose (this session)**
- **Log durability: fsync per append now; batching decided in step 4 or 6** (decided 2026-10-22). Step 3C forces every command to disk before `append` returns, and only then does the engine process it (~1–2 ms per fsync measured on the dev laptop, so roughly 500–1,000 commands/s). Write-ahead rule: no effect (trade, response) may be visible before its record is durable, so a batch is processed **after** its fsync, never before. Batching needs many commands waiting at once (a queue, an asynchronous entry point, a writer thread: the step 4 thread model). With today's synchronous callers, every batch holds one command. Options to compare with measurements:
  1. **fsync per append** (current): simple; latency = one fsync; throughput capped by fsync time.
  2. **Group commit with a timeout:** close a batch at N commands or T ms; one fsync per batch. High throughput, but under light load each command also waits up to T.
  3. **Smart batching** (Martin Thompson / LMAX): the writer takes whatever is already waiting each time it is ready, writes it, fsyncs once, then processes it. No timer: under light load the batch is one command (same as option 1); under heavy load batches grow on their own. Usually capped at a maximum batch size. Adapts better than option 2, but still needs the queue and writer thread.
- **Single-writer enforcement.** The engine takes its `OrderBook` from the caller, who keeps a reference and can call `book.cancel` / `addOrderToBook` directly, bypassing the engine (and, from step 3, the log). Plan: the engine creates its own book; tests get it via a package-private accessor (~7 test files build `new MatchingEngine(orderBook)`). Do it with the move to a real package + build file, when package-private starts to mean something.
- **RESTED snapshot.** `Response.remainingQuantity` for RESTED is the amount resting *at submit time*; later fills reach the client only as trades. Needs fill/execution events for the resting side (step 3 outputs / step 5 market data).
- **Market order price protection** (step 5, risk checks). A market BUY for 1,000 against asks 10 @ 100 and 990 @ 5,000 fills at an average of ~4,951. Real venues use price bands/collars/protection points: trade only within some distance of the best price or last trade; cancel or convert the rest to a limit at the band.
- **Sentinel reachable through the public constructor.** `new OrderRequest(side, Order.NO_PRICE, qty, MARKET)` is accepted. To keep `-1` fully out of client hands: make the four-argument constructor private and add factories for IOC, FOK, post-only.
- **`canBeFilled` uses `getOriginalQuantity()`.** Equal to remaining for every order on the real path (orders reach `process` brand new). `getRemainingQuantity()` would hold without that assumption. User's call; not a defect.

**Small code items**
- `OrderBook.removePriceLevel` still has checks that `pl != priceLevel` already covers (`isEmpty`, `containsKey`, `isFullyConsumed`).
- `OrderBook.java` imports `jdk.jfr.Description`, unused.
- `Main.java` is an empty `main` plus commented-out scratch code (builds orders with id 0 and the old constructor). Delete, or keep as a demo once the gateway exists.
- Post-only pre-check in `process`: `if (...)` without braces, body at the condition's indent. Correct, reads as unconditional.
- Test-only public `MatchingEngine.submit(Order)` bypasses the gateway and id assignment. Remove when tests no longer need it.
- `src/.gitignore` mostly duplicates the root `.gitignore`.

**Design threads**
- Participant id on `Order`: needed for `NOT_OWNER` cancel rejects and self-trade prevention. When added, decide whether a non-owner cancel returns `NOT_OWNER` or `UNKNOWN_ORDER` (avoids revealing that someone else's id exists). Or drop self-trade prevention from scope.
- Principle 2 tension: `PriceLevel.fillHeadOrder()` holds fill logic and `match()` calls it directly. Related: `bestLevel` and the read-only iterators hand out `PriceLevel` objects whose public `fillHeadOrder` can still mutate a level.
- Thread model: gateway connections -> ordered queue -> single writer (roadmap step 4).
- Spring Boot vs plain TCP gateway (Vision v3 §8).
- Real-world anchors: lot sizes, tick sizes, typical large-trade sizes, and how they should shape the D20 limits.

**Deferred on purpose (earlier)**
- **Move tests out of `src/`.** Plan: a sibling source root `Exchange Server/test/` marked "Test Sources Root" in IntelliJ, files keep **no package line** (same default package, so package-private access still works). **Not** `src/test/`: that would force `package test;`, and a named package cannot import default-package classes at all.
- **Merge test files to one per production class** with `@Nested` per method, at project completion (Vision v3 §7). Includes removing `MatchingEngineTest`'s duplicate `CrossesTest`.
- **Build file** (`pom.xml`): one classpath for IntelliJ, VS Code and the command line; needed for JMH at step 6. With it, move to Maven layout and a real package (then do single-writer enforcement). VS Code currently cannot resolve `org.junit` (stopgap: `java.project.referencedLibraries` in `.vscode/settings.json`).
- Cached best-level pointer and node pooling: step 6 benchmarks.
- Poison-pill policy: step 7.

**Resolved since v6:** `Order` rejects a null side; `removeOrderFromBook` does one lookup; `submit(OrderRequest)` is `process(createOrder(req))`; the draft header comment in `OrderBook.java` is gone; `docs/` is tracked in git; commit author set; `CancelResult` component is `status`.

---

## 7. Opening message for the next session

> Attach `ExchangeServer_Vision_v3.md` and `ExchangeServer_Spec_v7.md`. Follow the session rules in Spec v7 §1 (code first, questions after). We are in roadmap step 3 (§5). I will code from the short descriptions; review my saved code, show me where I went wrong, test my understanding, then write and run the tests (with mutation checks). The code is in the local repo (`src/`, default package); read it directly rather than asking me to paste it.
