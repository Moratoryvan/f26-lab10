package edu.cmu.cs214.lab10.store;

import edu.cmu.cs214.lab10.domain.Booking;
import java.util.List;
import java.util.Optional;

/**
 * Storage for bookings.
 *
 * <p>Thread safety: every implementation must make each method below atomic
 * with respect to the others. A caller may invoke any single method from any
 * thread at any time and will observe a consistent snapshot of the stored
 * bookings for the duration of that call.
 */
public interface BookingStore {

    /**
     * Whether the room already has a CONFIRMED booking touching the given
     * half-open minute range. WAITLISTED and CANCELLED bookings do not count.
     *
     * @param roomId      the room to look at
     * @param startMinute first minute of the range, inclusive
     * @param endMinute   first minute after the range, exclusive
     * @return true when a confirmed booking intersects the range
     */
    boolean hasOverlap(String roomId, int startMinute, int endMinute);

    /** Stores the booking. Ids are assumed unique; the store does not check. */
    void insert(Booking booking);

    /**
     * Stores the booking only if its room has no CONFIRMED booking touching its
     * half-open minute range, as one indivisible step.
     *
     * <p>The overlap check and the insert happen under the same lock, so no
     * other thread can insert into the room between them. This is the only
     * way to hold a slot that keeps the one-CONFIRMED-per-minute invariant
     * under concurrent callers; {@link #hasOverlap} followed by
     * {@link #insert} does not, because the lock is released in between.
     *
     * @param booking the booking to store, normally CONFIRMED
     * @return true when the booking was stored, false when a CONFIRMED booking
     *     already overlapped it and nothing was stored
     */
    boolean insertIfFree(Booking booking);

    /**
     * Every booking held for the room, in insertion order, in every status.
     *
     * @return a snapshot the caller owns; later writes to the store do not
     *     change it, and changes to it do not reach the store
     */
    List<Booking> bookingsForRoom(String roomId);

    /**
     * Cancels a booking and hands its slot to the next person in line, as one
     * indivisible step.
     *
     * <p>The booking moves to CANCELLED. The earliest WAITLISTED booking for
     * the same room that overlaps the cancelled range and whose own range is
     * free of other CONFIRMED bookings then moves to CONFIRMED. Cancelling a
     * WAITLISTED entry is allowed and may promote another waiting entry. No
     * other thread can observe the room between the two changes.
     *
     * @param bookingId the booking to cancel
     * @return the booking promoted into the freed slot, or empty when the id
     *     was unknown, the booking was already cancelled, or no waiting entry
     *     could be promoted
     */
    Optional<Booking> cancelAndPromote(String bookingId);
}
