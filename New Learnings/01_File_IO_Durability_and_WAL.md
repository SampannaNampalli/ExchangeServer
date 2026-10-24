# File I/O, Durability and Write-Ahead Logs: a 2-hour guide

**For:** ExchangeServer, roadmap step 3C (the write-ahead input log).
**Written:** 2026-10-22, for Java 17 (Temurin 17.0.20) on Windows 11.
**Every lab and code example in this file was run on your machine** while this guide was being written. The outputs shown are the real outputs. General figures, like typical disk speeds, are marked as rough.

---

## How to use this guide

**Time plan (about 2 hours):**

| Part | Topic | Time |
|---|---|---|
| 0 | The big picture | 5 min |
| 1 | Java file I/O (Labs 1–3) | 40 min |
| 2 | Durability and write-ahead logging (Lab 4) | 35 min |
| 3 | Serialization: objects to text and back (Lab 5) | 20 min |
| 4 | Testing code that uses files | 5 min |
| 5 | How real systems do it | 8 min |
| 6 | Your step 3C checklist | 2 min |
| 7 | Self-check questions | 5 min |
| 8–10 | Glossary, context for a new chat, further reading | reference only |

**What the markers mean:**

- **★ Core:** you need this for step 3C, and it's common in interviews. Learn it properly.
- **☆ Valued:** real-world knowledge that companies care about. Understand the idea; no need to master it today.
- **○ Awareness:** just know the name and roughly what it is. We'll come back to it in a later step.

**How to run the labs.** Every lab is one small, self-contained program. Copy it into a file anywhere *outside* the project's `src` folder (for example `New Learnings/labs/Lab1.java`), open a terminal in that folder, and run:

```
java Lab1.java
```

No compiling step is needed: Java 11+ can run a single source file directly. The labs create their files in your system's temp folder, except Lab 2, which writes into the folder you run it from.

**If you get stuck:** start a new chat, attach this file, and ask about a section by its number (e.g. "explain 2.3 again with another example"). Section 9 tells that chat what it needs to know about the project.

---

## Part 0: The big picture (5 min)

Our exchange keeps the order book **in memory**. If the process dies, the book is gone. The plan (Spec §4, decision D12) is:

```
LIVE:     client request -> sequencer (stamps) -> LOG: append + make durable -> engine -> response
RESTART:  LOG -> read every command back, in order -> same engine -> same book as before the crash
```

To build the "LOG" box correctly you need three skills:

1. **File I/O:** writing and reading files in Java without silently losing or corrupting data (Part 1).
2. **Durability:** knowing *when* data is really safe, and what "safe" costs (Part 2).
3. **Serialization:** turning a Java object into text or bytes, and back into an identical object (Part 3).

Real systems combine these into a pattern called a **write-ahead log (WAL)**. Databases, Kafka, Redis and exchanges all use it (Parts 2 and 5).

---

## Part 1: Java file I/O fundamentals (40 min)

### 1.1 A file is just bytes ★

- A file is a numbered list of **bytes** (numbers 0–255). The disk knows nothing about "lines", "records" or "objects".
- "Lines" exist only because we *agree* that byte 10 (`'\n'`) means "the line ends here".
- **Text** = characters turned into bytes by a **charset** (an encoding). The world standard is **UTF-8**:
  - `A` is 1 byte, `é` is 2 bytes, `₹` is 3 bytes.
  - So the text `café ₹100` is **9 characters but 12 bytes** in UTF-8 (Lab 1, part C).

**The charset trap (very common real-world bug):**

- If you don't name a charset, Java uses the **platform default**. On *your* machine with Java 17 that's **windows-1252** (Lab 1 shows it). Java 18+ changed the default to UTF-8, so the same code behaves differently on a different Java version or machine.
- Lab 1, part D: writing `café ₹100` with `new FileWriter(file)` (no charset) produced 9 bytes, and **`₹` silently became `?`** (byte 63) because windows-1252 has no rupee sign. No exception, no warning: just lost data.
- **Rule:** always pass `StandardCharsets.UTF_8` explicitly, both when writing *and* when reading.
- The same trap applies to source code. `javac` reads `.java` files in the default charset, so a `"café"` literal can turn into `"cafÃ©"`. This actually happened while this guide was being prepared. The fix is `javac -encoding UTF-8`; IntelliJ sets this per project.

**Line endings:**

- Windows ends lines with `\r\n` (bytes 13, 10); Linux and macOS use `\n` (byte 10).
- `BufferedWriter.newLine()` writes the **operating system's** ending. On your machine it wrote `[13, 10]` (Lab 1, part E).
- **Rule for file formats:** pick the line ending yourself (write `'\n'`), so a file written on Windows reads exactly the same on Linux.

### 1.2 Two families: byte streams and character streams ★

|  | Bytes | Characters (text) |
|---|---|---|
| **Write** | `OutputStream` (e.g. `FileOutputStream`) | `Writer` (e.g. `OutputStreamWriter`, `FileWriter`) |
| **Read** | `InputStream` (e.g. `FileInputStream`) | `Reader` (e.g. `InputStreamReader`, `FileReader`) |

The bridge between them is the charset. `OutputStreamWriter(stream, UTF_8)` turns characters into bytes; `InputStreamReader(stream, UTF_8)` turns bytes into characters.

You build a writer by **wrapping** layers, and each layer adds one feature:

```java
Writer w = new BufferedWriter(                       // adds a buffer (fewer, bigger writes)
               new OutputStreamWriter(                // characters -> bytes, using UTF-8
                   new FileOutputStream(file, true),  // bytes -> the file; true = append
                   StandardCharsets.UTF_8));
```

☆ Interview point: `java.io` is the textbook example of the **Decorator pattern** (wrapping an object to add behaviour without changing it).

### 1.3 The modern API: `Path` and `Files` ★

`java.nio.file` (Java 7+) is the modern way to work with files.

- `Path` is a *location*, not an open file: `Path p = Path.of("data", "log.txt");`
- `Files` has static helper methods:

| Method | What it does |
|---|---|
| `Files.writeString(p, text, UTF_8, options...)` | write a whole string |
| `Files.readString(p, UTF_8)` / `Files.readAllLines(p, UTF_8)` | read a whole (small) file |
| `Files.newBufferedWriter(p, UTF_8, options...)` | open a buffered writer |
| `Files.newBufferedReader(p, UTF_8)` | open a buffered reader |
| `Files.exists(p)`, `Files.size(p)` | check a file |
| `Files.createDirectories(dir)` | create folders (no error if they exist) |
| `Files.createTempFile(...)`, `Files.createTempDirectory(...)` | scratch files and folders |
| `Files.deleteIfExists(p)`, `Files.move(a, b, options...)` | delete, rename or move |

