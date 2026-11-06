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

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

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
