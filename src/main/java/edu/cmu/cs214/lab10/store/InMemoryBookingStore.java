package edu.cmu.cs214.lab10.store;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.domain.BookingStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A {@link BookingStore} that keeps everything in one list in memory.
 *
 * <p>Thread safety: safe for concurrent use. The booking list is private and
 * never escapes; every method on the {@link BookingStore} interface is
 * {@code synchronized} on this store, so calls from different threads run one
 * at a time and each one sees the list in a consistent state. Callers get
 * copies, never the list itself.
 */
public final class InMemoryBookingStore implements BookingStore {

    private final List<Booking> bookings = new ArrayList<>();

    @Override
    public synchronized boolean hasOverlap(String roomId, int startMinute, int endMinute) {
        for (Booking booking : bookings) {
            if (booking.status() == BookingStatus.CONFIRMED
                    && booking.roomId().equals(roomId)
                    && booking.overlaps(startMinute, endMinute)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void insert(Booking booking) {
        bookings.add(booking);
    }

    @Override
    public synchronized boolean insertIfFree(Booking booking) {
        // Check and insert under one hold of the lock: nothing can be added to
        // the list between the scan and the add.
        if (overlapsConfirmed(booking)) {
            return false;
        }
        bookings.add(booking);
        return true;
    }

    @Override
    public synchronized List<Booking> bookingsForRoom(String roomId) {
        List<Booking> forRoom = new ArrayList<>();
        for (Booking booking : bookings) {
            if (booking.roomId().equals(roomId)) {
                forRoom.add(booking);
            }
        }
        // Copied inside the lock, so the caller walks away with a snapshot no
        // other thread can touch.
        return List.copyOf(forRoom);
    }

    @Override
    public synchronized Optional<Booking> cancelAndPromote(String bookingId) {
        int index = indexOf(bookingId);
        if (index < 0) {
            return Optional.empty();
        }
        Booking target = bookings.get(index);
        if (target.status() == BookingStatus.CANCELLED) {
            return Optional.empty();
        }
        bookings.set(index, target.withStatus(BookingStatus.CANCELLED));

        // Cancel and promote happen under the same lock, so no thread ever sees
        // the slot standing empty in between.
        for (int i = 0; i < bookings.size(); i++) {
            Booking candidate = bookings.get(i);
            if (candidate.status() == BookingStatus.WAITLISTED
                    && candidate.collidesWith(target)
                    && !overlapsConfirmed(candidate)) {
                Booking promoted = candidate.withStatus(BookingStatus.CONFIRMED);
                bookings.set(i, promoted);
                return Optional.of(promoted);
            }
        }
        return Optional.empty();
    }

    /**
     * True if any CONFIRMED booking overlaps the candidate's room and slot. A
     * waitlisted booking may straddle more than the one booking being
     * cancelled, so promotion checks the whole slot, not just the vacancy.
     */
    private boolean overlapsConfirmed(Booking candidate) {
        for (Booking other : bookings) {
            if (other.status() == BookingStatus.CONFIRMED
                    && other.roomId().equals(candidate.roomId())
                    && other.overlaps(candidate.startMinute(), candidate.endMinute())) {
                return true;
            }
        }
        return false;
    }

    /** How many bookings the store holds, in every status. Test and dashboard use. */
    public synchronized int size() {
        return bookings.size();
    }

    private int indexOf(String bookingId) {
        for (int i = 0; i < bookings.size(); i++) {
            if (bookings.get(i).id().equals(bookingId)) {
                return i;
            }
        }
        return -1;
    }
}
