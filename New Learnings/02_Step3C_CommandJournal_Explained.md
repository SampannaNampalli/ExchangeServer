# Step 3C explained: the write-ahead command journal

**For:** you, reading code you didn't write yourself this time, and preparing to explain it in an interview.
**Companion:** `01_File_IO_Durability_and_WAL.md`. Section numbers like "(guide 2.3)" point into it.
**Code state:** written 2026-10-23 on top of commit `5f0d437`. All 266 tests pass (233 earlier + 23 `CommandJournalTest` + 10 `ExchangeTest`).

Read the code with this file open beside it. Each section below names the piece of code, says what it does, and gives the **real reason** it exists.

---

## 1. What was built

```
 client --> OrderRequest --> Exchange.submit / Exchange.cancel
 (stateless validation         1. Sequencer stamps (seq, timestamp, id)
  happens when the              2. CommandJournal.append  -> written + fsynced
  request is built)             3. MatchingEngine processes -> Response / CancelResult

 restart (step 3D, not written yet):
   CommandJournal.open -> readAll() -> each command straight into a fresh MatchingEngine
```

| File | Change | Role |
|---|---|---|
| `Command.java` | **new** | sealed supertype of the two command kinds |
| `NewOrderCommand.java`, `CancelOrderCommand.java` | `public final class ... implements Command` | join the sealed family |
| `CommandJournal.java` | **new** | the write-ahead log: append, fsync, read back, recover |
| `Exchange.java` | **new** | the one live path: stamp, then journal, then engine |
| `CommandJournalTest.java`, `ExchangeTest.java` | **new** | 33 tests |

Nothing in `MatchingEngine`, `OrderBook` or `Sequencer` changed. The engine still knows nothing about files, so replay can reuse it unchanged (D12).

---

## 2. `Command.java`: one type for "any command"

```java
public sealed interface Command permits NewOrderCommand, CancelOrderCommand {
    long getSequenceNumber();
    Instant getTimeStamp();
}
```

- **Why it exists:** `readAll()` must return new orders and cancels mixed together, in their original order. Without a common type that would be `List<Object>` plus casts (the ugly helper in `MatchingEngineCommandTest`).
- **Why only these two methods:** they are the fields *every* command has. Order details belong only to `NewOrderCommand`, and the target id means something different in each kind, so neither goes on the interface.
- **Why `sealed`:** the compiler knows the complete list of kinds. Code that handles "a command" can be sure it has handled every kind, and adding a third kind later becomes a visible, deliberate change. (In Java 21 a `switch` over a sealed type is checked for completeness; in Java 17 we use `instanceof`; guide 3.4.)
- **Why the two classes became `final`:** a class that a sealed interface permits must say how its own family continues: `final` (no subclasses), `sealed`, or `non-sealed`. `final` is the honest answer here.
- **Why the tag ("NEW" / "CANCEL") isn't a field:** the **file** needs a tag to know what a line is; the **Java type** doesn't, because the class *is* the tag. That keeps your piece A decision: the compiler, not a runtime check, tells a cancel from a new order.

---

## 3. `CommandJournal.java`, section by section

### 3.1 The header comment: the file format

```
NEW,<seq>,<timestamp>,<orderId>,<side>,<price>,<quantity>,<type>
CANCEL,<seq>,<timestamp>,<orderId>
```

Example of a real file, which the format test pins down exactly:

```
NEW,1,2026-01-01T10:00:00Z,1,BUY,100,10,LIMIT
NEW,2,2026-01-01T10:00:00Z,2,SELL,-,4,MARKET
CANCEL,3,2026-01-01T10:00:00Z,1
```

