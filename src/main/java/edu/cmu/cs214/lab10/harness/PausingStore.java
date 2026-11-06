package edu.cmu.cs214.lab10.harness;

import edu.cmu.cs214.lab10.domain.Booking;
import edu.cmu.cs214.lab10.store.BookingStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A {@link BookingStore} that wraps another store and parks one chosen call
 * inside it, so a test can drive a specific interleaving instead of hoping for
 * one.
 *
 * <p>Configure the wrapper with a method name and a call index N. Calls to
 * every other method, and the first N-1 calls to the named method, pass
 * straight through to the wrapped store. On call number N the wrapper
 * delegates as usual, then holds the returned value: it counts down the
 * {@code checkDone} latch and waits on the {@code proceed} latch before handing
 * that value back to its caller.
 *
 * <p>The call index counts invocations across all threads, in the order they
 * enter the method. The gate is one shot: once call N has been reached, nothing
 * is ever parked again, so a second thread running the same code path is not
 * held up.
 *
 * <p>Every wait here is bounded (see {@link #TIMEOUT_MILLIS}) and then gives
 * up and continues. No arrangement of threads can hang a test on this class; a
 * test that takes a timeout path still finishes in a few seconds. Nothing in
 * this class sleeps.
 *
 * <p>The full wiring pattern, worked on a deliberately harmless scenario, is in
 * the comments of {@code RaceTest}.
 *
 * <p>This class is not the answer to anything. Which method to name and which
 * call index to aim at is the question you are being asked.
 */
public final class PausingStore implements BookingStore {

    /** How long any wait in this class blocks before giving up and continuing. */
    public static final long TIMEOUT_MILLIS = 2_000L;

    private final BookingStore delegate;
    private final String targetMethod;
    private final int targetCall;

    private final AtomicInteger callsToTarget = new AtomicInteger();
    private final CountDownLatch checkDone = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    /**
     * @param delegate     the real store, which does all the work
     * @param targetMethod the {@link BookingStore} method to park inside, by
     *     name: {@code hasOverlap}, {@code insert}, {@code insertIfFree},
     *     {@code bookingsForRoom}, or {@code cancelAndPromote}
     * @param targetCall   which invocation of that method to park, counting
     *     from 1 across all threads
     */
    public PausingStore(BookingStore delegate, String targetMethod, int targetCall) {
        if (targetCall < 1) {
            throw new IllegalArgumentException("targetCall counts from 1, got " + targetCall);
        }
        this.delegate = delegate;
        this.targetMethod = targetMethod;
        this.targetCall = targetCall;
    }

    /**
     * Blocks until some thread is parked at the gate, or until the timeout.
     *
     * @return true when a thread is parked, false when the gate never fired
     *     within {@link #TIMEOUT_MILLIS} (which happens whenever the code under
     *     test does not make call number N at all)
     */
    public boolean awaitPaused() {
        return await(checkDone);
    }

    /**
     * Lets the parked thread return from its call. Safe to call whether or not
     * anything is parked, and safe to call more than once.
     */
    public void release() {
        proceed.countDown();
    }

    /** How many times the target method has been entered so far. */
    public int targetCallCount() {
        return callsToTarget.get();
    }

    /** Whether the gate fired, meaning call number N was reached. */
    public boolean fired() {
        return checkDone.getCount() == 0;
    }

    @Override
    public boolean hasOverlap(String roomId, int startMinute, int endMinute) {
        return gate("hasOverlap", () -> delegate.hasOverlap(roomId, startMinute, endMinute));
    }

    @Override
    public void insert(Booking booking) {
        gate("insert", () -> {
            delegate.insert(booking);
            return null;
        });
    }

    @Override
    public boolean insertIfFree(Booking booking) {
        return gate("insertIfFree", () -> delegate.insertIfFree(booking));
    }

    @Override
    public List<Booking> bookingsForRoom(String roomId) {
        return gate("bookingsForRoom", () -> delegate.bookingsForRoom(roomId));
    }

    @Override
    public Optional<Booking> cancelAndPromote(String bookingId) {
        return gate("cancelAndPromote", () -> delegate.cancelAndPromote(bookingId));
    }

    /**
     * Runs one delegated call, parking after it when this is the invocation the
     * wrapper was aimed at.
     */
    private <T> T gate(String method, Supplier<T> call) {
        boolean parkHere = method.equals(targetMethod) && callsToTarget.incrementAndGet() == targetCall;
        T answer = call.get();
        if (parkHere) {
            checkDone.countDown();
            await(proceed);
        }
        return answer;
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
