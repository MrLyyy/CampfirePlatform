package com.campfiremc.mc.testsupport;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/** A single-threaded virtual clock: only explicit drains and advances execute work. */
public final class ManualScheduler extends AbstractExecutorService implements java.util.concurrent.ScheduledExecutorService {
    private final PriorityQueue<Task<?>> queue = new PriorityQueue<>();
    private long nowNanos;
    private long sequence;
    private boolean shutdown;

    public void runUntilIdle() {
        runThrough(nowNanos);
    }

    public void advanceSeconds(long seconds) {
        if (seconds < 0) throw new IllegalArgumentException("negative advance");
        runThrough(Math.addExact(nowNanos, TimeUnit.SECONDS.toNanos(seconds)));
    }

    private void runThrough(long target) {
        int steps = 0;
        while (!queue.isEmpty() && queue.peek().deadline <= target) {
            if (++steps > 100_000) throw new IllegalStateException("scheduler did not become idle");
            Task<?> task = queue.remove();
            nowNanos = Math.max(nowNanos, task.deadline);
            if (!task.isCancelled()) task.run();
        }
        nowNanos = target;
    }

    @Override
    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return enqueue(new Task<>(java.util.concurrent.Executors.callable(command, null), delay, unit));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return enqueue(new Task<>(callable, delay, unit));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (period <= 0) throw new IllegalArgumentException("period must be positive");
        throw new UnsupportedOperationException("periodic tasks are not used by BackendClient");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (delay <= 0) throw new IllegalArgumentException("delay must be positive");
        throw new UnsupportedOperationException("periodic tasks are not used by BackendClient");
    }

    private <V> Task<V> enqueue(Task<V> task) {
        if (shutdown) throw new RejectedExecutionException("scheduler is shut down");
        queue.add(task);
        return task;
    }

    @Override
    public void shutdown() { shutdown = true; }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(queue);
        queue.clear();
        return pending;
    }

    @Override
    public boolean isShutdown() { return shutdown; }

    @Override
    public boolean isTerminated() { return shutdown && queue.isEmpty(); }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }

    private final class Task<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
        private final long deadline;
        private final long order;

        Task(Callable<V> callable, long delay, TimeUnit unit) {
            super(callable);
            deadline = Math.addExact(nowNanos, Math.max(0, unit.toNanos(delay)));
            order = sequence++;
        }

        @Override
        public boolean isPeriodic() { return false; }

        @Override
        public long getDelay(TimeUnit unit) { return unit.convert(deadline - nowNanos, TimeUnit.NANOSECONDS); }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            if (other instanceof ManualScheduler.Task<?> task) {
                int time = Long.compare(deadline, task.deadline);
                return time != 0 ? time : Long.compare(order, task.order);
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
