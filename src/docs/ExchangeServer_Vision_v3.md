# ExchangeServer — Vision v3

**Supersedes:** `ExchangeServer_Vision.md` (v1) and `ExchangeServer_Vision_v2.md`. This version is **self-contained**: nothing needs to be carried over from earlier versions.
**Companion:** `ExchangeServer_Spec_v7.md` (exact code state, decisions, open threads).
**Rule:** the roadmap lives **only here**. The spec points to it and does not duplicate it.

---

## 1. Purpose

ExchangeServer is a Java trade-exchange matching engine built as a **hands-on way to understand production-grade software systems**. Depth of understanding matters more than feature count: every design choice should be justified, tested, and understood well enough to defend.

**Honest scope note:** building something that *works like* a real exchange is achievable. Matching the scale and robustness of NASDAQ/LMAX-class venues is not, solo. The aim is to hit the same design problems real systems hit and understand why they were solved the way they were.

Learning order: correctness of the core -> concurrency model -> networking -> durability/recovery -> (conceptually) distribution -> operability.

---

## 2. What the system is

A single-instrument limit order book with a price-time-priority matching engine, growing toward a full pipeline:

```
 client --> TCP gateway --> sequencer + input log --> single-writer engine --> OrderBook
              (stateless          (stamps seq, ts,         (deterministic        (levels, nodes,
               validation)         id; write-ahead)         matching)             id map)
                                         |                       |
                                         v                       v
                                  replay on recovery      outputs: acks, trades,
                                                           fills, market data
```

Status: domain model, matching engine and API entry point exist. The book supports O(1) cancel (roadmap step 1 done, 2026-10-17). Cancel goes through the engine, and all five order types (limit, IOC, FOK, market, post-only) are implemented with a `Response` per submit (roadmap step 2 done, 2026-10-20). Sequencer, log, gateway, risk checks, market data, benchmarking and recovery are planned.

---

## 3. Working style (how to collaborate)

1. **Reason before code.** Walk a concrete numeric scenario in plain English before generalizing to pseudocode/code.
2. **I write the code; the LLM reviews.** Review, bug-spotting and design discussion, not authoring the implementation. **Exception (from 2026-10-17): the LLM writes the tests**, verified by running them, and gives them in chat; I read each one to understand what it checks. The LLM never edits source files directly; I paste code in.
3. **Verify by tracing, not asserting.** Before calling anything "done", trace it with concrete inputs, including edge cases and exit paths.
4. **Call out weak justifications.** "Production systems do it" is not a reason unless true; the real reason is often simpler (easier to test, avoids duplication). Stale rationales must be fixed when the design changes.
5. **Check exhaustiveness explicitly.** Name the dimensions of a problem (e.g. 2 sides x 3 price relations = 6 cases) rather than listing cases until it feels enough.
6. **Code first, questions after (since 2026-10-17; replaces the earlier "questions before code" pacing).** The loop for each piece of work:
   1. The LLM gives a **short description** of what the piece must do (a few lines per behaviour, like "IOC: match what you can now, cancel the remainder instead of resting it"). No design hints, no edge-case lists, no code.
   2. I design and code it from my own understanding.
   3. The LLM reviews the saved code and **shows where I went wrong** (location + what breaks, with a concrete input), without writing the fix.
   4. The LLM **tests my understanding** of the reasoning behind each function / logical piece (why it exists, why in that order, what breaks without it).
   5. I fix; repeat 3–5 until clean. Then the LLM writes and runs the tests.

*Instructions for an assisting LLM:* adopt the above; it is the tested collaboration style, not aspiration.

---

## 4. Engineering principles

1. **Invariants first.** State the invariant, find the single place that maintains it, make violations fail loudly.
   - `totalVolume` equals the live remaining volume of the level's nodes at all times.
   - The id map contains an id iff that order rests in a level.
   - The book never contains empty price levels.
   - Invariants are protected at their source, not re-checked redundantly by callers.
