package io.github.bootui.autoconfigure.database;

import io.github.bootui.core.BootUiDtos.SqlRequestDto;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fixed-size, thread-safe ring buffer that keeps the most recent SQL requests
 * executed against the application's JDBC {@code DataSource}s.
 *
 * <p>The recorder is process-local and intended only for the local-first
 * developer console &mdash; it never leaves the JVM.</p>
 */
public class SqlRecorder {

    private final int capacity;
    private final SqlRequestDto[] buffer;
    private int writeIndex;
    private int size;
    private final AtomicLong total = new AtomicLong();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SqlRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new SqlRequestDto[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public long totalRecorded() {
        return total.get();
    }

    public void record(SqlRequestDto request) {
        if (request == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            buffer[writeIndex] = request;
            writeIndex = (writeIndex + 1) % capacity;
            if (size < capacity) {
                size++;
            }
            total.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the recent SQL requests, ordered from newest to oldest.
     */
    public List<SqlRequestDto> snapshot() {
        lock.readLock().lock();
        try {
            List<SqlRequestDto> out = new ArrayList<>(size);
            int idx = (writeIndex - 1 + capacity) % capacity;
            for (int i = 0; i < size; i++) {
                out.add(buffer[idx]);
                idx = (idx - 1 + capacity) % capacity;
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = null;
            }
            writeIndex = 0;
            size = 0;
        } finally {
            lock.writeLock().unlock();
        }
    }
}
