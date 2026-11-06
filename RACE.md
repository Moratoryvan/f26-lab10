# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything the review turned up that touches shared state, with a one-line
verdict for each. Line numbers refer to the files as of the initial commit.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book`, lines 70 and 74: `store.hasOverlap(...)` then `store.insert(...)` | **The bug.** Check-then-act over two separately synchronized store calls; the store lock is released between the check and the insert, so a second booker can pass the same check in the gap. |
| 2 | `InMemoryBookingStore.bookings` list and its four `synchronized` methods | Correct for what the interface promises: each single call is atomic, and `indexOf` / `overlapsConfirmed` are private and only run under the lock. It never promised that two calls together are atomic. |
| 3 | `InMemoryBookingStore.cancelAndPromote` | Cancel and promote happen inside one synchronized method, so there is no gap between them. Not the cause. |
| 4 | `BookingService.catalog` (`HashMap`) | Written only in the constructor, read-only afterwards, published through a `final` field. Safe. |
| 5 | `BookingService.sequence` (`AtomicLong`) | `incrementAndGet` is atomic, so ids are unique; the ticket confirms each pair had different ids. Not the cause. |
| 6 | `BookingStats` counters (`long` fields incremented with `++`, no lock) | A real lost-update race, but documented as approximate and nothing decides on the numbers; it cannot put a second CONFIRMED row in the store. Not the ticket. |
| 7 | `BookingLog.write` via `System.out.println` | `PrintStream` locks per call, so lines interleave whole but never corrupt. Not the cause; it is why the two confirmed lines sit 1 ms apart with nothing between. |
| 8 | `BookingService.joinWaitlist`: `store.insert` with no check first | A single store call, and WAITLISTED entries block nothing, so no invariant over them can break. Not the cause. |
| 9 | `BookingService.cancelBooking` | One store call, then stats and log. Nothing here races against the one-CONFIRMED invariant. Not the cause. |
| 10 | `Booking`, `Room`, `BookingResult` records | Immutable; a status change creates a new value. No shared mutable state. |

**Which one explains the ticket, and how you know.** The invariant the ticket
names is "a room has at most one CONFIRMED booking covering any given minute."
The only code that enforces it is `book()`, and it does so in two steps: ask the
store `hasOverlap` (line 70), then `insert` a CONFIRMED booking (line 74). Each
step is atomic by itself, but the store's lock is released between them, so the
answer "no overlap" can be stale by the time `insert` runs. Two threads that
both reach line 70 before either reaches line 74 both get `false`, both insert a
CONFIRMED booking, and both log `booking.confirmed`. That is exactly the log
excerpt: two confirmations 1 ms apart, no `booking.rejected` between them, no
exception, two different ids (the `AtomicLong` is working fine). Suspect 1 is
the only one whose failure mode is "two CONFIRMED rows overlap in the store":
suspects 2 and 3 hold the lock for the whole operation, 4 and 10 never change,
and 5 through 9 cannot add a second CONFIRMED row at all. The service javadoc's
inference, "since the store is thread safe, the operations built on top of it
are too," is the mistake: atomic parts do not compose into an atomic whole.

The stress runner agrees. Three runs of 2000 rounds ended with 23, 14, and 14
rounds in a bad state, every one of them "2 confirmed bookings for WEH-5409
540..600." It also explains why operations could not reproduce it by hand: the
window is the few hundred nanoseconds between the two store calls, which two
people on a count of three never hit, while "the first minute after a slot
opens" has many people hammering the same slot at once.

### Predicted interleaving (write this before you run your race test)

Both threads call `book("WEH-5409", 900, 960)`, the 15:00 slot from the
2026-10-21 incident, against an empty store. Thread A is the one the gate will
park; thread B runs unhindered.

| Step | Thread A (customer-1) | Thread B (customer-2) |
|---|---|---|
| t1 | Enters `book`; room and range checks pass. Calls `store.hasOverlap("WEH-5409", 900, 960)`. Under the store lock the list is empty, so it computes **false** and releases the lock. A holds that answer but has not reached line 74. | |
| t2 | | Enters `book`; room and range checks pass. Calls `store.hasOverlap(...)`. A has inserted nothing, so the store still says **false**. |
| t3 | | `nextId()` gives `bk-1`. `store.insert(bk-1 CONFIRMED)`, `stats.recordConfirmed()`, logs `booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960`, returns CONFIRMED. |
| t4 | Acts on the stale false from t1: `nextId()` gives `bk-2`, `store.insert(bk-2 CONFIRMED)`, logs `booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960`, returns CONFIRMED. | |
| | **End state:** the store holds two CONFIRMED bookings for WEH-5409 [900, 960), `bk-1` and `bk-2`. Both callers got CONFIRMED. No `booking.rejected` line was written. | |

Note on ids: `nextId()` runs after `hasOverlap` returns (line 73), so the thread
that is parked gets the later id even though it entered `book` first.

### Your race test

**How you aimed `PausingStore`, and the result.**

Aim: `new PausingStore(real, "hasOverlap", 1)`, driven by
`RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed`.

Why that pair pins the predicted schedule. `PausingStore` delegates first and
parks *after* the wrapped call has computed its answer, so `hasOverlap` call 1
freezes customer-1 at the exact point in the prediction's t1: the store has
already said "no overlap", the lock is released, and `book` has not yet reached
line 74 to `insert`. While customer-1 is parked, customer-2 runs `book` end to
end; its `hasOverlap` is call 2, which the one-shot gate ignores, and it sees an
empty room because customer-1 inserted nothing (t2, t3). Releasing the gate
hands customer-1 its stale `false` and it inserts too (t4). The other aims do
not pin this schedule: `insert` 1 would park customer-1 after its booking is
already stored, so customer-2 would be correctly rejected, and `hasOverlap` 2
would park whichever thread checks second, after the first one may already have
inserted. The assertion counts CONFIRMED bookings overlapping [900, 960) in the
real store and expects 1, so it is about the end state, not the schedule.

Result: the test fails on every run. The prediction matched, including the id
order (customer-2 got `bk-1`, the parked customer-1 got `bk-2`) and the two
`booking.confirmed` lines 1 ms apart with no `booking.rejected` between them,
the same shape as the ticket's log excerpt.

Run 1 (`mvn -B test -Dtest=RaceTest`):

```
2026-11-06T03:57:19.878Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T03:57:19.879Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.026 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed -- Time elapsed: 0.018 s <<< FAILURE!
org.opentest4j.AssertionFailedError: CONFIRMED bookings for WEH-5409 [900, 960) (customer-1=CONFIRMED, customer-2=CONFIRMED, gate parked a thread=true, store=[Booking[id=bk-1, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED], Booking[id=bk-2, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED]]) ==> expected: <1> but was: <2>
	at edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed(RaceTest.java:77)
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Run 2 (`mvn -B test -Dtest=RaceTest`):

