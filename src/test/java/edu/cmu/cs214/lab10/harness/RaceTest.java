package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingResult;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests for the booking path, driven through {@link PausingStore}.
 *
 * <p>The wiring pattern this follows is written out in the comments at the
 * bottom of the class.
 */
class RaceTest {

    private static final String WEAN = "WEH-5409";
    private static final String GATES = "GHC-4301";
    private static final List<Room> ROOMS = List.of(
            new Room(WEAN, "Wean 5409", 12),
            new Room(GATES, "Gates 4301", 30));

    /** The 15:00 slot from the 2026-10-21 incident in RES-4471. */
    private static final int START = 900;
    private static final int END = 960;

    /**
     * RES-4471: two customers book the same room and slot at once, and both
     * get CONFIRMED.
     *
     * <p>Milestone 1 aim was {@code hasOverlap}, call 1: customer-1 entered
     * {@code book}, the store answered "no overlap" for an empty room, and the
     * gate parked customer-1 holding that answer before {@code insert}.
     * customer-2 then ran the whole path, also saw no overlap, and inserted.
     * Releasing the gate let customer-1 insert a second CONFIRMED booking.
     *
     * <p>Milestone 3 re-aim: the fix makes {@code book} hold a slot with the
     * single atomic store call {@code insertIfFree}, so it never calls
     * {@code hasOverlap} and the old aim would never fire (the test would go
     * green after a 2 s timeout without exercising any interleaving). The gate
     * is now aimed at {@code insertIfFree}, call 1, which is the same point in
     * the schedule: customer-1 is parked right after the store has decided its
     * booking, with customer-2 arriving while it is parked. The test also
     * asserts that the gate fired, so it cannot silently go inert again.
     *
     * <p>The assertion is on the end state of the real store, not on the order
     * things happened in: the room must end up with exactly one CONFIRMED
     * booking covering the slot.
     */
    @Test
    void twoCustomersBookingTheSameSlotAtOnceCannotBothBeConfirmed() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "insertIfFree", 1);
        BookingService service = new BookingService(gated, ROOMS);

        AtomicReference<BookingResult> firstResult = new AtomicReference<>();
        AtomicReference<BookingResult> secondResult = new AtomicReference<>();

        // customer-1 goes first and is parked inside insertIfFree (call 1), with
        // the store's answer already computed but not yet handed back to book().
        Thread first = new Thread(() -> firstResult.set(service.book(WEAN, START, END)), "customer-1");
        first.start();
        boolean parked = gated.awaitPaused();

        // customer-2 books the same slot while customer-1 is parked. Its
        // insertIfFree is call 2 and is not gated, so it runs to completion.
        Thread second = new Thread(() -> secondResult.set(service.book(WEAN, START, END)), "customer-2");
        second.start();
        second.join(PausingStore.TIMEOUT_MILLIS);

        // Let customer-1 return from the store and finish book().
        gated.release();
        first.join(PausingStore.TIMEOUT_MILLIS);

        assertTrue(parked, "the gate never fired: book() made no insertIfFree call, so this"
                + " test drove no interleaving (calls seen=" + gated.targetCallCount() + ")");
        int confirmed = confirmedCount(real, WEAN, START, END);
        assertEquals(1, confirmed,
                "CONFIRMED bookings for " + WEAN + " [" + START + ", " + END + ")"
                        + " (customer-1=" + outcome(firstResult)
                        + ", customer-2=" + outcome(secondResult)
                        + ", gate parked a thread=" + parked
                        + ", store=" + real.bookingsForRoom(WEAN) + ")");
    }

    /**
     * Milestone 2: the proposed patch adds a second {@code hasOverlap} right
     * before {@code insert}. That re-check and the insert are still two store
     * calls with the lock dropped between them, so the window moves; it does
     * not close.
     *
     * <p>Aim: {@code hasOverlap}, call 2. On the patched code customer-1 makes
     * call 1 (the original check) and call 2 (the new re-check), and is parked
     * after the re-check has answered "no overlap", before {@code insert}.
     * customer-2 then makes calls 3 and 4, both false, and inserts. Releasing
     * the gate lets customer-1 insert on its stale re-check answer.
     *
     * <p>On the unpatched code call 2 belongs to customer-2's only check, which
     * happens after customer-1 has already inserted, so this test is green
     * there. It is aimed at the patch specifically.
     */
    @Test
    void reCheckBeforeInsertStillLetsTwoCustomersConfirmTheSameSlot() throws InterruptedException {
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 2);
        BookingService service = new BookingService(gated, ROOMS);

        AtomicReference<BookingResult> firstResult = new AtomicReference<>();
        AtomicReference<BookingResult> secondResult = new AtomicReference<>();

        // customer-1 passes its first check (call 1) and parks inside its
        // re-check (call 2), holding a fresh "no overlap" it has not acted on.
        Thread first = new Thread(() -> firstResult.set(service.book(WEAN, START, END)), "customer-1");
        first.start();
        boolean parked = gated.awaitPaused();

        // customer-2 books the same slot while customer-1 sits between its
        // re-check and its insert. Its checks are calls 3 and 4, not gated.
        Thread second = new Thread(() -> secondResult.set(service.book(WEAN, START, END)), "customer-2");
        second.start();
        second.join(PausingStore.TIMEOUT_MILLIS);

        gated.release();
        first.join(PausingStore.TIMEOUT_MILLIS);

        int confirmed = confirmedCount(real, WEAN, START, END);
        assertEquals(1, confirmed,
                "CONFIRMED bookings for " + WEAN + " [" + START + ", " + END + ")"
                        + " (customer-1=" + outcome(firstResult)
                        + ", customer-2=" + outcome(secondResult)
                        + ", gate parked a thread=" + parked
                        + ", hasOverlap calls=" + gated.targetCallCount()
                        + ", store=" + real.bookingsForRoom(WEAN) + ")");
    }

    private static int confirmedCount(InMemoryBookingStore store, String roomId, int startMinute, int endMinute) {
        int count = 0;
        for (Booking booking : store.bookingsForRoom(roomId)) {
            if (booking.status() == BookingStatus.CONFIRMED && booking.overlaps(startMinute, endMinute)) {
                count++;
            }
        }
        return count;
    }

    private static String outcome(AtomicReference<BookingResult> result) {
        BookingResult value = result.get();
        return value == null ? "no result" : value.outcome().toString();
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the four on BookingStore: hasOverlap, insert,
    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
    // delegates, then parks the calling thread before returning the answer it
    // already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}
