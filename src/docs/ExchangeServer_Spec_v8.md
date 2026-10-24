# ExchangeServer — Spec v8

**Supersedes:** Spec v7 (and v3–v6). Self-contained: nothing needs to be read from earlier specs.
**Companion:** `ExchangeServer_Vision_v3.md` (purpose, working style, principles, **roadmap**; the roadmap is not repeated here).
**Learning notes:** `New Learnings/01_File_IO_Durability_and_WAL.md` (file I/O, durability, WAL; labs) and `New Learnings/02_Step3C_CommandJournal_Explained.md` (walkthrough of the journal code, failure model, interview questions), both at the project root.
**Verified on 2026-10-24 against the working tree** (step 3C committed as `2cdca7c`; step 3D committed by the user right after this spec was written): compiles, **281 tests, all green**.
**Status:** roadmap steps 1, 2 and 3 complete. Next: roadmap step 4 (TCP gateway with a single-writer engine thread), see §5.

---

## 1. Session rules (read first)

These refine Vision v3 §3 and are the agreed way of working:

1. **Code first, questions after** (Vision v3 §3.6). The LLM gives a short description of what to build (a few lines per behaviour, no hints, no edge-case lists, no code) -> the user designs and codes it -> the LLM reviews the saved code and shows where it goes wrong (location + concrete failing input, not the fix) -> the LLM tests the user's understanding of the reasoning behind each piece -> the user fixes; repeat until clean -> the LLM writes and runs the tests.
2. **When the user is stuck** after a round on the same point, or asks directly: the LLM may give options with trade-offs and a recommendation, or a small snippet / a neutral-example pattern. The user still writes the code.
3. **The LLM writes the tests.** It compiles and runs them before reporting, and states the result. It also **breaks the code on purpose** (one-line mutations on a scratch copy) and reports how many tests fail per mutation, so a green run means something. Survivors are reported and explained. The user reads each test.
4. **Who edits what.** The LLM edits **test files (`*Test.java`) directly** (since 2026-10-21; no more pasting). It **never edits production Java files**: it reviews them, and the user writes the code. One agreed exception so far: step 3C's production code was written by the LLM on 2026-10-23 at the user's explicit request ("just this once"), with an explanation file (`New Learnings/02`). Editing docs (`docs/`, `New Learnings/`) when asked is fine.
5. **Review only saved files.** Read the working tree (`git diff`, untracked files) directly; if what the user describes is not in the file, say so and ask them to save.
6. **Name the real reason** for every check or design choice; call out stale rationales and non-reasons (Vision principle 4).
7. **How to run the tests** (no build file exists): compile `src/*.java` with `javac` (JDK 17), classpath = JUnit jars from `~/.m2/repository` (`junit-jupiter-api`/`-engine` 5.11.4, `junit-platform-commons`/`-engine`/`-launcher` 1.11.4, `opentest4j` 1.3.0, `apiguardian-api` 1.1.2), and run them with a small `LauncherFactory` + `SummaryGeneratingListener` main that takes test class names as arguments. Use Windows-form paths (`C:/...`) and `;` as the classpath separator. IntelliJ runs them via its own JUnit module library. VS Code shows false "org.junit cannot be resolved" errors (no classpath); ignore them.
8. **Environment facts that matter:** Windows 11, Temurin JDK 17; the default charset is **windows-1252** (always pass `UTF_8`; `javac` needs `-encoding UTF-8` for non-ASCII sources); fsync costs ~1–2 ms on the dev laptop.

---

## 2. Current state

All classes are in the **default package** in `src/`. Single instrument.