- **Text, one record per line:** you can open the journal in any editor and read it. Debugging a recovery problem at 3 a.m. is much easier when you can read the evidence. Binary would be smaller and faster; that's a step 6 question (guide 3.1).
- **`,` between fields, `\n` after each record:** none of our values can contain a comma or a newline (enum names, numbers, ISO timestamps), so no escaping is needed (guide 3.2).
- **Always `\n`, never `\r\n`:** the format must not depend on which OS wrote it (guide 1.1). A record ending in `\r` is rejected as corrupt.
- **MARKET price is `-`:** a market order has no price (D26). `-1` is the in-memory sentinel, not part of the file format, and `decode` rejects `-1` for MARKET. If the sentinel ever changes, old journals still read correctly.

### 3.2 Constants and fields

```java
private static final String NEW = "NEW"; ... private static final byte END_OF_RECORD = '\n';
private final Path file;
private final FileChannel channel;
private final long truncatedBytesOnOpen;
private boolean failed;
```

- The constants keep the format in one place. `encode` and `decode` use the same names, so they can't drift apart.
- `channel` is opened **once** and kept open for the journal's whole life (guide 1.6). That's why the class is `AutoCloseable`.
- `failed` drives the fail-stop policy (3.4).
- `truncatedBytesOnOpen` records how much a torn tail recovery removed. Tests use it, and in production you'd log it, because an operator wants to know that recovery cut something.

### 3.3 `open(Path)`: a static factory, not a constructor

```java
public static CommandJournal open(Path file) throws IOException {
    long truncated = cutTornTail(file);
    FileChannel channel = FileChannel.open(file, CREATE, WRITE, APPEND);
    return new CommandJournal(file, channel, truncated);
}
```

- **Recovery runs first, then the append channel opens.** Recovery needs to *read* the file and *truncate* it. Java doesn't allow `READ` and `APPEND` on one channel (that combination throws `IllegalArgumentException`), so recovery uses its own short-lived channel and closes it.
- **`APPEND`:** every write goes to the end, whatever happened before. A restart can never overwrite history (guide 1.4). Mutation J2 removes `APPEND`, and 2 tests fail.
- **`CREATE`:** a missing file is a valid "empty history".
- **Why a static factory:** it does real work (recovery) *before* the object exists, and the name `open` says what happens. The constructor is `private`, so there's no way to get a journal that skipped recovery.

### 3.4 `append(Command)`: the heart of write-ahead

```java
if (failed) throw new IllegalStateException("Journal failed earlier; restart to recover.");
ByteBuffer record = ByteBuffer.wrap(encode(command).getBytes(UTF_8));
try {
    while (record.hasRemaining()) channel.write(record);
    channel.force(true);
} catch (IOException e) {
    failed = true;
    throw e;
}
```

Line by line:

1. **`if (failed)`: fail-stop.** After any write or fsync failure, every later append is refused. There are two reasons:
   - A failed write may have left **half a record** on disk. If a later append succeeded, its record would be glued onto that half, creating a corrupt record in the *middle* of the file. That's exactly the case recovery must refuse to guess about.
   - A failed **fsync can't be retried safely**. The OS may already have dropped the unsaved data and marked it clean, so a retry "succeeds" without saving anything (guide 2.3, "fsyncgate"). PostgreSQL stops completely in this case, and so do we.

   The way back is a restart: `open()` cuts any half record, and the system continues from a clean file.
2. **`encode` before writing anything:** if the command can't be encoded (null timestamp, null request), it throws *before* a single byte reaches the file. That's important: a record that can't be read back would be a **poison pill**, crashing every future replay (Spec §4).
3. **The `while (record.hasRemaining())` loop:** a channel write may, in theory, write fewer bytes than asked; the loop finishes the job (guide 1.9).
4. **`force(true)`: the fsync.** `append` returns only when the record is on the disk, so it survives a power cut, not just a process crash (guide 2.1–2.3). `true` also saves the file's size; for a growing file that matters. Cost: 1–2 ms per command on your laptop (guide Lab 4), which is the price of option A.
5. **The `catch`:** set `failed`, then **rethrow**. The caller (`Exchange`) must learn about the failure so it doesn't process the command.