- Small files: `readString` / `readAllLines` load everything into memory at once.
- Big files: read line by line with `newBufferedReader` or `Files.lines(p)`. `Files.lines` keeps the file open, so close it (use try-with-resources).
- ☆ `FileChannel` (`java.nio.channels`) is the lower-level, byte-based tool. It's the one that can **force data to disk** (Part 2), report the exact size, and **truncate** a file. See 1.9.

### 1.4 Open options: append or overwrite? ★

When you open a file for writing, `StandardOpenOption` values say how:

| Option | Meaning |
|---|---|
| `CREATE` | create the file if it's missing |
| `CREATE_NEW` | create it, and **fail** if it already exists |
| `APPEND` | every write goes to the **end** of the file |
| `TRUNCATE_EXISTING` | **empty** the file when opening it |
| `WRITE` / `READ` | open for writing / reading |
| `SYNC` / `DSYNC` | every write is forced to disk (Part 2) |

**Trap:** `Files.newBufferedWriter(path, UTF_8)` with **no options** behaves as `CREATE + TRUNCATE_EXISTING + WRITE`. It **empties the file every time you open it** (Lab 1, part A: the file ended up containing only `[second]`). With `CREATE, APPEND` the old content stays (part B: `[second, third]`).

The old API has the same trap: `new FileOutputStream(file)` overwrites, while `new FileOutputStream(file, true)` appends.

For a log, a restart must never erase what is already there.

### Lab 1: append vs overwrite, characters vs bytes, line endings (5 min)

```java
import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.Arrays;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

public class Lab1 {
    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("lab1");
        Path notes = dir.resolve("notes.txt");

        // A. No options = CREATE + TRUNCATE_EXISTING + WRITE: every open empties the file.
        try (BufferedWriter w = Files.newBufferedWriter(notes, UTF_8)) { w.write("first\n"); }
        try (BufferedWriter w = Files.newBufferedWriter(notes, UTF_8)) { w.write("second\n"); }
        System.out.println("A. reopened without APPEND: " + Files.readAllLines(notes, UTF_8));

        // B. CREATE + APPEND keeps what is already there.
        try (BufferedWriter w = Files.newBufferedWriter(notes, UTF_8, CREATE, APPEND)) { w.write("third\n"); }
        System.out.println("B. reopened with APPEND:    " + Files.readAllLines(notes, UTF_8));

        // C. Characters are not bytes.
        String text = "caf" + (char) 0xE9 + " " + (char) 0x20B9 + "100";   // "cafe" with an accent, space, rupee sign, 100
        Path utf8 = dir.resolve("utf8.txt");
        Files.writeString(utf8, text, UTF_8);
        System.out.println("C. " + text.length() + " characters -> " + Files.size(utf8) + " bytes in UTF-8");
        System.out.println("   default charset on this machine: " + Charset.defaultCharset());

        // D. Writing with the default charset (no charset given) silently loses characters.
        Path def = dir.resolve("default.txt");
        try (Writer w = new FileWriter(def.toFile())) { w.write(text); }
        byte[] bytes = Files.readAllBytes(def);
        System.out.println("D. default-charset file: " + bytes.length + " bytes " + Arrays.toString(bytes));
        System.out.println("   same text as before? " + new String(bytes, UTF_8).equals(text));

        // E. newLine() writes the operating system's line separator.
        Path nl = dir.resolve("newline.txt");
        try (BufferedWriter w = Files.newBufferedWriter(nl, UTF_8)) { w.write("x"); w.newLine(); }
        System.out.println("E. bytes written for \"x\" + newLine(): " + Arrays.toString(Files.readAllBytes(nl)));
    }
}
```

Output on your machine:

```
A. reopened without APPEND: [second]
B. reopened with APPEND:    [second, third]
C. 9 characters -> 12 bytes in UTF-8
   default charset on this machine: windows-1252
D. default-charset file: 9 bytes [99, 97, 102, -23, 32, 63, 49, 48, 48]
   same text as before? false
E. bytes written for "x" + newLine(): [120, 13, 10]
```

In D, Java prints bytes as *signed* numbers (-128 to 127), so `-23` is byte 233 (`é` in windows-1252), and `63` is `?`, where the `₹` used to be.

### 1.5 Buffering and `flush()` ★

- Every write to the operating system is a **system call**: your program asks the OS kernel to do something. System calls are slow compared to normal code, so **buffers** collect many small writes and pass them to the OS in one big write.
- `BufferedWriter` keeps up to 8,192 characters in a plain Java array, inside your process.

| Call | What really happens |
|---|---|
| `write("hello\n")` | copied into Java's array, **still inside your process** |
| `flush()` | the bytes are handed to the operating system |
| `close()` | `flush()`, then the file is released |

If the process dies before `flush()`, the buffered data is simply gone. Lab 2 proves it. `Runtime.getRuntime().halt(1)` stops the JVM instantly, the closest Java gets to `kill -9` or Task Manager's "End task":

| After `write("hello\n")`, the program... | Bytes in the file |
|---|---|
| did nothing, then crashed | **0** |
| called `flush()`, then crashed | 6 |
| called `close()`, then crashed | 6 |
| did nothing, then called `System.exit(0)` | **0** (`exit` does *not* flush your writers) |

**Important:** `flush()` does **not** mean "on the disk". It means "the OS has it". Part 2 explains the difference.

### Lab 2: crash before flush (5 min)

```java
import java.io.*;
import java.nio.file.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

public class Lab2 {
    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "nothing";        // nothing | flush | close
        Path file = Path.of("lab2-output.txt");                     // created in the folder you run from
        Files.deleteIfExists(file);

        BufferedWriter w = Files.newBufferedWriter(file, UTF_8, CREATE, APPEND);
        w.write("hello\n");                                         // 6 bytes, now in Java's buffer
        if (mode.equals("flush")) w.flush();
        if (mode.equals("close")) w.close();

        System.out.println("Mode '" + mode + "': crashing now (like kill -9). Now check " + file.toAbsolutePath());
        Runtime.getRuntime().halt(1);                               // no cleanup, no flushing, nothing
    }
}
```

Run it three times and look at `lab2-output.txt` after each run (open it, or check its size in the file explorer):

```
java Lab2.java nothing      -> file has 0 bytes
java Lab2.java flush        -> file has 6 bytes ("hello" + newline)
java Lab2.java close        -> file has 6 bytes
```

### 1.6 Closing files: try-with-resources ★