| Class | What it is now |
|---|---|
| `Side` | `BUY`, `SELL`. |
| `OrderType` | `LIMIT`, `IOC`, `FOK`, `MARKET`, `POST_ONLY`. All five are implemented. |
| `OrderStatus` | `FILLED`, `RESTED`, `CANCELLED`, `REJECTED` (outcome of a submit). |
| `Order` | Final `orderId`, `side`, `price` (ticks), `originalQuantity`, `timestamp`, `orderType`; mutable `filledQuantity`. Constants `MAX_ORDER_QUANTITY = 1_000_000_000`, `MAX_PRICE = Long.MAX_VALUE / MAX_ORDER_QUANTITY` (D20), `NO_PRICE = -1` (D26). Constructor `(id, side, price, quantity, timestamp, type)` rejects with `IllegalArgumentException`: null/invalid side, id/quantity `<= 0`, `price > MAX_PRICE`, `quantity > MAX_ORDER_QUANTITY`, null type, MARKET with a price other than `NO_PRICE`, any other type with `price <= 0`. Does **not** check the timestamp for null. `getPrice()` throws `IllegalStateException` for MARKET. `fill(q)` rejects `q <= 0` and overfill. |
| `OrderRequest` | Gateway-side stateless validation (D19), same rules as `Order`, all `IllegalArgumentException`. Public constructor `(side, price, quantity, type)`; factories `limit(side, price, quantity)` and `market(side, quantity)` (stores `NO_PRICE`). Immutable. |
| `Command` | **new (3C).** `sealed interface Command permits NewOrderCommand, CancelOrderCommand` with `getSequenceNumber()`, `getTimeStamp()` (D31). |
| `NewOrderCommand` | **new (3A).** `final`, implements `Command`: `OrderRequest`, sequence number, timestamp, order id. No validation of its own. |
| `CancelOrderCommand` | **new (3A).** `final`, implements `Command`: sequence number, timestamp, **target** order id. |
| `Sequencer` | **new (3B).** The only component that reads the clock or counts. Fields hold the **next** sequence number and the **next** order id (both start at 1) and the previous timestamp. `createStampedNewOrderCommand(request)` uses one of each; `createStampedCancelOrderCommand(targetId)` uses a sequence number and a timestamp but no order id (D32). Timestamps never go backwards: if the clock steps back, it reuses the previous timestamp (D33). Package-private restore setters for replay: `setNextSequenceNumber`, `setNextNewOrderId`, `setPreviousTimeStampInstant` (D41). |
| `CommandJournal` | **new (3C).** The write-ahead input log, a plain text file (D36). `static open(Path)` cuts a torn last record, then opens a `FileChannel` with `CREATE, WRITE, APPEND`; private constructor. `append(Command)` encodes, writes, `force(true)`, then returns (D37); after any write/fsync failure it refuses every later append (fail-stop, D39). `readAll()` reads **bytes** (not lines), decodes every record, throws `IllegalStateException` on any record that does not parse or has no `\n` (D38). `isEmpty()`, `getTruncatedBytesOnOpen()`, `close()`. Package-private `encode`/`decode`. |
| `Replay` | **new (3D).** `startReplay()` reads the journal and feeds every command, in order, to the engine's `submitNewOrderCommand` / `submitCancelOrderCommand`; outputs are discarded (D41). Verifies sequence numbers are exactly 1, 2, 3, … and timestamps never decrease (D42). Exposes `getLastSequenceNumber()`, `getLastNewOrderId()` (from the last `NEW` record), `getLastTimeStampInstant()`. |
| `Exchange` | **new (3C, extended in 3D).** The one live path (D40). Constructor `(Sequencer, CommandJournal, MatchingEngine)`: if the journal is not empty, runs `Replay` and restores the sequencer (next seq = last + 1, next id = last new-order id + 1, previous timestamp = last timestamp); if replay throws, closes the journal and rethrows (D43). `submit(OrderRequest)`: `requireNonNull` -> stamp -> `journal.append` -> `engine.submitNewOrderCommand`. `cancel(long)`: stamp -> append -> `engine.submitCancelOrderCommand`. `close()` closes the journal. |
| `Response` | `record Response(long orderId, List<Trade> trades, OrderStatus orderStatus, long remainingQuantity)` (D22). |
| `Trade` | `quantity`, `price` (always the resting level's price), `buyOrderId`, `sellOrderId`, `timestamp` (= the incoming command's timestamp, D34). |
| `CancelResult`, `CancelStatus` | `record CancelResult(CancelStatus status, long cancelledQuantity)`; `enum CancelStatus { CANCELLED, UNKNOWN_ORDER }` (D17). |
| `OrderNode` (pkg-private) | `final Order order`; `prev`, `next`, `level`. Real nodes require non-null order; sentinels only via `OrderNode.sentinel()`. |
| `PriceLevel` | Doubly linked list with sentinel `head`/`tail`; `totalVolume`, `noOfOrders`. `OrderNode addOrder(Order)` (pkg-private), `peekFirstOrder()`, `fillHeadOrder(q)` (fills head only, never removes), `unlink(OrderNode)` (pkg-private; guards D9; O(1)), `isFullyConsumed()`, test-only walks `ordersHeadToTail()` / `ordersTailToHead()` (pkg-private). |
| `OrderBook` | `bids` (`TreeMap`, reverse order), `asks` (`TreeMap`), `orderMap: HashMap<Long, OrderNode>`. `addOrderToBook(Order)` (all checks before any mutation; duplicate id throws), `removeOrderFromBook(id)` (pkg-private), private `removePriceLevel`, `CancelResult cancel(id)`, `bestBid/bestAsk/bestLevel(side)`, `getVolume(side, price)`, `getNoOfBids/Asks()`, read-only `askPriceLevelIterator()` / `bidPriceLevelIterator()` (best first; `remove()` throws), test-only `containsOrder(id)`. |
| `MatchingEngine` | Holds `OrderBook` (passed in) and `prevOrderId` (starts at 0). **Reads no clock and counts no ids.** Public: `Response submitNewOrderCommand(NewOrderCommand)` (rejects an order id `<= prevOrderId` with `IllegalStateException` before anything else, D35; then `process(createOrder(...))`), `CancelResult submitCancelOrderCommand(CancelOrderCommand)` (delegates to `orderBook.cancel`), test-only `Response submit(Order)`. Pkg-private: `createOrder(id, side, price, quantity, type, timestamp)` and `createOrder(id, request, timestamp)`, `process`, `canBeFilled`, `match`, `crosses` (both overloads). `process` shape in D24. **Removed in step 3:** `submit(OrderRequest)`, `createOrder(OrderRequest)`, `nextOrderId`, public `cancel(long)`. |
| `Main` | 74 lines: empty `main` plus commented-out scratch code (§6). |