```
2026-11-06T03:57:20.992Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T03:57:20.993Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.028 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed -- Time elapsed: 0.019 s <<< FAILURE!
org.opentest4j.AssertionFailedError: CONFIRMED bookings for WEH-5409 [900, 960) (customer-1=CONFIRMED, customer-2=CONFIRMED, gate parked a thread=true, store=[Booking[id=bk-1, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED], Booking[id=bk-2, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED]]) ==> expected: <1> but was: <2>
	at edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed(RaceTest.java:77)
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

---

## Milestone 2: the patch a teammate sent

The patch adds one hunk to `BookingService.book`: a second
`store.hasOverlap(...)` immediately before `store.insert(booking)`, with the
comment "with the freshest possible answer in hand there is no window left."
Applied with `git apply proposed-fix.diff`; `git diff --stat` showed only
`BookingService.java` changed (7 insertions).

**Your milestone-1 test on the patched code.** Green. With the gate still aimed
at `hasOverlap` call 1, customer-1 parks after its first check; customer-2 runs
both of its checks (calls 2 and 3) and inserts; on release customer-1's new
re-check (call 4) sees that insert and rejects. The patch defeats this one
schedule, and that is all this green shows.

```
2026-11-06T04:07:48.751Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T04:07:48.752Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.024 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**The re-aimed run.** `new PausingStore(real, "hasOverlap", 2)`, driven by
`RaceTest.reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot` (the
milestone-1 test is kept alongside it). On the patched code calls 1 and 2 are
both customer-1's: the original check, then the new re-check. Parking call 2
freezes customer-1 with the "freshest possible answer" already computed but
not yet acted on, in the gap the patch claims is gone. customer-2 then makes
calls 3 and 4, sees nothing, and inserts; on release customer-1 inserts too.
This aim tests the patch rather than sidestepping it because the thread is
parked inside the patch's own new call, after that call has returned its
answer: the re-check and the insert are still two store calls with the lock
released between them, so moving the check closer to the write only shrinks
the window. The assertion is unchanged, one CONFIRMED booking in the real store.
Both runs fail, and the failure message records `hasOverlap calls=4`, which is
the patched code's call count (two per customer).