There's no `BufferedWriter`. `FileChannel.write` hands the bytes straight to the OS, so there's no hidden Java buffer to forget to flush (guide 1.5). The test `appendedRecordIsInTheFileAsSoonAsAppendReturns` checks exactly that.

### 3.5 `readAll()`: bytes, not lines

```java
try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
    int b;
    while ((b = in.read()) != -1) {
        if (b == END_OF_RECORD) { recordNumber++; commands.add(decode(...)); record.reset(); }
        else record.write(b);
    }
}
if (record.size() > 0) throw new IllegalStateException("... has no end-of-record ...");
```

- **Why not `readLine()`:** it returns an unfinished last line exactly like a finished one (guide 1.8). A torn `CANCEL,2,…,12` cut after the `1` would come back as a perfectly valid "cancel order 1": the wrong order cancelled on replay. Reading bytes and requiring the `\n` makes **the end-of-record byte the proof that a record is complete.** The test `tornRecordThatLooksValidIsStillCutOff` covers this case.
- **Why a separate `InputStream`:** the journal's own channel is in `APPEND` mode, which can't read.
- **`BufferedInputStream`:** reading one byte at a time from the OS would mean one system call per byte. The buffer reads 8 KB at a time and hands out bytes from memory.
- **Record numbers in errors:** "Corrupt journal record 2" tells a human where to look.
- **The final `if`:** `open()` already cut any torn tail, so finding one *now* means something else wrote to the file after it was opened. Fail loudly (test `readAllRefusesAnUnterminatedTailWrittenAfterOpen`).
- **Known limit:** it returns the whole history as one `List`. That's fine for a learning project; a real system streams records or starts from a snapshot (step 7).

### 3.6 `isEmpty()`, `getTruncatedBytesOnOpen()`, `close()`

- `isEmpty()` uses `channel.size()`, the exact byte count, without reading anything. `Exchange` uses it (3.9).
- `close()` closes the channel. Closing twice is harmless. That matters because tests sometimes close the journal on purpose and the `Exchange` closes it again.

### 3.7 Recovery: `cutTornTail` and `endOfLastCompleteRecord`

```java
try (FileChannel ch = FileChannel.open(file, CREATE, READ, WRITE)) {
    long size = ch.size();
    long end = endOfLastCompleteRecord(ch, size);
    if (end < size) { ch.truncate(end); ch.force(true); }
    return size - end;
}
```

- **The rule (guide 2.5–2.7):** with an fsync after every append, a crash can damage only the **last** record, the one being written when the crash happened. That record's `append` never returned, so its command was never processed, nothing was visible, and cutting it is safe.
- **"Torn" means: no `\n` at the end.** Everything after the last `\n` is cut.
- **`ch.force(true)` after the truncate:** the cut must be on disk before new records follow it. Otherwise, after a second crash, the old torn bytes could come back between good records.
- **`endOfLastCompleteRecord` scans backwards** in 4,096-byte blocks, from the end of the file, until it finds a `\n`. Why backwards: torn bytes are only ever at the end, so the work is proportional to the length of the last record, not the whole file. `ch.read(block, position)` reads at a given position without moving the channel's own position.
- The test `tornTailLongerThanOneScanBlockIsFound` uses a 10,000-byte tail, so the scan must cross block boundaries. Mutation J11 (scan one block only) is caught by it.

**What recovery deliberately does *not* do:** it never drops a record that *has* its `\n`, even if it's the last one and doesn't parse. A complete record was fully written, so its `append` may have returned and its command may have been processed. Dropping it could silently lose a trade. So `readAll()` fails loudly instead, and a human decides (test `completeButCorruptLastRecordIsNotSilentlyDropped`).

| What the file looks like at startup | What happens | Why |
|---|---|---|
| empty or missing | empty history | nothing was ever journalled |
| ends with `\n`, all records parse | nothing changes | clean |
| bytes after the last `\n` | **cut**, size reported | crash during the last append; it was never processed |
| a complete record doesn't parse (anywhere, even last) | `readAll()` throws `IllegalStateException` with the record number | not a crash artifact: bug, disk corruption or manual editing. Never guess |