**Order types (behaviour, all tested):**

| Type | Fully fills now | Partly fills now | Nothing crosses |
|---|---|---|---|
| LIMIT | FILLED, 0 | RESTED, remainder rests | RESTED, all rests |
| IOC | FILLED, 0 | CANCELLED, remainder | CANCELLED, all |
| FOK | FILLED, 0 | *(cannot happen: killed instead)* CANCELLED, all, no trades | CANCELLED, all |
| MARKET (no price; crosses every level) | FILLED, 0 | CANCELLED, remainder (other side ran out) | CANCELLED, all (other side empty) |
| POST_ONLY | *(cannot happen: rejected)* | *(cannot happen: rejected)* | RESTED, all rests |
| POST_ONLY that would cross on arrival (equal price included) | REJECTED, all, no trades, book unchanged | | |

**The pipeline now:**

```
OrderRequest (stateless validation) -> Exchange.submit / cancel
    -> Sequencer stamps (seq, timestamp, id)
    -> CommandJournal.append  (written + fsynced; fail-stop on error)
    -> MatchingEngine         (deterministic; no clock, no counter)
restart: CommandJournal.open (cut torn tail) -> Replay (verify + feed the same engine) -> restore Sequencer
```

**Journal format** (D36): one record per line, `,`-separated, every record ends with `\n` (never `\r\n`):

```
NEW,<seq>,<timestamp>,<orderId>,<side>,<price>,<quantity>,<type>      e.g. NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT
CANCEL,<seq>,<timestamp>,<targetOrderId>                              e.g. CANCEL,3,2026-01-01T10:00:00Z,1
```

MARKET's price field is `-` (never `-1`). Enums by `name()`; timestamps by `Instant.toString()` (ISO-8601, exact to the nanosecond).

**Invariants (all tested):**
- `PriceLevel.totalVolume` = sum of remaining over linked nodes; `noOfOrders` = number of linked nodes.
- An id is in `orderMap` iff its order rests in a level.
- The book never holds an empty price level.
- `addOrderToBook` is all-or-nothing: if it throws, the book is unchanged.
- An invalid `Order` cannot be constructed.
- For every `Response`: original quantity = sum of trade quantities + `remainingQuantity`.
- A FOK or post-only that does not go ahead leaves the book exactly as it was.
- Only LIMIT and POST_ONLY orders ever rest.
- **(step 3)** The engine's behaviour depends only on the commands it is given (no clock, no counter, no randomness, no hash-order dependence).
- **(step 3)** Sequence numbers are contiguous from 1; order ids strictly increase; timestamps never decrease, in the journal and across restarts.
- **(step 3)** Visible ⇒ durable: no trade or response exists for a command that is not fsynced in the journal.
- **(step 3)** Restarting on a journal rebuilds the identical book (levels, orders, time priority, remaining quantities, timestamps), and the journal alone reproduces every live output.
- **(step 3)** Replay never writes to the journal.