Run 1 (patched, `mvn -B test -Dtest='RaceTest#reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot'`):

```
2026-11-06T04:07:49.987Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T04:07:49.988Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.028 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot -- Time elapsed: 0.019 s <<< FAILURE!
org.opentest4j.AssertionFailedError: CONFIRMED bookings for WEH-5409 [900, 960) (customer-1=CONFIRMED, customer-2=CONFIRMED, gate parked a thread=true, hasOverlap calls=4, store=[Booking[id=bk-2, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED], Booking[id=bk-1, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED]]) ==> expected: <1> but was: <2>
	at edu.cmu.cs214.lab10.harness.RaceTest.reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot(RaceTest.java:126)
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Run 2 (patched, same command):

```
2026-11-06T04:07:51.106Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T04:07:51.107Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.027 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot -- Time elapsed: 0.017 s <<< FAILURE!
org.opentest4j.AssertionFailedError: CONFIRMED bookings for WEH-5409 [900, 960) (customer-1=CONFIRMED, customer-2=CONFIRMED, gate parked a thread=true, hasOverlap calls=4, store=[Booking[id=bk-2, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED], Booking[id=bk-1, roomId=WEH-5409, startMinute=900, endMinute=960, status=CONFIRMED]]) ==> expected: <1> but was: <2>
	at edu.cmu.cs214.lab10.harness.RaceTest.reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot(RaceTest.java:126)
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

Control: the same re-aimed test on the unpatched code is green. There call 2 is
customer-2's only check, made after customer-1 has already inserted, so the
gate parks customer-2 on a `true` answer and it is rejected (the 4 s elapsed
time is the two bounded waits timing out). So the red runs above come from the
patch's call sequence, not from a test that fails regardless.