### 3.8 `encode` and `decode`: the format, both directions

**`encode(Command)`:**
- Null command, request or timestamp are refused **before** writing, so nothing unreadable ever reaches the file.
- `StringBuilder.append(long)` writes plain ASCII digits whatever the machine's locale; `String.format` might not (guide 3.3).
- Enums are written by `name()`, never `ordinal()`; `Instant` by `toString()` (ISO-8601, exact to the nanosecond).
- The final `else` throws for an unknown kind. It can't happen while `Command` is sealed, but if someone adds a third kind and forgets the journal, it fails loudly instead of writing garbage.

**`decode(String, long)`:**
- `split(",", -1)`: the `-1` keeps trailing empty fields, so `CANCEL,1,…,5,` (5 fields) is caught by the field count. Without it, the trailing empty field would vanish and the record would be accepted. Mutation J5 shows exactly that.
- **A `switch` expression on the tag:** `case NEW -> { …; yield …; }` and `default -> throw …`.
- **Exact field counts** for each kind.
- **MARKET:** the price field must be exactly `-`, and the request is rebuilt with `OrderRequest.market(...)`. Any other type goes through the four-argument `OrderRequest` constructor, which **re-validates** everything (side, quantity range, price range). The journal reuses the gateway's validation instead of copying it (D19).
- **All parsing errors become one `IllegalStateException`** with the record number and text. Under D27, `IllegalArgumentException` means "a client sent bad input", while `IllegalStateException` means "our own data or code is broken". A bad journal record is our data, so it's an `IllegalStateException`. The original exception is kept as the *cause*, so no detail is lost.
- `DateTimeParseException` is caught separately because it isn't an `IllegalArgumentException`. `NumberFormatException` is one, so it's already covered.

### 3.9 `Exchange.java`: the one live path

```java
public Response submit(OrderRequest request) throws IOException {
    Objects.requireNonNull(request, "request");
    NewOrderCommand command = sequencer.createStampedNewOrderCommand(request);
    journal.append(command);                     // durable before any effect
    return engine.submitNewOrderCommand(command);
}
```

- **The order of the three lines *is* the design.** Stamp, then journal, then process. If the journal throws, the engine is never called (test `ifTheJournalFailsTheEngineNeverSeesTheCommand`). Swap the last two lines (mutation X1) and that test fails: the order would rest on the book with no journal record, and replay would lose it.
- **Why this lives in `Exchange` and not in the engine** (your design question 1): replay (3D) will call `engine.submitNewOrderCommand` directly with journalled commands. If the engine wrote the journal, replay would append every command again.
- **`requireNonNull` before stamping:** otherwise a `null` would use up a sequence number and an order id for a command that never existed (test `nullRequestUsesNoSequenceNumber`).
- **Stateless rejects never reach the journal:** an invalid request can't even be *built* (`OrderRequest`'s constructor throws), so there's nothing to submit.
- **State-dependent rejects *are* journalled** (D13): a post-only that would cross, or a cancel of an unknown id. The journal can't know in advance, and replay reproduces the same reject.
- **The constructor refuses a non-empty journal.** A fresh `Sequencer` would hand out seq 1 and id 1 again, duplicating ids already in the journal. Piece D replaces this check with replay, which also restores the sequencer's counters and the engine's last-id guard.

---

## 4. What happens when something fails (the failure model)

Imagine the machine dies at each point inside `Exchange.submit`:

| Crash point | In the journal after restart? | Client saw a response? | Consistent? |
|---|---|---|---|
| before `append` starts | no | no | yes: the command never existed |
| during the write (torn) | no: `open()` cuts it | no | yes |
| after the write, before `force` returns | process crash: **yes** (the OS cache survives). Power cut: maybe | no | yes, either way, because nothing was visible |
| after `append`, before or during the engine | **yes**, and replay will process it | no | yes for the book; but see the next paragraph |
| after the response | yes | yes | yes |