**Tests (281):** `PriceLevelTest` 26, `CommandJournalTest` 23, `OrderTest` 20, `OrderBookTest` 20, `MatchingEngineMatchTest` 18, `MatchingEngineCommandTest` 18, `OrderRequestTest` 17, `ReplayTest` 16, `MatchingEngineFokTest` 14, `OrderTypeValidationTest` 13, `MatchingEnginePostOnlyTest` 13, `MatchingEngineIocTest` 13, `SequencerTest` 11, `MatchingEngineSubmitTest` 9, `MatchingEngineMarketTest` 9, `MatchingEngineCancelTest` 9, `ExchangeTest` 9, `MatchingEngineTest` 8 (its `CrossesTest` duplicates `MatchingEngineCrossesTest`), `MatchingEngineCrossesTest` 8, `MatchingEngineBookCleanupTest` 7.

How the older engine tests get commands: each of the `Cancel`, `Fok`, `Ioc`, `Market`, `PostOnly`, `Submit` test classes owns a `Sequencer` and has helpers `submit(OrderRequest)` / `cancel(long)` that stamp a command and call the engine (ids still start at 1, so old expectations hold).

**Mutation results worth remembering:** every step 3 mutation is caught except (a) removing `force` in `append` or after the tail cut (no unit test can see whether data reached the disk; covered by review) and (b) removing the sequencer's backwards-clock handling (the real clock never goes back during a test; needs an injected `Clock`, §6).

**Complexity:** cancel / fill-removal of a node O(1); removing an emptied level O(log L); best-level lookup O(log L) per match iteration. FOK pre-check walks at most the crossing levels. Post-only pre-check: one best-level lookup. **Journal:** append = one write + one fsync (~1–2 ms on the dev laptop, so roughly 500–1,000 commands/s); recovery scans backwards from the end (cost ∝ length of the last record); `readAll` and replay are O(file size).

**Git:** step 3A+B committed as `5f0d437` ("Add command-support and Sequencer"), 3C as `2cdca7c` ("Add WAL and helpers"), 3D committed after this spec. `New Learnings/` (project root) is untracked unless the user adds it. A **stale older copy** of the learning guide sits in `src/New Learnings/` (no smart-batching section); delete it or pick one location.

**Pace (for estimating):** step 3 took 2026-10-20..24. Review rounds: A 4, B 4, C 0 (LLM-written after ~7 design rounds on placement, types, durability and batching, plus a 2-hour learning guide), D 2. Most of the time went to design discussion for C, which is expected to repeat for step 4 (threads).

---

## 3. Decisions (settled)