```
2026-11-06T04:08:11.548Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T04:08:15.558Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.049 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Your verdict.** Reject. The patch does not close the window; it narrows it
and moves it one call later. The invariant needs the overlap check and the
insert to be one atomic step, and the patch still performs them as two
separately locked store calls. The milestone-1 green on the patched code shows
only that the patch beats the one schedule that test pins; it does not show the
invariant holds, and the re-aimed test shows it does not. The teammate's
evidence, "suite green, stress runner clean a few times", is the same evidence
the ticket already discounted: the suite was green before the first report, and
the stress runner's clean run prints "that is not evidence of anything." A
narrower race is a worse bug, not a fixed one, because it reproduces even less
often in the field and would re-open RES-4471 with a harder reproduction. The
patch was undone with `git apply -R proposed-fix.diff` and is not committed.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Option 2, an atomic check-and-insert in
the store. The new operation is `BookingStore.insertIfFree(Booking)`, declared
as a plain interface method (not a default), implemented in
`InMemoryBookingStore` as one `synchronized` method, and passed through in
`PausingStore` via its `gate` so it can be aimed at. The lock is the store's
intrinsic monitor, the same one every other store method already takes. The
two operations it now covers are the CONFIRMED-overlap scan over the room
(formerly `hasOverlap`) and the `bookings.add` (formerly `insert`), which now
happen under one hold of that lock. `BookingService.book` makes that one call
and rejects when it returns false; it no longer calls `hasOverlap` or `insert`
at all. The service javadoc's claim that a thread-safe store makes the
operations on top of it thread-safe is rewritten to say the opposite. As the
handout predicts, adding the interface method broke the build until
`PausingStore` implemented it:

```
[ERROR] .../harness/PausingStore.java:[40,14] edu.cmu.cs214.lab10.harness.PausingStore is not abstract and does not override abstract method insertIfFree(edu.cmu.cs214.lab10.domain.Booking) in edu.cmu.cs214.lab10.store.BookingStore
```

Why this option over the other two: the invariant is a property of the data,
so enforcing it in the store protects every caller, present and future, and
keeps the lock scope to exactly the check plus the write. A `synchronized` or
`ReentrantLock` around the service's booking path would also work but would
hold a second lock around the store's lock, serialize the catalog lookup, id
generation, stats, and logging that do not need it, and protect only this one
service's `book`, not another service instance pointed at the same store.

The diff of `src/main`:

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
index 5e6b639..9c65544 100644
--- a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
@@ -53,8 +53,8 @@ public final class PausingStore implements BookingStore {
     /**
      * @param delegate     the real store, which does all the work
      * @param targetMethod the {@link BookingStore} method to park inside, by
-     *     name: {@code hasOverlap}, {@code insert}, {@code bookingsForRoom}, or
-     *     {@code cancelAndPromote}
+     *     name: {@code hasOverlap}, {@code insert}, {@code insertIfFree},
+     *     {@code bookingsForRoom}, or {@code cancelAndPromote}
      * @param targetCall   which invocation of that method to park, counting
      *     from 1 across all threads
      */
@@ -109,6 +109,11 @@ public final class PausingStore implements BookingStore {
         });
     }
 
+    @Override
+    public boolean insertIfFree(Booking booking) {
+        return gate("insertIfFree", () -> delegate.insertIfFree(booking));
+    }
+
     @Override
     public List<Booking> bookingsForRoom(String roomId) {
         return gate("bookingsForRoom", () -> delegate.bookingsForRoom(roomId));
diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
index 514c3e1..4aa5a1e 100644
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ -22,10 +22,13 @@ import java.util.concurrent.atomic.AtomicLong;
  * <p>Thread safety: safe for concurrent use. The service owns no mutable
  * booking state of its own. The room catalog is built once in the constructor
  * and never written again, and every read and write of booking data goes
- * through {@link BookingStore}, whose methods are each synchronized. Since the
- * store is thread safe, the operations built on top of it are too. The only
- * other field that changes after construction is {@link BookingStats}, and its
- * numbers are documented as approximate.
+ * through {@link BookingStore}. Each store method is atomic on its own, but a
+ * sequence of store calls is not, so every invariant that spans a check and a
+ * write is enforced by a single store call: {@link #book} holds a slot with
+ * {@link BookingStore#insertIfFree} and {@link #cancelBooking} releases one
+ * with {@link BookingStore#cancelAndPromote}. The only other field that
+ * changes after construction is {@link BookingStats}, and its numbers are
+ * documented as approximate.
  */
 public final class BookingService {
 
@@ -67,11 +70,13 @@ public final class BookingService {
         if (startMinute < 0 || endMinute <= startMinute) {
             return reject(roomId, startMinute, endMinute, "empty or reversed slot");
         }
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
+        // The overlap check and the insert must be one step. Two separate store
+        // calls (hasOverlap, then insert) release the lock in between, and two
+        // callers can both pass the check before either inserts (RES-4471).
+        if (!store.insertIfFree(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        store.insert(booking);
         stats.recordConfirmed();
         BookingLog.confirmed(booking);
         return BookingResult.confirmed(booking);
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
index 3ae3544..cd0a81a 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
@@ -28,6 +28,22 @@ public interface BookingStore {
     /** Stores the booking. Ids are assumed unique; the store does not check. */
     void insert(Booking booking);
 
+    /**
+     * Stores the booking only if its room has no CONFIRMED booking touching its
+     * half-open minute range, as one indivisible step.
+     *
+     * <p>The overlap check and the insert happen under the same lock, so no
+     * other thread can insert into the room between them. This is the only
+     * way to hold a slot that keeps the one-CONFIRMED-per-minute invariant
+     * under concurrent callers; {@link #hasOverlap} followed by
+     * {@link #insert} does not, because the lock is released in between.
+     *
+     * @param booking the booking to store, normally CONFIRMED
+     * @return true when the booking was stored, false when a CONFIRMED booking
+     *     already overlapped it and nothing was stored
+     */
+    boolean insertIfFree(Booking booking);
+
     /**
      * Every booking held for the room, in insertion order, in every status.
      *
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
index f7fd85f..8b9cfb8 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
@@ -36,6 +36,17 @@ public final class InMemoryBookingStore implements BookingStore {
         bookings.add(booking);
     }
 
+    @Override
+    public synchronized boolean insertIfFree(Booking booking) {
+        // Check and insert under one hold of the lock: nothing can be added to
+        // the list between the scan and the add.
+        if (overlapsConfirmed(booking)) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }
+
     @Override
     public synchronized List<Booking> bookingsForRoom(String roomId) {
         List<Booking> forRoom = new ArrayList<>();
```

**Milestone-1 test, adjusted, and why.** The fix changed what the test can
observe: `book` never calls `hasOverlap` any more, so the old aim
(`hasOverlap`, 1) can never fire. Left as it was, the test would turn green by
accident: `awaitPaused` would time out after 2 s, both customers would run
with nothing parked, and the assertion would pass without having driven any
interleaving. Two changes, both in `RaceTest`:

1. Re-aimed to `new PausingStore(real, "insertIfFree", 1)`. That is the same
   point in the schedule as before, customer-1 parked right after the store has
   decided its booking and customer-2 arriving while it is parked; only the
   method that marks that point has changed.