The guarantee is **"visible ⇒ durable"**: anything a client saw is in the journal. It is *not* "durable ⇒ visible": a command can be journalled, and replayed after a restart, without its client ever getting an answer. The client may then resend it, creating a duplicate. Real exchanges solve this with client order ids (FIX `ClOrdID`) and duplicate detection (guide 2.6). That's a step 4/7 topic.

Other failures:

| Failure | Behaviour |
|---|---|
| disk full / write error | `IOException` to the caller, command not processed, journal fail-stops |
| fsync error | same; no retry (fsyncgate) |
| corrupt record in the file | `readAll()` throws with the record number; a human decides |
| engine throws *after* the append (e.g. the id guard) | the command is journalled, so every replay will throw at the same place: a **poison pill**. Known, deferred to step 7 (Spec §4) |

---

## 5. The tests, and what they can't prove

**`CommandJournalTest` (23):**
- *Round trip:* every order type on both sides, market orders, cancels, nanosecond timestamps, `Long.MAX_VALUE` and the D20 limits, mixed kinds in order.
- *Exact format:* the bytes of three records are pinned, including `-` for market and `\n` only.
- *Durability visible to tests:* a record is readable through another handle as soon as `append` returns.
- *Restart:* reopening keeps old records and appends after them; a missing file is created; `readAll` changes nothing.
- *Recovery:* a torn tail is cut (including one that would parse as a different valid command, one longer than a scan block, and a file that is *only* a torn record); a clean file is untouched; the next append starts on a fresh line.
- *Corruption:* 14 kinds of bad record are each rejected; a bad middle record reports its number; a complete-but-corrupt last record is *not* dropped.
- *Fail-stop:* a null timestamp, request or command writes nothing and leaves the journal usable; a failed write makes every later append throw.

**`ExchangeTest` (10):** stamped commands are journalled and match the responses; trade timestamps equal journalled timestamps; cancels and state-dependent rejects are journalled; sequence numbers are contiguous and in processing order; journal failure means the engine never sees the command; stateless rejects and `null` use no sequence number; restart leaves the file unchanged; a non-empty journal is refused until replay exists.

**Mutation results** (each a one-line break, applied alone):

| Mutation | Tests failing |
|---|---|
| J2 open without `APPEND` | 2 |
| J3 torn tail never cut | 5 |
| J4 off-by-one in the tail scan | 9 |
| J5 `split` without `-1` | 1 |
| J6 `failed` never set | 2 |
| J7 market price written as `-1` | 5 |
| J8 `readAll` accepts an unterminated tail | 1 |
| J9 market price check removed in `decode` | 1 |
| J10 null timestamp written as `null` | 1 |
| J11 scan only the last block | 1 |
| X1 engine before journal | 1 |
| X2 `requireNonNull` removed | 1 |
| X3 non-empty journal accepted | 1 |
| **J1 no `force` in `append`** | **0** |
| **J12 no `force` after truncating** | **0** |

**Why J1 and J12 survive:** whether data reached the *disk* (and not just the OS cache) only shows after a power cut. A unit test runs on a live machine, where the OS cache is always there to read from. Proving it needs fault-injection file systems (guide Part 4), which is out of scope. That's why those two lines are covered by **code review and reasoning**, not by tests. Say this openly if asked; it's the honest answer.

---

## 6. Known limits and what comes next