| # | Decision | Reason |
|---|---|---|
| D1 | `OrderBook` owns the `orderId -> OrderNode` map. | Only the code that mutates levels can keep "id in map iff resting" true. The engine never touches the map. |
| D2 | One `unlink(node)` serves fill-removal and cancel. | One code path, one place for invariants. |
| D3 | `fillHeadOrder()` only fills; never removes. | Otherwise the book never learns of the removal and the map goes stale. |
| D4 | `match()` calls `removeOrderFromBook` when the resting order hits 0. | The engine already holds the book. |
| D5 | `OrderNode` wrapper, not link fields in `Order`. | `Order` stays a domain object. Revisit at step 6 only if benchmarks show it; try node pooling before making `Order` intrusive. |
| D6 | Self-implemented doubly linked list with caller-held node handles. | `java.util.LinkedList` hides nodes (O(n) remove); a `ListIterator` is invalidated by other modifications. |
| D7 | Sentinel head/tail. | No head/tail/only-node branches in link/unlink. |
| D8 | `unlink` nulls `prev`, `next`, `level`. | Stale nodes are detectable. |
| D9 | `unlink` throws `IllegalStateException` for a sentinel or a node whose `level != this`. | Unguarded, a foreign node would corrupt two levels silently. |
| D10 | Eager removal of emptied levels. | Strict invariant, simple best-level lookup. |
| D11 | Cached best-level pointer deferred to benchmarking. | Measure first. |
| D12 | Log source of truth = sequenced input commands, replayed through the real engine. | Live and replay paths cannot drift. Requires determinism. **Implemented in step 3.** |
| D13 | State-dependent rejects (cancel of unknown id, post-only that would cross) stay in the log. | Write-ahead logs a command before the engine processes it; nobody knows it is a reject until after it is logged. Replaying it reproduces the same reject. (Tested in `ExchangeTest`.) |
| D14 | Drained-level removal happens in `removeOrderFromBook`, not in `process()`. | Follows from D1, D3, D4, D10. |
| D15 | Duplicate order id in `addOrderToBook` throws. | Ids come from our own code; a duplicate is an internal invariant violation. |
| D16 | Order ids are never reused; monotonically increasing; gaps allowed. | Logged cancels, trade history and acks refer to ids. |
| D17 | `cancel` returns `CancelResult(status, cancelledQuantity)`; `UNKNOWN_ORDER` = not resting. `NOT_OWNER` deferred until orders carry a participant id. | The common `UNKNOWN_ORDER` case is a race, a normal outcome, so a return value, not an exception. |
| D18 | Level removal is private to `removeOrderFromBook` and checks that `TreeMap.remove` returned the emptied level. | One owner; wrong-side/wrong-key bugs fail loudly. |
| D19 | Validation in exactly two layers: `OrderRequest` (gateway, stateless) and the `Order` constructor (the real invariant). No checks in `createOrder`; no guard in `crosses`. The journal's `decode` rebuilds requests through `OrderRequest`'s constructor (reuse, not a third copy). | Middle-layer copies drifted apart. Stateless rejects never enter the log. |
| D20 | Limits are code constants in `Order`, inclusive. | Notional can never overflow a `long`. Constants, not config: a change between a live run and its replay would make logged commands poison pills. **Step 3 lesson:** even a code change that *tightens* a rule does the same (an old valid record fails `decode`/validation on replay). Rules replay depends on must not change retroactively: snapshot + fresh journal at the change, or version the rules per record. |
| D21 | Clients reach the engine through one entry point on one thread. Since step 3 that entry point is `Exchange` (`submit`, `cancel`); the engine's public `cancel(long)` is gone. | One total order of commands; replay runs the same sequence through the same code. |
| D22 | `submit` returns `Response(orderId, trades, orderStatus, remainingQuantity)`. | Fields cannot contradict each other; the `orderId` is how a client cancels. |
| D23 | Four statuses; REJECTED only for post-only that would cross. | The client's next action differs ("no liquidity" vs "reprice"). |
| D24 | `process` shape: (1) type-specific pre-checks that change nothing; (2) the shared matching loop; (3) the outcome as a `switch` expression over `OrderType` with no `default`. A fillable FOK left with a remainder throws `IllegalStateException`. | All checks before any mutation; the compiler rejects a `switch` that misses a type. |
| D25 | The LIMIT default lives only at the gateway (`OrderRequest.limit`). A null type throws in both layers. | Past the gateway a null type is our bug. |
| D26 | A market order has no price: `Order.NO_PRICE` (`-1`) in memory; `getPrice()` throws for MARKET; clients use `OrderRequest.market`. In the journal the price field is `-`. | A fake price would lie. Keeping the sentinel out of the client API **and out of the file format** lets it change later. |
| D27 | `IllegalArgumentException` = bad input; `IllegalStateException` = our bug / broken invariant / **corrupt journal** (our own data). | One meaning per exception type. |
| D28 | `canBeFilled` lives in the engine and uses the engine's `crosses`; the book exposes read-only level iterators. | One definition of "crosses"; the book exposes state, the engine decides its meaning. |
| D29 | Post-only that would cross is rejected, not slid. | Respects the client's price. |
| D30 | "Is this order type supported?" is a stateless check. | Depends only on the type field and the code version. |
| D31 | **Commands as data.** `NewOrderCommand` (request + seq + timestamp + id) and `CancelOrderCommand` (seq + timestamp + target id) are separate `final` classes under a `sealed interface Command`. The engine processes only commands. | The compiler, not a runtime null check, tells a cancel from a new order (an earlier single `Command` class with a nullable `Order` was split for this). A command carries no mutable state, so logging it and replaying it see the same object. The file needs a kind tag; the Java type does not (the class is the tag). |
| D32 | **The sequencer owns all stamps.** Every command gets a sequence number and a timestamp; new orders also get an order id. Two counters, both starting at 1; a cancel uses a sequence number but no id. | One source of stamps, so the engine is deterministic. Ids are unique by construction (one counter, only incremented), so no id set is needed. Two counters is a choice (dense ids); id = seq would also satisfy D16. |
| D33 | **Timestamps never go backwards:** on a backwards clock step, reuse the previous timestamp. | Throwing would refuse all traffic for the length of the jump; spinning would freeze the exchange for it. Equal timestamps are harmless: the sequence number already orders commands, and matching never reads timestamps. |
| D34 | **A trade's timestamp = the incoming command's timestamp.** | The incoming order caused the trade; reading the clock in `match()` would make replay produce different trades. |
| D35 | **The engine rejects a new-order id not strictly greater than the last one** (`IllegalStateException`, before any mutation). One `long` of state. | Catches every reuse, including ids that filled and left the book (which a book-based check cannot see). It fires *after* journalling, so a violation becomes a poison pill (§4); uniqueness itself is the sequencer's job. |
| D36 | **The journal is a plain text file**, one record per line (format in §2). Chosen over PostgreSQL/SQLite/Kafka; log-management tools (ELK, Loki) ruled out for a WAL (they buffer, reorder, drop, and have retention). | Readable in an editor, easy to debug; teaches durability directly. The user already knows PostgreSQL/Docker. ELK/Loki are kept for observability later. |
| D37 | **fsync on every append, before processing** (option A, decided 2026-10-22). Batching (group commit / smart batching) deferred to step 4 or 6 (§6). | Write-ahead rule: no effect may be visible before its record is durable, so a batch may only be processed after its fsync. With synchronous callers a batch never holds more than one command; batching needs a queue (step 4). Measure before optimizing. |
| D38 | **Recovery:** `open()` cuts everything after the last `\n` (a torn last record) and fsyncs the cut; a **complete** record that does not parse is never dropped: `readAll()` throws with the record number. `readAll()` reads bytes and requires the `\n`. | With fsync per append, a crash can only damage the record being written, whose append never returned, so nothing depends on it. A complete record may already have been processed and seen: dropping it could silently lose a trade. `readLine()` cannot tell a cut-off last line from a finished one (a torn "cancel 12" can read as "cancel 1"). |
| D39 | **Fail-stop:** after a failed write or fsync, the journal refuses every later append until restart. | A failed write may leave half a record, and the next record would be glued to it (corruption in the middle). A failed fsync cannot be retried safely: the OS may have dropped the data and marked it clean ("fsyncgate"; PostgreSQL panics). Restart recovery cuts the half record. |
| D40 | **The journal call lives in `Exchange`, in front of the engine.** Order: stamp -> append -> process. Replay feeds the engine directly, skipping the sequencer and the journal. | If the engine journalled, replay would append everything again (or need a second entry point, breaking D12). The engine stays free of I/O. |
| D41 | **Replay restores state by feeding every command through the same engine** (book and `prevOrderId` come back by themselves). The **sequencer is restored explicitly from the journal:** next seq = last seq + 1, next id = last *new-order* id + 1, previous timestamp = last timestamp. Replay's outputs are discarded. | The journal is the only source of truth for the counters (the crashed process's in-memory counters are gone; a failed append's seq is simply reused, so no gap). The last record may be a cancel, whose id is a target, not an issued id. Outputs were already delivered live; re-sending would make clients book fills twice and print trades twice on the market-data feed (real systems number their outputs and let clients request resends, marking them `PossDupFlag`, FIX tag 43). |
| D42 | **Replay verifies the history:** sequence numbers exactly 1, 2, 3, …; timestamps never decrease; order ids strictly increase (via D35). Any violation throws `IllegalStateException`. | A gap or repeat means a different history from the live run: every later command would be applied to a different book. A wrong book that nobody knows about is worse than no book. |
| D43 | **`Exchange` takes ownership of the journal** at construction: `close()` closes it, and if replay throws, the constructor closes it and rethrows (close failure added as suppressed). | Otherwise a failed construction leaks the file handle (on Windows the file stays locked). A factory that creates everything itself is the later, cleaner option (§6). |

