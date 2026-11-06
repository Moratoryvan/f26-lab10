package edu.cmu.cs214.lab10.service;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingResult;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.store.BookingStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The room reservation API that the web front end and the kiosk both call.
 *
 * <p>Times are raw minutes from midnight and ranges are half-open, matching
 * {@link Booking}.
 *
 * <p>Thread safety: safe for concurrent use. The service owns no mutable
 * booking state of its own. The room catalog is built once in the constructor
 * and never written again, and every read and write of booking data goes
 * through {@link BookingStore}. Each store method is atomic on its own, but a
 * sequence of store calls is not, so every invariant that spans a check and a
 * write is enforced by a single store call: {@link #book} holds a slot with
 * {@link BookingStore#insertIfFree} and {@link #cancelBooking} releases one
 * with {@link BookingStore#cancelAndPromote}. The only other field that
 * changes after construction is {@link BookingStats}, and its numbers are
 * documented as approximate.
 */
public final class BookingService {

    private final BookingStore store;
    private final Map<String, Room> catalog;
    private final BookingStats stats = new BookingStats();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * @param store the backing store, shared with any other service instance
     *     pointed at the same data
     * @param rooms the room catalog, copied into this service and fixed for its
     *     lifetime
     */
    public BookingService(BookingStore store, List<Room> rooms) {
        this.store = store;
        Map<String, Room> byId = new HashMap<>();
        for (Room room : rooms) {
            byId.put(room.id(), room);
        }
        // Filled here and never written again. Every later read is a lookup.
        this.catalog = byId;
    }

    /**
     * Holds a room for a slot.
     *
     * <p>The slot has to be free: a room may not have two confirmed bookings
     * covering the same minute. When the slot is taken the attempt is rejected
     * and the caller can queue with {@link #joinWaitlist}.
     *
     * @return CONFIRMED with the new booking, or REJECTED with a reason
     */
    public BookingResult book(String roomId, int startMinute, int endMinute) {
        Room room = catalog.get(roomId);
        if (room == null) {
            return reject(roomId, startMinute, endMinute, "unknown room");
        }
        if (startMinute < 0 || endMinute <= startMinute) {
            return reject(roomId, startMinute, endMinute, "empty or reversed slot");
        }
        Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
        // The overlap check and the insert must be one step. Two separate store
        // calls (hasOverlap, then insert) release the lock in between, and two
        // callers can both pass the check before either inserts (RES-4471).
        if (!store.insertIfFree(booking)) {
            return reject(roomId, startMinute, endMinute, "slot already held");
        }
        stats.recordConfirmed();
        BookingLog.confirmed(booking);
        return BookingResult.confirmed(booking);
    }

    /**
     * Queues the caller behind whoever currently holds the slot.
     *
     * <p>A waitlist entry holds nothing: it blocks no other booking. It
     * becomes CONFIRMED only if a holder cancels; see {@link #cancelBooking}.
     *
     * @return WAITLISTED with the new entry, or REJECTED with a reason
     */
    public BookingResult joinWaitlist(String roomId, int startMinute, int endMinute) {
        Room room = catalog.get(roomId);
        if (room == null) {
            return reject(roomId, startMinute, endMinute, "unknown room");
        }
        if (startMinute < 0 || endMinute <= startMinute) {
            return reject(roomId, startMinute, endMinute, "empty or reversed slot");
        }
        Booking entry = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.WAITLISTED);
        store.insert(entry);
        stats.recordWaitlisted();
        BookingLog.waitlisted(entry);
        return BookingResult.waitlisted(entry);
    }

    /**
     * Releases a booking and hands the slot to the next person in line.
     *
     * <p>Cancelling and promoting are one step in the store, so no other
     * thread can observe the room between the two changes.
     *
     * @return the booking promoted into the freed slot, or empty when the id
     *     was unknown, it was already cancelled, or no waiting entry could be
     *     promoted
     */
    public Optional<Booking> cancelBooking(String bookingId) {
        Optional<Booking> promoted = store.cancelAndPromote(bookingId);
        stats.recordCancelled();
        BookingLog.cancelled(bookingId);
        promoted.ifPresent(BookingLog::promoted);
        return promoted;
    }

    /**
     * Every booking held for a room, in every status.
     *
     * @return a snapshot the caller owns
     */
    public List<Booking> listBookings(String roomId) {
        return store.bookingsForRoom(roomId);
    }

    /** The room catalog, by id, sorted for display. */
    public List<Room> rooms() {
        List<Room> all = new ArrayList<>(catalog.values());
        all.sort(Comparator.comparing(Room::id));
        return List.copyOf(all);
    }

    /** One room, or empty when the catalog has no such id. */
    public Optional<Room> room(String roomId) {
        return Optional.ofNullable(catalog.get(roomId));
    }

    /** The dashboard counters. Approximate; see {@link BookingStats}. */
    public BookingStats stats() {
        return stats;
    }

    private BookingResult reject(String roomId, int startMinute, int endMinute, String reason) {
        stats.recordRejected();
        BookingLog.rejected(roomId, startMinute, endMinute, reason);
        return BookingResult.rejected(reason);
    }

    private String nextId() {
        return "bk-" + sequence.incrementAndGet();
    }
}