| Limit | Why it's acceptable now | When |
|---|---|---|
| Restart refuses a non-empty journal | replay doesn't exist yet | **3D:** replay, restoring the sequencer's counters and the engine's last id |
| One fsync per command (~500–1,000 commands/s on your laptop) | correct and simple; batching needs a queue | step 4/6: group commit or smart batching (Spec §6) |
| Engine exceptions after `append` are poison pills | rare: they mean a bug | step 7: poison-pill policy |
| New file: the folder isn't fsynced (Linux) | Java on Windows can't do it; small window on first creation only | if deploying on Linux |
| No lock against a second process opening the same file | single writer by design; on Windows a whole-file lock would also block `readAll`'s reader | step 4 (one process owns the journal) |
| `readAll` builds one big `List`; reads byte by byte | fine for test-sized journals | step 6/7: streaming, snapshots |
| No checksum per record | a bit flip inside a *complete* record could still parse | optional: CRC32 per record (guide 2.7) |
| Sequence numbers aren't checked on read | the sequencer guarantees them | 3D: replay can verify `seq = previous + 1` (gap and duplicate detection) |

---

## 7. Interview questions based on this code

Try to answer each one out loud before reading the model answer in section 8. In an interview, the *reason* matters more than the API name.

**Java I/O**
1. Why does `append` use a `FileChannel` instead of a `BufferedWriter`?
2. What would go wrong if `open` used `CREATE, WRITE` without `APPEND`?
3. Why does `readAll` read bytes instead of using `BufferedReader.readLine()`?
4. Why can't the journal's own channel be used for reading?
5. Why is the write wrapped in `while (record.hasRemaining())`?
6. What does `StandardCharsets.UTF_8` protect against, given that the file is pure ASCII?

**Durability**
7. What's the difference between `flush()` and `force(true)`? Which failures does each survive?
8. Why `force(true)` and not `force(false)`?
9. What does fsync on every append cost here, and what would you do if that became a bottleneck?
10. Why does the journal refuse every append after one failed write? Why not just retry?
11. Why is it safe to cut a torn last record, but not a complete one that fails to parse?
12. Why fsync after truncating the torn tail?

**Design**
13. Why does the journal call live in `Exchange` and not inside `MatchingEngine`?
14. What does "write-ahead" guarantee, and what does it *not* guarantee? Give a scenario.
15. Why is `Command` a sealed interface? Why is the record tag in the file but not in the Java type?
16. Why is a market order's price written as `-` instead of `-1`?
17. Why are decode errors `IllegalStateException` and not `IllegalArgumentException`?
18. Why does `encode` reject a null timestamp *before* writing anything?
19. Why is `open` a static factory with a private constructor?
20. Why does `Exchange` refuse a journal that already has records?

**Testing and trade-offs**
21. Which behaviours can't your unit tests prove, and how would you gain confidence in them?
22. How did you check that the tests actually test something?
23. Text or binary format: which did you pick, and when would you switch?
24. How would this design change for 100,000 orders per second?
25. A client sent an order, the exchange crashed, and after restart the order is on the book, but the client never got a response. Is that a bug? What would you do?

---

## 8. Model answers