---

## 4. Log, replay, determinism

- Sequenced **input commands** are the source of truth; outputs are derived by replay. **Implemented** (step 3).
- **Write-ahead:** a command is fsynced before the engine processes it (D37, D40).
- **Reject split:** stateless rejects are caught when the `OrderRequest` is built and never reach the journal; state-dependent rejects are decided by the engine and journalled (D13).
- **Determinism:** sequence number, timestamp and order id are stamped before journalling; the engine reads no clock and counts nothing (the step 2 gaps `Instant.now()` in `createOrder`/`match` and `nextOrderId` are **closed**). Levels walk in `TreeMap` order; the id map is only looked up. Single writer (by convention until step 4).
- **The two failure gaps of write-ahead** (documented in `New Learnings/02` §4):
  - *Visible ⇒ durable* holds: nothing a client saw is missing after a restart.
  - *Durable ⇒ visible* does not: a crash after the append but before the response leaves a journalled, replayed command whose client heard nothing; the client may resend and create a duplicate. Fix at the protocol level (client order ids, e.g. FIX `ClOrdID`; duplicate detection; output sequence numbers + resend requests). Steps 4/7.
- **Poison pill:** a journalled command that crashes the engine crashes every replay. Known sources: the engine's `IllegalStateException`s (D24 FOK, D35 id guard) and a tightened validation rule (D20). Policy (skip/quarantine/stop) is roadmap step 7. **Prevention idea for step 7:** put checks *before* the durable write (as `encode` already does for null fields): the journal or `Exchange` refuses a command whose seq is not last + 1 or whose id is not greater than the last, before appending it, so such a bug throws once and writes nothing.
- **Output event shape:** an order that never rests: received -> match(es) -> done. "Open/rested" only for orders that go on the book. No fabricated events.

