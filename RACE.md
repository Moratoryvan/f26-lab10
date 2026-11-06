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

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.