1. A `BufferedWriter` keeps data in a Java array and hides the stream underneath, so you can't fsync through it. A `FileChannel` passes bytes straight to the OS (nothing to forget to flush) and offers `force()` and `truncate()`.
2. Every restart would write from position 0, overwriting the oldest records. The history would be silently destroyed. Mutation J2 proves the tests catch it.
3. `readLine()` returns an unfinished last line like a finished one. A torn record can parse as a *different valid* command (e.g. "cancel order 12" cut to "cancel order 1"). Requiring the `\n` byte makes completeness explicit.
4. It's opened with `APPEND`, and Java doesn't allow `READ` together with `APPEND`.
5. A channel write may write fewer bytes than requested; the loop guarantees the whole record is written.
6. The platform default charset (windows-1252 on your Java 17) differs between machines and Java versions. Naming UTF-8 makes the bytes identical everywhere, and keeps the code correct if a field ever contains non-ASCII text.
7. `flush()` moves data from Java to the OS: it survives a process crash. `force` (fsync) moves it from the OS cache to the disk: it survives a power cut.
8. `true` also saves metadata, including the file size. For a file that grows, the size is needed to find the new data. `false` is an optimization worth measuring later.
9. About 1–2 ms per command on the laptop, so roughly 500–1,000 commands/s on one thread. Fix: group commit or smart batching, which needs a queue in front of a single writer. Process each batch only *after* its fsync.
10. A failed write may leave half a record, and the next record would be glued to it, creating corruption in the *middle*. A failed fsync can't be retried safely, because the OS may have discarded the data and marked it clean. Stopping and recovering on restart is the only safe choice; PostgreSQL does the same.
11. A torn record's `append` never returned, so it was never processed: nothing depends on it. A complete record was fully written, so its command may have been processed and seen by clients. Dropping it could silently lose a trade, so a human must look.
12. Otherwise, after a second crash, the truncation might not be on disk while new records are, and the old torn bytes could reappear in the middle of the file.
13. Replay feeds journalled commands to the engine. If the engine journalled, replay would append everything again, or need a second entry point, which breaks "one code path" (D12). It also keeps the engine pure: no I/O, easy to test.
14. Anything visible (a trade, a response) was durably journalled first: "visible ⇒ durable". It does *not* guarantee that every journalled command got a response. A crash between `append` and the response leaves a journalled command whose client heard nothing, and replay will process it.
15. `readAll` must return mixed kinds in order, so they need a common type. `sealed` lets the compiler know every kind. The file needs a tag because bytes have no type; Java objects already *are* their type, so a tag field would duplicate it and allow contradictions.
16. `-1` is an in-memory sentinel. Writing it would make the file format depend on an implementation detail. `-` is explicit, and `decode` rejects `-1`, so the two can't be confused.
17. By convention (D27), `IllegalArgumentException` means "a client sent bad input"; `IllegalStateException` means "our own state or data is broken". The journal is our own data.
18. A record that can't be read back is a poison pill: every replay would crash on it. Refusing before writing keeps the file clean, and the journal stays usable.
19. Recovery must run before the append channel exists (different open modes). A static factory can do that work first and names it (`open`). The private constructor guarantees no journal skips recovery.
20. A fresh `Sequencer` restarts at seq 1 / id 1, so it would reuse ids already in the journal. Replay (3D) must first restore the counters from the journal.
21. That `force` really reaches the disk (mutations J1 and J12 survive). Confidence comes from code review, from the documented OS guarantees, and in industry from fault-injection file systems or real power-cut testing.
22. Mutation testing: break one line at a time and confirm that some test fails. 13 of 15 mutations were caught; the 2 survivors are explained (21).
23. Text: readable, easy to debug, fast enough at this scale. Switch to binary (length-prefixed records, maybe with a CRC) when profiling shows parsing or size matters, e.g. at step 6.
24. Batch fsyncs (group commit or smart batching) behind a queue; binary records with checksums; maybe memory-mapped files (Chronicle Queue, Kafka); replicate the journal to another machine before acknowledging (LMAX); snapshots to keep replay short.
25. Not a bug in the journal: it's the "durable but not visible" gap. The command was durable, so replay correctly applied it. The fix is at the protocol level: clients attach their own order ids (FIX `ClOrdID`), the exchange detects resends, and clients can ask for the status of an order after reconnecting.

---

## 9. The 60-second version (for "tell me about something you built")

> "I built the write-ahead input log for a single-threaded matching engine. Every client command is stamped with a sequence number, timestamp and order id, appended to an append-only text file, and fsynced *before* the engine processes it, so anything a client sees is guaranteed to be replayable after a crash. On startup, the journal cuts off a torn last record, which is safe because that command was never processed, but it fails loudly on any other corruption rather than guessing. A failed write or fsync makes the journal fail-stop, because retrying a failed fsync can silently lose data. The engine itself does no I/O, so replay feeds the same engine the same commands through the same code path. Per-command fsync costs about 1–2 ms on my laptop; the next step is group commit behind a single-writer queue."