---

## 5. Next: roadmap step 4 — TCP gateway with a single-writer engine thread

Not started. Short descriptions only (session rule 1); the user designs and codes, then review. Suggested order:

- **A. Thread model.** Exactly one thread owns the sequencer, the journal and the engine. Requests from any number of other threads reach it through one ordered queue, and each caller gets its own result back without blocking that thread's processing of others.
- **B. TCP gateway.** Clients connect over TCP, send new orders and cancels in a simple text protocol, and receive their responses (and trades) on the same connection. Malformed or invalid requests are rejected at the gateway and never enter the queue.
- **C. Batching (decide with measurements).** Once commands can wait in the queue, compare fsync per append, group commit with a timeout, and smart batching (§6), and keep the one the numbers justify.

Open decision to settle at the start of step 4: plain TCP vs Spring Boot (Vision v3 §8).

---

## 6. Open threads

**Deferred on purpose**
- **Log durability: batching decided in step 4 or 6.** Today: fsync per append (~1–2 ms; ~500–1,000 commands/s). Options to compare with measurements once a queue exists:
  1. **fsync per append** (current): simple; latency = one fsync; throughput capped by fsync time.
  2. **Group commit with a timeout:** close a batch at N commands or T ms; one fsync per batch. High throughput, but under light load each command also waits up to T.
  3. **Smart batching** (Martin Thompson / LMAX): the writer takes whatever is already waiting each time it is ready, writes it, fsyncs once, then processes it. No timer: under light load the batch is one command (same as option 1); under heavy load batches grow on their own. Usually capped at a maximum batch size. Adapts better than option 2, but still needs the queue and writer thread.
  In every option a batch is processed **after** its fsync, never before (write-ahead rule).
- **Single-writer enforcement / ownership.** The caller builds the `OrderBook`, `MatchingEngine`, `Sequencer` and `CommandJournal` and hands them to `Exchange`; it keeps references and could bypass the journal (e.g. call the engine directly). Plan: a factory (e.g. `Exchange.start(Path)`) that creates everything itself; tests get the book via a package-private accessor. Do it with the move to a real package + build file. Also removes the need for `Sequencer`'s package-private restore setters to be callable by anyone in the package.
- **Injectable clock for `Sequencer`.** The "clock went backwards" branch (D33) is untested: removing it fails no test. A constructor taking `java.time.Clock` (production: `Clock.systemUTC()`; tests: a fake clock that can step back) would make it testable.
- **RESTED snapshot.** `Response.remainingQuantity` for RESTED is the amount resting at submit time; later fills reach the client only as trades. Needs fill/execution events for the resting side (step 4 outputs / step 5 market data).
- **Market order price protection** (step 5, risk checks): price bands/collars.
- **Sentinel reachable through the public constructor.** `new OrderRequest(side, Order.NO_PRICE, qty, MARKET)` is accepted. To keep `-1` fully out of client hands: make the four-argument constructor private and add factories for IOC, FOK, post-only. (`CommandJournal.decode` uses the four-argument constructor for non-market types; it would need the factories or package access.)
- **`canBeFilled` uses `getOriginalQuantity()`.** Equal to remaining on the real path. User's call; not a defect.