```java
try (BufferedWriter w = Files.newBufferedWriter(p, UTF_8, CREATE, APPEND)) {
    w.write("hello\n");
}   // w.close() runs here automatically, even if write() threw an exception
```

- This works for anything that implements `AutoCloseable`. With several resources, they're closed in **reverse** order.
- If you forget to close:
  - data still in the buffer is lost;
  - the operating system's "file handle" leaks (each process can only have so many);
  - on **Windows** a file that is still open often **can't be deleted or renamed**. For example, JUnit can fail to clean up its temp folder.

**A file that stays open for the whole program run** (like a log) can't live inside one `try` block. Instead, a class keeps the open writer in a field and is itself `AutoCloseable`, so whoever owns it can close it:

```java
class Diary implements AutoCloseable {
    private final BufferedWriter out;

    Diary(Path file) throws IOException {
        out = Files.newBufferedWriter(file, UTF_8, CREATE, APPEND);   // opened once
    }

    void add(String entry) throws IOException {
        out.write(entry);
        out.write('\n');
    }

    @Override
    public void close() throws IOException {
        out.close();                                                   // flushes, then releases the file
    }
}

try (Diary d = new Diary(Path.of("diary.txt"))) {
    d.add("Mon: learned flush");
    d.add("Tue: learned fsync");
}
```

Notice that `add` never flushes. If the program crashes between two `add` calls, entries can be missing. Whether that's acceptable is exactly the question of Part 2.

☆ Effective Java, Item 9: "Prefer try-with-resources to try-finally".

### 1.7 When things go wrong: `IOException` ★

- `IOException` is a **checked** exception: the compiler makes you either catch it or declare `throws IOException`.
- Real causes: the file or folder doesn't exist, no permission, **disk full** ("There is not enough space on the disk"), a USB drive was removed, the file is locked by another program.
- **Never swallow it:** `catch (IOException e) {}` turns "data was lost" into "everything looks fine". Either handle it meaningfully or let it propagate.
- `UncheckedIOException` wraps an `IOException` when you're inside code that can't throw checked exceptions (lambdas, some interfaces).
- **Connection to our project:** under write-ahead, if writing a command to the log fails, that command **must not be processed**, and the caller must find out. How the caller finds out is your design decision.
- ☆ If the try-block body throws *and* `close()` throws, Java keeps the body's exception and attaches the close exception as **suppressed** (`e.getSuppressed()`), so neither one is lost.

### 1.8 Reading text back, and the last-line trap ★

```java
try (BufferedReader r = Files.newBufferedReader(p, UTF_8)) {
    String line;
    while ((line = r.readLine()) != null) {      // null means "end of file"
        // use line (it has no '\n' at the end)
    }
}
```

- `readLine()` returns the next line **without** its `\n` or `\r\n`, or `null` at the end of the file. It also treats a lone `\r` as a line end, which is another reason to control your line endings.

**The trap (Lab 3):** the file `A\nB\nC\n` and the file `A\nB\nC` both read as `[A, B, C]`. `readLine()` **cannot tell you whether the last line was finished.**

Why it matters: in a log, an unfinished last line is a record that a crash cut in half. You need another way to check, for example looking at the file's **last byte**:

```java
try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
    if (ch.size() > 0) {
        ByteBuffer one = ByteBuffer.allocate(1);
        ch.read(one, ch.size() - 1);            // read 1 byte at the last position
        boolean complete = one.get(0) == '\n';
    }
}
```

(Other ways exist, e.g. reading the file as bytes and tracking positions yourself.)

### 1.9 The byte level: `FileChannel` and `ByteBuffer` ☆

You need this level for two things that the `Writer` classes can't do: forcing data to disk, and truncating.

- A `ByteBuffer` is a byte array plus a **position** (where the next read or write happens) and a **limit** (where the data ends). `ByteBuffer.wrap(bytes)` makes one ready for writing out.
- `FileChannel.open(p, CREATE, WRITE, APPEND)` opens a channel for appending.
- Write in a loop: `while (buf.hasRemaining()) ch.write(buf);`. In theory one call may write fewer bytes than you asked for.
- `ch.size()` is the exact file size in bytes.
- `ch.truncate(n)` **cuts the file down to `n` bytes**, throwing away everything after them. Lab 3 cuts `A\nB\nC` back to `A\nB\n`.
- `ch.force(true)` sends data to the disk (Part 2).
- **Practical point:** `Files.newBufferedWriter` hides the stream underneath it, so you **cannot force-to-disk through it**. If you need that, build the layers yourself and keep a reference to the bottom layer (`FileOutputStream` or `FileChannel`). See 2.3.

### Lab 3: the last-line trap and cutting a tail (5 min)

```java
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

public class Lab3 {
    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("lab3");
        Path complete = dir.resolve("complete.txt");
        Path cut = dir.resolve("cut.txt");
        Files.writeString(complete, "A\nB\nC\n", UTF_8);
        Files.writeString(cut, "A\nB\nC", UTF_8);                    // as if a crash stopped the last write

        for (Path p : List.of(complete, cut)) {
            System.out.println(p.getFileName() + ": readLine sees " + readLines(p)
                    + ", last byte is newline? " + endsWithNewline(p));
        }

        // Cut off the unfinished tail: keep everything up to and including the last '\n'.
        // (lastIndexOf on a String counts chars; that equals bytes only because this demo is plain ASCII.)
        long keep = Files.readString(cut, UTF_8).lastIndexOf('\n') + 1;
        try (FileChannel ch = FileChannel.open(cut, WRITE)) {
            ch.truncate(keep);
        }
        System.out.println("after truncate(" + keep + "): " + readLines(cut)
                + ", last byte is newline? " + endsWithNewline(cut));
    }

    static List<String> readLines(Path p) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(p, UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        return lines;
    }

    static boolean endsWithNewline(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, READ)) {
            if (ch.size() == 0) return false;
            ByteBuffer one = ByteBuffer.allocate(1);
            ch.read(one, ch.size() - 1);                            // read 1 byte at the last position
            return one.get(0) == '\n';
        }
    }
}
```

Output on your machine:

```
complete.txt: readLine sees [A, B, C], last byte is newline? true
cut.txt: readLine sees [A, B, C], last byte is newline? false
after truncate(4): [A, B], last byte is newline? true
```

---

## Part 2: Durability and write-ahead logging (35 min)

### 2.1 What "durable" means ★

**Durable** means: once the system says "saved", the data stays saved, even if something crashes right after. It's the **D in ACID** (the four guarantees of database transactions).

Always ask: **durable against what?**