2. Added `assertTrue(parked, ...)` so the test fails if the gate never fires.
   A race test that silently stops racing is worse than no test.

Proof that the second change is needed: with the old aim flipped back on the
fixed code, the test now fails on that assertion, with 0 calls seen:

```
2026-11-06T04:17:58.449Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T04:18:00.456Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.045 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed -- Time elapsed: 2.034 s <<< FAILURE!
org.opentest4j.AssertionFailedError: the gate never fired: book() made no insertIfFree call, so this test drove no interleaving (calls seen=0) ==> expected: <true> but was: <false>
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

The re-aimed milestone-1 test, two runs on the fixed code, both green in about
25 ms (no timeout was taken, so the gate fired):

```
2026-11-06T04:18:01.759Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
2026-11-06T04:18:01.761Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.025 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

```
2026-11-06T04:18:02.855Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
2026-11-06T04:18:02.859Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.026 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The full suite:

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.033 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

The stress runner, three runs of 2000 rounds (not evidence on its own, but
consistent; before the fix the same three runs gave 23, 14, and 14 bad rounds):

```
rounds: 2000
rounds ending in a bad state: 0
clean run. That is not evidence of anything; run it again.
rounds: 2000
rounds ending in a bad state: 0
clean run. That is not evidence of anything; run it again.
rounds: 2000
rounds ending in a bad state: 0
clean run. That is not evidence of anything; run it again.
```

**Is your milestone-1 test still testing anything?** Yes. It now exercises the
schedule the fix must survive: customer-1 is parked inside `insertIfFree` call
1 after the store has atomically scanned the room and added its booking, and
customer-2 calls `insertIfFree` while customer-1 is still parked. Because the
store's work for call 1 is already complete when the thread is parked (the
gate parks after delegating, and the store released its lock on return),
customer-2's call sees the CONFIRMED booking and returns false, so customer-2
is rejected. On release customer-1 finishes with CONFIRMED. The end state is
one CONFIRMED booking, which is what the assertion checks. `PausingStore` did
pause a call during the green run, and I know it three ways: the test asserts
`parked == true` and passed; the run took 25 ms rather than the 2 s a timed-out
`awaitPaused` costs (the old-aim run above shows that 2.0 s); and the log order
is `booking.rejected` first, then `booking.confirmed`, which can only happen
when customer-1 is held between its store call and its log line while
customer-2 runs to completion.

**What runs one at a time now that ran in parallel before.** Every `book`
call's check-and-insert, for every room, now queues behind every other call
into the store on the store's monitor: other `book` calls (same room or not),
`joinWaitlist`'s `insert`, `cancelBooking`'s `cancelAndPromote`,
`listBookings`'s `bookingsForRoom`, and `size()`. Before the fix the scan and
the add each took that monitor separately, so two `book` calls could
interleave between them (which was the bug), and a `bookingsForRoom` or
`cancelAndPromote` could also slip in between a booker's check and its insert.
Concretely: two customers booking WEH-5409 and GHC-4301 at the same instant
used to have their scans and adds interleave freely; now one customer's
scan-plus-add completes before the other's begins. The rest of `book` (catalog
lookup, range validation, `nextId`, stats, the log line) still runs in
parallel; only the critical section grew from one list operation to a scan
plus an add, which on an in-memory list is microseconds.

**What you left unfixed.**

- `BookingStats` lost-update counters (suspect 6): documented as approximate,
  nothing decides on them, and the README says to count the store for exact
  numbers. Out of scope for this ticket.
- `hasOverlap` and `insert` remain on `BookingStore`. Other callers may still
  compose them unsafely; the interface javadoc on `insertIfFree` now says why
  that pair is not enough. Removing them is an API change beyond the ticket.
- A rejected `book` now consumes a sequence id (`nextId` runs before the store
  call, since the booking must exist to be offered to the store). Ids were
  never promised to be gap-free, and the ticket shows they are not shown to
  customers as a count.
- The milestone-2 test (`hasOverlap`, 2) is kept as the record of why the patch
  was rejected. On the shipped code `book` never calls `hasOverlap`, so its
  gate never fires, it takes the 2 s `awaitPaused` timeout, and it passes on
  end state only. It is inert on this code by design; it would fire again if
  someone reintroduced a `hasOverlap` check in the booking path.
- Not a code issue: the Documents folder's file sync dropped duplicate
  `edu/cmu 2/` directories into `target/` during one build, which made the
  surefire fork fail with "wrong name". `mvn clean` fixed it; `target/` is
  ignored by git.