**Journal limits (accepted for now, see `New Learnings/02` §6)**
- New journal file: the folder is not fsynced after creation (matters on Linux; Java on Windows cannot do it).
- No lock against a second process opening the same journal (on Windows a whole-file lock would also block `readAll`'s separate reader).
- `readAll` returns the whole history as one `List` and reads byte by byte through a `BufferedInputStream`; fine at test sizes. Streaming / snapshots at step 6–7.
- No per-record checksum (a bit flip inside a complete record could still parse). Optional CRC32 later.
- `Exchange`'s `journal.isEmpty()` call sits outside the replay `try`, so an exception there would skip the journal close (practically impossible: it is a size read).

**Small code items**
- `OrderBook.removePriceLevel` still has checks that `pl != priceLevel` already covers (`isEmpty`, `containsKey`, `isFullyConsumed`).
- `OrderBook.java` imports `jdk.jfr.Description` and uses it as an annotation on `bestLevel` in place of a comment. It is a JDK Flight Recorder annotation, not documentation; replace it with a comment. (Spec v7 wrongly called the import unused.)
- `Main.java` is an empty `main` plus commented-out scratch code (orders with id 0 and the old constructor). Delete, or keep as a demo once the gateway exists.
- Post-only pre-check in `process`: `if (...)` without braces, body at the condition's indent. Correct, reads as unconditional.
- Test-only public `MatchingEngine.submit(Order)` bypasses the sequencer and journal. Remove when tests no longer need it.
- `MatchingEngineCommandTest`'s determinism helper uses `List<Object>` + `instanceof`; it could now use `List<Command>`.
- `src/.gitignore` mostly duplicates the root `.gitignore`.
- Stale duplicate `src/New Learnings/01_File_IO_Durability_and_WAL.md` (§2 Git).

**Design threads**
- Participant id on `Order`: needed for `NOT_OWNER` cancel rejects and self-trade prevention. When added, decide whether a non-owner cancel returns `NOT_OWNER` or `UNKNOWN_ORDER`. Or drop self-trade prevention from scope.
- Principle 2 tension: `PriceLevel.fillHeadOrder()` holds fill logic and `match()` calls it directly; `bestLevel` and the iterators hand out `PriceLevel` objects whose public `fillHeadOrder` can still mutate a level.
- Thread model: gateway connections -> ordered queue -> single writer (roadmap step 4, §5).
- Client-side recovery protocol: client order ids, duplicate detection, numbered outputs and resend requests (§4 "durable ⇒ visible"). Steps 4/7.
- Spring Boot vs plain TCP gateway (Vision v3 §8).
- Real-world anchors: lot sizes, tick sizes, typical large-trade sizes, and how they should shape the D20 limits.

**Deferred on purpose (earlier)**
- **Move tests out of `src/`** to a sibling source root `Exchange Server/test/` marked "Test Sources Root", files keeping **no package line**. **Not** `src/test/` (that would force a named package, which cannot import default-package classes).
- **Merge test files to one per production class** with `@Nested` per method, at project completion (Vision v3 §7). Includes removing `MatchingEngineTest`'s duplicate `CrossesTest`.
- **Build file** (`pom.xml`): one classpath for IntelliJ, VS Code and the command line; needed for JMH at step 6. With it, move to Maven layout and a real package (then do single-writer enforcement).
- Cached best-level pointer and node pooling: step 6 benchmarks.
- Poison-pill policy: step 7 (§4).

**Resolved since v7:** step 3 complete (commands, sequencer, write-ahead journal, replay); engine clock reads and id counter removed; public `MatchingEngine.cancel(long)` removed (cancels go through `Exchange`); trade timestamps deterministic; `Order` constructor parameter order unchanged after an accidental swap was reverted.

---

## 7. Opening message for the next session

> Attach `ExchangeServer_Vision_v3.md` and `ExchangeServer_Spec_v8.md`. Follow the session rules in Spec v8 §1 (code first, questions after). We are in roadmap step 4 (§5). I will code from the short descriptions; review my saved code, show me where I went wrong, test my understanding, then write and run the tests (with mutation checks). The code is in the local repo (`src/`, default package); read it directly rather than asking me to paste it.