| Failure | Examples | Data in Java's buffer | Data in the OS cache | Data on the disk |
|---|---|---|---|---|
| Exception in your code (process keeps running) | bug, bad input | safe | safe | safe |
| **Process crash** | `kill -9`, "End task", out-of-memory kill, JVM crash | **lost** | safe: the OS still writes it | safe |
| **Machine crash** | power cut, kernel panic, the VM's host dies | lost | **lost** | safe |
| Disk dies | hardware failure | lost | lost | **lost**: you need copies on another machine (replication, Part 5) |

### 2.2 The journey of a byte ★

```
  your String
      |  write()
      v
 [1] Java buffer           (an array inside your process)
      |  flush()            -> a "write" system call
      v
 [2] OS page cache         (kernel memory; the OS writes it to disk later, when it chooses)
      |  fsync              -> Java: FileChannel.force(...) or FileDescriptor.sync()
      v
 [3] the disk              (the drive may have its own small cache; see 2.3)
```

- After `flush()`: the data survives a **process crash**. The OS has it and will write it out. On Linux, by default, data older than about **30 seconds** is always written out.
- After **fsync**: the data survives a **power cut**. `fsync` is the name of the Unix system call; Windows calls it `FlushFileBuffers`.
- The **page cache** is why ordinary programs feel fast: writes go to memory and the OS saves them in the background.

### 2.3 Forcing data to disk in Java ★

Three ways:

```java
// (a) FileChannel: write bytes, then force
try (FileChannel ch = FileChannel.open(p, CREATE, WRITE, APPEND)) {
    ByteBuffer buf = ByteBuffer.wrap("hello\n".getBytes(UTF_8));
    while (buf.hasRemaining()) ch.write(buf);
    ch.force(true);                  // returns once the OS reports the data is on the device
}

// (b) Stream layers you built yourself: flush FIRST, then sync
FileOutputStream fos = new FileOutputStream(file, true);
Writer w = new BufferedWriter(new OutputStreamWriter(fos, UTF_8));
w.write("hello\n");
w.flush();                           // [1] -> [2]
fos.getFD().sync();                  // [2] -> [3]

// (c) An open option: every write is forced automatically
FileChannel ch = FileChannel.open(p, CREATE, WRITE, APPEND, DSYNC);
```

- **Order matters in (b):** calling `sync()` without `flush()` first syncs nothing new. The data is still in Java's array.
- `force(true)` also saves the file's **metadata** (size, timestamps); `force(false)` may skip it. For a file that grows, the size matters, so `force(true)` is the simple, safe choice. (`force(false)` is a later optimization: on Linux it maps to `fdatasync`, which still saves the size.)
- `SYNC` = content + metadata on every write; `DSYNC` = content only. Simple to use, but you lose control over *when* to force (see group commit, 2.4).
- ○ **Brand-new files:** on Linux, a file's *name* lives in its folder, so to be sure a newly created file survives a power cut, the **folder** must be fsynced too. Know that this exists; plain Java on Windows doesn't let you do it.
- ○ **Disks can lie:** some drives report "written" while the data is still in their own volatile cache. Servers use drives with **power-loss protection** (or battery-backed controllers). On a laptop, treat fsync as "the best we can do".
- ☆ **If fsync fails, don't just retry.** The OS may already have thrown the unsaved data away and marked it clean, so a second fsync can "succeed" without saving anything. PostgreSQL learned this in 2018 ("fsyncgate") and now **stops with a fatal error** instead of retrying. Treat an fsync failure as fatal.

### 2.4 The price of durability ★

Lab 4 measured this on your machine. Two runs gave about **0.9 ms** and **1.9 ms** per fsync:

```
1000 appends, no fsync:               6.5 ms
1000 appends, fsync every one:     1937.4 ms
1000 appends, fsync every 100th:     16.0 ms
```

What that means:

- **Without fsync:** about 6 microseconds per append.
- **With one fsync per command:** about 1–2 ms each, so at most **about 500–1,000 commands per second**, however fast the matching engine is. Matching itself takes microseconds.
- Rough typical fsync times: tens of microseconds (server SSD with power-loss protection), about a millisecond or more (laptop SSD, as you just measured), 5–15 ms (spinning hard disk).

**Group commit (batching) ☆:** collect several commands, write them all, fsync **once**, and only then process and answer all of them. In Lab 4, one fsync per 100 appends was **over 100 times faster** than one fsync per append.

- Trade-off: much higher **throughput** (commands per second), but each command may **wait a little** for its batch (higher **latency**).
- PostgreSQL (`commit_delay`), MySQL, Kafka producers and the LMAX exchange all batch like this. We'll measure it in step 6, not now.
- How a batch is closed:
  - **Size or timeout:** close the batch at N records or after T milliseconds, whichever comes first. Simple, but under light load each command also waits up to T.
  - **Smart batching** (Martin Thompson, LMAX): no timer. Each time the writer is ready, it takes **whatever is already waiting**, writes it all and fsyncs once. Under light load a batch is one command, so nothing waits. Under heavy load batches grow on their own. Usually there's a maximum batch size.
- Both ways need **many commands waiting at the same time** (a queue in front of a writer thread). If each caller waits for its answer before sending the next command, every batch holds just one command.

**A real-world dial: Redis's append-only file (`appendfsync`)**

| Setting | What it does | Risk on a machine crash |
|---|---|---|
| `always` | fsync after every write command | (almost) nothing lost; slowest |
| `everysec` (the default) | fsync once per second, in the background | up to about 1 second of writes lost |
| `no` | let the OS decide | more can be lost; fastest |

There's no "right" setting, only a choice between speed and how much data you may lose. Every serious system makes this choice explicitly, and **you will too** in 3C.

### 2.5 Torn writes ★

- A crash can stop a write **halfway**. You wrote 40 bytes, and only the first 17 reached the disk. Even a single `write()` call is not guaranteed to be all-or-nothing on a power cut.
- In an **append-only** file, only the **last** record can be torn, because every earlier record was finished before the next one started.
- **One condition:** "finished" must mean *on the disk*. If each record is fsynced before the next is written, a power cut can only damage the last one. Without fsync, the OS writes its cached pages to disk in whatever order it likes, so a power cut can leave damage *further back* (for example a block of zeros). A process crash alone can't do that, because the OS cache survives it (2.1).
- So:
  - a **torn last record** is expected after a crash;
  - a **broken record in the middle** is *not* a crash artifact. It means a bug, disk corruption, or someone edited the file. **Stop and report loudly; never guess.**