2. **One owner per responsibility.** `OrderBook` owns the order lifecycle on the book; `Order` is a domain object; `MatchingEngine` decides what matches mean and does not touch book internals (map, TreeMaps, list nodes). *Known tension:* `PriceLevel.fillHeadOrder()` contains fill logic, which sits between "dumb container" and "business logic" (see Spec v7 open threads).
3. **One code path where possible.** Fill-removal and cancel share `unlink`; replay shares the live engine path.
4. **Determinism.** Engine behaviour depends only on the ordered input commands. Ids, timestamps and sequence numbers are stamped before logging; no wall-clock, randomness or hash-order dependence in matching; single writer thread. *Current gap:* `createOrder()` and `match()` still use `Instant.now()` (Spec v7 open threads).
5. **Inputs are the source of truth.** Log sequenced commands; derive outputs by replay.
6. **Validate at the right layer.** Stateless checks at the gateway; state-dependent checks in the engine. Internal invariant violations throw; external bad requests return rejects.
7. **Measure before optimizing.** Simple correct structures first (eager level removal, `TreeMap`); revisit with benchmarks.
8. **Honest complexity claims.** Say what is O(1) and what is O(log L).
   - Cancel / fill removal of a node: O(1).
   - Removing an emptied level: O(log L) (`TreeMap.remove`).
   - Best-level lookup via `TreeMap.firstEntry()`: O(log L) on every match iteration, until/unless a cached pointer is justified by benchmarks.
9. **Business logic and data structures stay separated.** Containers expose state; the engine decides its meaning.

---

## 5. Key design commitments (detail in Spec v7)

- Doubly linked list per price level with sentinel nodes and caller-held node handles (`OrderNode` wrapper owned by the book layer; intrusive in effect, not in `Order`).
- `orderId -> OrderNode` map in `OrderBook`.
- Eager removal of emptied levels.
- Write-ahead input log; state-dependent rejects are logged, stateless rejects are filtered at the gateway.
- Orders that never rest log as received -> match(es) -> done, with no fabricated "rested/removed" events.
- **Concurrency answer (resolves v1's open question):** correctness comes from funnelling all activity for an instrument through one single-threaded engine; no internal thread-safety in the book. Concurrency exists only at the boundary (many connections feeding one ordered queue).

---

## 6. Non-goals (for now)

- Multi-instrument support, multi-threaded matching, distributed replication/consensus.
- Price-indexed array books (needs a bounded price range; revisit only if benchmarks demand it).
- Full FIX/ITCH compliance; a simple TCP protocol is enough.
- Production hardening beyond the learning goals.

---

## 7. Roadmap (one-week plan, about 5h/day) — single source of truth

1. Close open threads (validation deduplication, `OrderRequest` quantity guard, `crosses()` guard + tests) and O(1) cancel
2. Order types / cancel flows
3. Sequencer + event log
4. TCP gateway with single-writer engine thread (define the thread model: connection threads -> ordered queue -> single writer)
5. Risk checks + market data
6. Benchmarking (decide on cached best-level pointer; measure level removal vs lookup cost)
7. Crash recovery via replay (including poison-pill policy)

**At project completion (cleanup):** merge the test files so there is **one test file per production class** (e.g. `OrderBookTest`, `MatchingEngineTest`, `PriceLevelTest`), with `@Nested` groups per method, instead of the current per-method / per-step files (`MatchingEngineCrossesTest`, `...MatchTest`, `...SubmitTest`, `MatchingEngineBookCleanupTest`). Deferred on purpose: merging now would mean re-reading existing files on every paste.

**Deferred beyond the week (from v1, not cancelled):** distributed systems concepts (replication, failover, why distributed matching is avoided), and DevOps/observability (metrics, logging, deployment). Metrics will first appear via benchmarking in step 6.

---

## 8. Decisions needing confirmation

- **Service layer:** v1 / Spec v3 planned Spring Boot (microservices at the edges, monolithic core in the middle). v2 switched to a plain TCP gateway. This doc follows v2. Confirm whether Spring Boot is dropped or merely postponed.

---

## 9. Glossary

- **Resting order:** sitting on the book (passive side).
- **Incoming / aggressive order:** currently being matched; may never rest if fully filled.
- **Touch:** best bid and best ask levels.
- **Unlink:** splicing a node out of its level's list in O(1) given the node.
- **Sentinel:** dummy head/tail node that removes edge-case branching.
- **Poison pill:** a logged command that crashes the engine and therefore every replay.
- **Write-ahead:** persisting a command before processing it.