- ○ Databases that **overwrite** pages in place face "torn pages" (PostgreSQL's `full_page_writes` exists for this). Append-only files avoid most of that problem, which is one reason logs are append-only.

### 2.6 Write-ahead logging (WAL) ★

**The rule:** write down what you are *about to do*, durably, **before** you do it or show any effect of it.

After a crash:

- a **complete** record can be **redone** from the log (that's replay);
- an **incomplete** record never had any effect, so it's **safe to discard**.

**Why discarding the torn last record is safe** (from our earlier discussion):

1. A record can only be torn if the crash happened *during* its append.
2. So the append never returned, which means the engine never processed that command.
3. So there were no trades and no response: the system's history doesn't contain it.
4. The client got no answer, exactly as if the request had been lost in the network.

☆ **The opposite gap:** the record was saved completely, but the crash happened *before* the response reached the client. After replay, the order exists, but the client never heard about it and may send it again, creating a **duplicate**. Real exchanges solve this with **client-chosen order ids** (FIX tag 11, `ClOrdID`) and duplicate detection. We'll meet this in steps 4 and 7.

**Where you'll find a WAL:**

| System | Its write-ahead log |
|---|---|
| PostgreSQL | WAL files in `pg_wal` |
| MySQL (InnoDB) | redo log |
| SQLite | rollback journal, or "WAL mode" |
| Redis | AOF (append-only file) |
| Kafka | the log *is* the data |
| File systems | ext4 journal, NTFS `$LogFile` |
| LMAX exchange | the journaller runs before the business logic |

☆ **Event sourcing:** store the sequence of **input events** as the source of truth, and rebuild the current state by replaying them. That's exactly our design (D12). It's a common term in system-design interviews. ○ It often appears together with **CQRS** (separate models for writing and reading).

○ **Snapshots** (step 7): replaying years of log is slow, so systems save their state from time to time and replay only the log *after* the snapshot. A snapshot is written safely like this: write to a temp file, fsync, then **atomically rename** it over the old one (`Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)`). A reader then sees either the whole old file or the whole new one, never half of each.

☆ **One writer per file.** Two threads, or two programs, appending to the same file without coordination can interleave their bytes and mix records together. Our design has a single writer thread (step 4). ○ `FileChannel.lock()` can stop a *second copy of the program* from opening the same log by mistake.

### 2.7 Recovery on startup ★

In plain words, a general recipe (not code):

1. Open the log. If it doesn't exist, the history is empty.
2. Read records from the start, in order. For each one, ask: is it **complete** and **valid**?
3. If the **last** record is incomplete, cut the file back to the end of the last good record (truncate). This must happen **before** anything new is appended, or the next record would be glued onto the broken half.
4. If a bad record is **not** the last one, stop with a loud error. A human must look at it.
5. Only now start appending new records.

Real-world anchors:

- Redis: `aof-load-truncated yes` loads an AOF whose last command is incomplete, drops that command, and logs a warning.
- PostgreSQL: crash recovery replays WAL records until the first one that fails its check, and treats that point as the end of the log.

☆ **Checksums:** a record can be "complete" (it has its `\n`) and still be **wrong**: a flipped bit or a bad disk sector can turn `21.5` into `21.6`, and it still parses. A **CRC32** stored with each record detects this. Java has `java.util.zip.CRC32` (and `CRC32C` since Java 9). Kafka keeps a CRC per batch of records, and PostgreSQL one per WAL record. Optional for us now, but good to know (Lab 5 shows that one changed character changes the whole CRC).

### Lab 4: the price of fsync (5 min)

```java
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.*;

public class Lab4 {
    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("lab4");
        byte[] record = "reading,42,2026-01-01T10:00:00Z,21.5\n".getBytes(UTF_8);
        int n = 1000;

        run(dir.resolve("warmup.log"), record, n, 0);               // let the JVM warm up first
        System.out.printf("%d appends, no fsync:          %8.1f ms%n", n, run(dir.resolve("a.log"), record, n, 0));
        System.out.printf("%d appends, fsync every one:   %8.1f ms%n", n, run(dir.resolve("b.log"), record, n, 1));
        System.out.printf("%d appends, fsync every 100th: %8.1f ms%n", n, run(dir.resolve("c.log"), record, n, 100));
    }

    static double run(Path p, byte[] record, int n, int syncEvery) throws IOException {
        long start = System.nanoTime();
        try (FileChannel ch = FileChannel.open(p, CREATE, WRITE, APPEND)) {
            for (int i = 1; i <= n; i++) {
                ByteBuffer buf = ByteBuffer.wrap(record);
                while (buf.hasRemaining()) ch.write(buf);           // a write may take several calls
                if (syncEvery > 0 && i % syncEvery == 0) ch.force(true);
            }
        }
        return (System.nanoTime() - start) / 1e6;
    }
}
```

Your numbers will vary a little from run to run. Look at the **ratios**, not the exact milliseconds.

---

## Part 3: Serialization, objects to text and back (20 min)

### 3.1 What and why ★

- **Serialization:** turning an object into bytes or text, to store it in a file or send it over a network. **Deserialization:** the reverse.
- You need it here (the log) and again in step 4 (the network gateway).

| Kind | Examples | Good | Bad |
|---|---|---|---|
| **Text** | CSV-like lines, JSON, FIX `tag=value` | readable in any editor, easy to debug | bigger, slower to parse |
| **Binary** | `DataOutputStream`, Protocol Buffers, Avro, SBE | small, fast | needs tools to inspect |

- ○ **SBE (Simple Binary Encoding)** is the FIX community's binary format for low-latency trading. CME's market data uses it, for example.
- ★ **Don't use Java's built-in `Serializable` / `ObjectOutputStream` for files.** The format is tied to your classes' internals and breaks when a class changes, and reading untrusted data with it is a known security risk. Effective Java, Item 85: "Prefer alternatives to Java serialization".
- For our log, one **text line per record** is a perfectly good choice: you can open the file and read it, which makes debugging easy.

### 3.2 Framing: where does one record end? ★

**Framing** is how a reader finds the boundaries between records.

- **Delimiter:** a special character ends each record (e.g. a newline), and another separates fields (e.g. a comma). The delimiter must **never appear inside a field**; if it can, you need escaping (CSV's quoting rules exist for this).
- **Length prefix:** `[4-byte length][record bytes]`, mostly in binary formats. No escaping needed, but you can't read it by eye.

Check every field you plan to write: can it ever contain your separator or a newline? Enum names, numbers and ISO timestamps can't, which is why they're easy.

### 3.3 Writing each field so it reads back *exactly* ★

| Type | Write | Read back | Watch out |
|---|---|---|---|
| `long` | `Long.toString(x)` | `Long.parseLong(s)` | bad text throws `NumberFormatException`; treat it as a corrupt record |
| `enum` | `e.name()` | `MyEnum.valueOf(s)` | **never `ordinal()`**: adding or reordering constants changes every number, and old files then mean something else (Effective Java, Item 35). `valueOf` is case-sensitive and throws `IllegalArgumentException` |
| `Instant` | `t.toString()` (ISO-8601, e.g. `2023-11-14T22:13:20.123456789Z`) | `Instant.parse(s)` | exact, down to the nanosecond. `toEpochMilli()` throws away anything smaller than a millisecond |
| "no value" | an explicit marker you choose | must come back as something your constructors accept | decide it on purpose. Writing `null` as the text `"null"` is a classic bug source |
| `double` ○ | avoid in formats if you can | | `String.format(Locale.GERMANY, "%.2f", 1.5)` gives `1,50`; that comma breaks a comma-separated format. Use `Locale.ROOT` or `Double.toString`. Even `%d` can produce non-ASCII digits in some locales |

**Splitting trap (Lab 5):** `"A,5,,".split(",")` gives `[A, 5]`, because **trailing empty fields are dropped**. `"A,5,,".split(",", -1)` gives `[A, 5, , ]`. Then **check the number of fields** before you use them.

★ **The round-trip property:** for every object `x`, `read(write(x))` must equal `x`, field by field. It's the first thing to test for any format. Java **records** get `equals()` for free, which makes such tests easy; plain classes need their own `equals` or a field-by-field comparison.

☆ **Validate on read:** never trust what's in a file. A corrupt line must produce a clear error, not a half-built object. Constructors that validate (like `Order`'s) help.

☆ **Versioning:** put a format version somewhere (e.g. a first line `V1`). Then a future format change can still read old logs. Protobuf and Avro handle this "schema evolution" for you.

### 3.4 One Java type for several kinds of record ★ (Java 17)

A file can hold different kinds of record, so reading a record gives you "one of several kinds". The usual solution:

- the **file** stores a tag (the first field says which kind it is);
- the **Java code** gives the kinds a common supertype, so each class keeps only the fields it really has.

A neutral example, verified on Java 17:

```java
public class Shapes {
    sealed interface Shape permits Circle, Square {}
    record Circle(double radius) implements Shape {}
    record Square(double side) implements Shape {
        Square {                                                    // compact constructor: validate
            if (side <= 0) throw new IllegalArgumentException("side must be positive");
        }
    }

    static double area(Shape s) {
        if (s instanceof Circle c) return Math.PI * c.radius() * c.radius();
        if (s instanceof Square q) return q.side() * q.side();
        throw new IllegalStateException("unknown shape: " + s);
    }

    public static void main(String[] args) {
        Shape s = new Square(3);
        System.out.println(s + " area=" + area(s));
        System.out.println("records compare by value: " + new Circle(1).equals(new Circle(1)));
    }
}
```

- **`sealed ... permits`** (final in Java 17): only the listed classes may implement the interface, so the compiler knows the complete list.
- **`record`** (Java 16+): a compact, immutable data class. The constructor, accessors (`radius()`), `equals`, `hashCode` and `toString` are generated. A *compact constructor* (`Square { ... }`) can validate.
- **`instanceof` with a pattern** (Java 16+): `s instanceof Circle c` tests and casts in one step.
- In **Java 21**, `switch (s) { case Circle c -> ...; case Square q -> ...; }` with **no `default`** is checked for completeness by the compiler, like your `switch` over `OrderType`. In Java 17 that form is only a *preview* feature, so use `instanceof` as above.
- Alternative: put the behaviour on the interface itself (`double area();`, implemented by each record). That's plain polymorphism with no `instanceof` at all. Which one fits depends on whether the behaviour *belongs* to the data type.

### Lab 5: round trips and parsing traps (5 min)

```java
import java.time.Instant;
import java.util.*;
import java.util.zip.CRC32;
import static java.nio.charset.StandardCharsets.UTF_8;

public class Lab5 {
    enum Color { RED, GREEN }

    public static void main(String[] args) {
        // Instant: toString/parse is exact, epoch millis is not.
        Instant t = Instant.ofEpochSecond(1_700_000_000L, 123_456_789);
        String text = t.toString();
        System.out.println("1. " + text + " -> parse back equal? " + Instant.parse(text).equals(t));
        System.out.println("2. via toEpochMilli equal? " + Instant.ofEpochMilli(t.toEpochMilli()).equals(t));

        // Enums: name()/valueOf() are stable; valueOf is case-sensitive.
        System.out.println("3. name=" + Color.GREEN.name() + " valueOf=" + Color.valueOf("GREEN") + " ordinal=" + Color.GREEN.ordinal());
        try { Color.valueOf("green"); }
        catch (IllegalArgumentException e) { System.out.println("4. valueOf(\"green\") -> IllegalArgumentException"); }

        // split drops trailing empty fields unless the limit is negative.
        System.out.println("5. \"A,5,,\".split(\",\")     -> " + Arrays.toString("A,5,,".split(",")));
        System.out.println("6. \"A,5,,\".split(\",\", -1) -> " + Arrays.toString("A,5,,".split(",", -1)));

        // Numbers: bad text throws; formatting depends on the locale.
        try { Long.parseLong("12a"); }
        catch (NumberFormatException e) { System.out.println("7. parseLong(\"12a\") -> NumberFormatException: " + e.getMessage()); }
        System.out.println("8. String.format(Locale.GERMANY, \"%.2f\", 1.5) -> " + String.format(Locale.GERMANY, "%.2f", 1.5));
        System.out.println("9. String.format(Locale.ROOT,    \"%.2f\", 1.5) -> " + String.format(Locale.ROOT, "%.2f", 1.5));

        // Checksums: one changed character gives a completely different CRC.
        System.out.println("10. CRC32 of \"reading,42,21.5\" = " + crc("reading,42,21.5")
                + ", of \"reading,42,21.6\" = " + crc("reading,42,21.6"));
    }

    static String crc(String s) {
        CRC32 c = new CRC32();
        c.update(s.getBytes(UTF_8));
        return Long.toHexString(c.getValue());
    }
}
```

Output on your machine:

```
1. 2023-11-14T22:13:20.123456789Z -> parse back equal? true
2. via toEpochMilli equal? false
3. name=GREEN valueOf=GREEN ordinal=1
4. valueOf("green") -> IllegalArgumentException
5. "A,5,,".split(",")     -> [A, 5]
6. "A,5,,".split(",", -1) -> [A, 5, , ]
7. parseLong("12a") -> NumberFormatException: For input string: "12a"
8. String.format(Locale.GERMANY, "%.2f", 1.5) -> 1,50
9. String.format(Locale.ROOT,    "%.2f", 1.5) -> 1.50
10. CRC32 of "reading,42,21.5" = c8ba4d58, of "reading,42,21.6" = 51b31ce2
```

---

## Part 4: Testing code that uses files (5 min) ★

JUnit 5 can create a fresh, empty folder for each test and delete it afterwards:

```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TempDirExampleTest {

    @Test
    void writesAndReadsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("test.txt");          // a fresh, empty folder for this test only

        Files.writeString(file, "A\nB\n", UTF_8);

        assertEquals(List.of("A", "B"), Files.readAllLines(file, UTF_8));
    }                                                 // JUnit deletes the folder afterwards
}
```

What follows from this:

- Code that works with a file must **accept the file's location from outside** (for example, a constructor parameter). A hard-coded path can't be tested and can't be configured.
- **"Restart" in a test:** close the object, create a new one on the *same* file, and check that the old records are still there and new ones come after them.
- **A "crash" in a test:** you can't pull the power cord in a unit test, but you *can* create the file a crash would leave behind. Write two good records and *half* of a third by hand, then run your recovery and check the result.
- What unit tests **can't** prove: real power-loss durability. ○ The industry uses special test file systems that "forget" unsynced data on purpose (for example LazyFS, which the Jepsen project has used).
- On Windows, close every file in tests; otherwise the temp-folder cleanup fails.

---

## Part 5: How real systems do it (8 min) ☆

**LMAX** (a financial exchange written in Java; Martin Fowler's article, 2011): incoming events are first **journalled to disk** and **replicated to a standby machine**, and only then does a single-threaded, in-memory business logic process them. On a restart, it loads the last snapshot and **replays the journal**. Journalling and replication run in parallel and in batches. Our Vision §2 pipeline follows the same idea.

**Kafka:** each topic partition is an **append-only log** split into "segment" files. Every record has an **offset** (like our sequence number), and readers can replay from any offset. Records carry CRCs. Kafka gets durability mainly from **replication** to other machines (`acks=all`), not from an fsync per message.

**PostgreSQL:** every change goes to the **WAL** first. With the default settings, `COMMIT` returns only after the WAL is fsynced. Group commit (`commit_delay`) batches fsyncs, and each WAL record has a CRC. **Checkpoints** (like snapshots) mean recovery only replays the WAL written after the last checkpoint.

**Redis AOF:** appends every write command as text (in Redis's own protocol format), with the `appendfsync` dial from 2.4. It **rewrites** the file in the background to keep it small, and `aof-load-truncated` handles a torn last command.

**SQLite:** a single-file database (on every phone). It uses a rollback journal or WAL mode, both write-ahead techniques.

**The common pattern:** append-only files, sequence numbers or offsets, checksums, batching (group commit), snapshots or checkpoints, and replication for machine loss. You're building the first two pieces now.

○ For later performance work: **memory-mapped files** (`FileChannel.map`), used by Chronicle Queue (a Java journal used in trading systems), and **log segments and rotation**, so one file doesn't grow forever.

---

## Part 6: Your step 3C checklist (2 min)

These are the questions your design must answer. They come from our discussion in the main chat and are deliberately **questions, not answers**:

1. **Where** is the log written, so that replay (piece D) does **not** append the same commands again? (D12: one path into matching.)
2. What Java type goes **into** the log, and what comes **out** when reading back? Where does the "which kind of command" tag live?
3. When does an append **return**: after `flush`, or after an fsync? What does that cost per command (Lab 4)? Which failures does your choice survive (2.1)?
4. Opening an existing file at startup: append, never truncate (1.4). What if the file doesn't exist yet?
5. **Format:** field separator, line ending (1.1), how each field is written (3.3), how a missing price is written, and whether every field round-trips exactly.
6. **Recovery:** how do you detect a torn last record (1.8)? What do you do with it, and *when*, relative to the next append (2.7)? What about a bad record in the middle (2.5)?
7. **Failure policy:** if an append throws, the command is not processed. Who tells the caller, and how (1.7)?
8. **Lifetime:** who opens and closes the file (1.6, `AutoCloseable`)?
9. **Testability:** can a test hand your class a temp file (Part 4)?

---

## Part 7: Self-check (5 min)

Answer these in your head first, then check the answers below.

1. Your program opens its file with `Files.newBufferedWriter(path, UTF_8)` every time it starts. What happens to the old content?
2. Your program wrote a line, called `flush()`, and was then killed from Task Manager. Is the line in the file?
3. Same as 2, but the power went out instead. Is the line in the file?
4. You called `fos.getFD().sync()` but forgot `writer.flush()`. Is the line safe?
5. Why is `ordinal()` a bad way to store an enum in a file?
6. What does `"A,5,,".split(",").length` return?
7. `readLine()` gave you `"C"` as the last line. Do you know that line was complete?
8. Why is it safe to throw away a torn *last* record of a write-ahead log?
9. A record in the *middle* of your log doesn't parse. What does that tell you, and what should the program do?
10. An fsync takes 1 ms. What's the most commands per second you can handle with one fsync per command on one thread? How can you go faster without giving up durability?
11. What does `force(true)` save that `force(false)` might not?
12. Give two reasons not to use Java's `Serializable` for the log.
13. Redis runs with `appendfsync everysec`, and the machine loses power. How much data can be lost?
14. Why should the log's file location be a constructor parameter?
15. Why does `System.exit(0)` not save what's in a `BufferedWriter`?

### Answers

1. It's **erased**. No options means `CREATE + TRUNCATE_EXISTING + WRITE`. Use `CREATE, APPEND`.
2. **Yes.** `flush()` handed it to the OS. The process died, but the OS still has the data and writes it to disk.
3. **Not guaranteed.** It may still have been only in the OS cache, in memory, when the power went. Only fsync (`force`/`sync`) guarantees it.
4. **No.** The line is still in Java's buffer, so `sync()` had nothing new to save. Always flush first, then sync.
5. Adding or reordering enum constants changes the numbers, so old files suddenly mean something else. Use `name()` and `valueOf()`.
6. **2**: trailing empty fields are dropped. Use `split(",", -1)`.
7. **No.** `readLine()` returns an unfinished last line exactly like a finished one. Check the last byte (or track positions).
8. A torn record means the crash happened *during* its append. The append never returned, so the command was never processed: no trades, no response. It never became part of the system's history.
9. If every record was fsynced before the next one, it isn't a crash artifact, because a crash can only cut the last record. It means a bug, disk corruption, or manual editing. **Stop with a loud error**; don't skip it or guess. (Without fsync, a power cut *can* damage earlier records: see 2.5.)
10. About **1,000 per second** (1 / 1 ms). **Group commit:** write a batch of commands, fsync once, then process and answer them all.
11. The file's **metadata**: size and timestamps. For a growing file, the size matters.
12. Any two of: the format breaks when classes change; it's a known security risk when reading data; it isn't human-readable or usable from other languages.
13. Up to about **one second** of writes.
14. So tests can use a temp folder (`@TempDir`), and so the location can be configured instead of hard-coded.
15. `System.exit` doesn't flush or close your writers. Only `close()`/`flush()` does, for example through try-with-resources before the program ends.

---

## Part 8: Glossary

| Term | Meaning |
|---|---|
| **Append-only** | data is only ever added at the end; nothing is changed in place |
| **Buffer** | memory that collects data so it can be passed on in bigger pieces |
| **Charset / encoding** | the rule that turns characters into bytes (e.g. UTF-8) |
| **Checksum / CRC** | a small number computed from data; if the data changes, it (almost certainly) changes too |
| **Durability** | saved data survives crashes (the D in ACID) |
| **Event sourcing** | storing the sequence of input events as the source of truth, and rebuilding state by replaying them |
| **flush** | pass buffered data from your program to the operating system |
| **Framing** | how a reader finds where one record ends and the next begins |
| **fsync** | force data from the OS cache onto the disk (Java: `FileChannel.force`, `FileDescriptor.sync`) |
| **Group commit** | one fsync for a batch of records, to save time |
| **Journal** | another word for a write-ahead/input log |
| **Latency / throughput** | how long one operation waits / how many operations finish per second |
| **Metadata** | data about a file (size, timestamps), not its content |
| **Offset** | a record's position number in a log (Kafka's word; like our sequence number) |
| **Page cache** | the OS's memory copy of file data; makes I/O fast |
| **Replay** | re-running logged inputs to rebuild state |
| **Replication** | keeping copies on other machines, so one machine's loss isn't data loss |
| **Round trip** | `read(write(x))` gives back exactly `x` |
| **Serialization** | turning an object into bytes or text (deserialization: the reverse) |
| **Snapshot / checkpoint** | a saved copy of the full state, so replay can start from there |
| **System call** | a request from a program to the OS kernel (slow compared to normal code) |
| **Torn write** | a write that a crash cut partway through |
| **Truncate** | cut a file down to a given size |
| **WAL (write-ahead log)** | log the intent durably before applying it or showing its effects |

---

## Part 9: Context for a new chat session (for the assistant)

*If you're an AI assistant helping with this file, read this first.*

**The project.** ExchangeServer is a Java 17 learning project: a single-instrument limit order book with a price-time-priority matching engine, built to understand production-grade systems in depth. The code is in `src/` (default package, no build file; tests use JUnit 5.11 jars). The design documents are `src/docs/ExchangeServer_Vision_v3.md` and `src/docs/ExchangeServer_Spec_v7.md`; ask the user to attach them if you need more than this summary. Environment: Windows 11, Temurin JDK 17, default charset windows-1252, IntelliJ and VS Code.

**Where the user is.** Roadmap step 3 (sequencer + event log):

- **A. Commands as data: done.** `NewOrderCommand` (an `OrderRequest` + sequence number + timestamp + order id) and `CancelOrderCommand` (sequence number + timestamp + target order id). The engine processes them through `submitNewOrderCommand` / `submitCancelOrderCommand` and reads no clock and no counter.
- **B. Sequencer: done.** It stamps sequence numbers and order ids (both start at 1; a cancel uses a sequence number but no id), and timestamps that never go backwards (if the clock steps back, it reuses the previous timestamp). The engine rejects a new-order id that isn't strictly greater than the last one (`IllegalStateException`).
- **C. Write-ahead input log: being designed now**, as a **plain file** with explicit sync. The user chose this over a database. Requirements: every stamped command is appended *before* the engine processes it; once an append returns, the command survives a process kill and a power cut; the file can be read back into identical commands in the same order; stateless rejects never reach it; one live entry point does stamp, then log, then engine.
- **D. Replay: next.** A fresh engine fed the log ends with the same book and produces the same outputs.

**Design points already discussed in the main chat:**

- Calling the log from inside the engine's `submit…` methods would make replay append everything again (or need a second, replay-only path, which breaks D12). So the log call belongs on the **live path in front of the engine**.
- The file needs a kind-tag per record; in Java, a common supertype is preferred over one class with a type field (the user split a single `Command` class in piece A for exactly this compile-time reason).
- Name: the user suggested `CommandLogger`; `CommandLog` / `CommandJournal` were suggested to avoid confusion with diagnostic loggers. Undecided.
- Recovery: a torn **last** record is safe to cut (it was never processed); a bad record in the **middle** means corruption, so fail loudly.
- Durability (flush vs fsync, and its cost) is the open question this guide prepares the user for.

**How to help (the agreed working style):**

1. **Explain concepts and use neutral examples** (diaries, sensors, shapes), like this guide does.
2. **Don't write or design the user's command log for them.** The user writes all production code; design review happens in the main chat. If the user asks "is my design right?", answer with questions or a concrete failing scenario, then suggest bringing it to the main chat.
3. When the user is stuck after real effort, offer options with trade-offs and a recommendation, but still not their code.
4. Give the **real reason** for every claim, and say plainly when something is uncertain or depends on the OS or disk.

---

## Part 10: Further reading (optional, after the 2 hours)

- **Oracle Java Tutorials, "Basic I/O"** (`java.io` and `java.nio.file`): the official walkthrough.
- **Javadoc:** `java.nio.file.Files`, `java.nio.channels.FileChannel` (especially `force` and `truncate`), `java.nio.file.StandardOpenOption`.
- **Martin Kleppmann, *Designing Data-Intensive Applications*** (O'Reilly): chapter 3 (storage, logs), chapter 4 (encoding and evolution), chapter 7 (transactions, durability), chapter 11 (logs and event streams). This is the most valued book on these topics in industry.
- **Jay Kreps, "The Log: What every software engineer should know about real-time data's unifying abstraction"** (LinkedIn Engineering blog, 2013).
- **Martin Fowler, "The LMAX Architecture"** (2011): the exchange design our Vision follows.
- **Redis documentation, "Redis persistence"**: AOF, `appendfsync`, truncated files.
- **PostgreSQL documentation, "Reliability and the Write-Ahead Log"**.
- **Dan Luu, "Files are hard"** (2015): why durable file I/O is harder than it looks.
- **Joshua Bloch, *Effective Java* (3rd edition):** Item 9 (try-with-resources), Item 35 (ordinals), Item 85 (avoid Java serialization).
