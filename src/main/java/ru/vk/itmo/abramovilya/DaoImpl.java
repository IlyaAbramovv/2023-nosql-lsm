package ru.vk.itmo.abramovilya;

import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> map =
            new ConcurrentSkipListMap<>(DaoImpl::compareMemorySegments);
    private final AtomicLong memoryMapSize = new AtomicLong();
    private ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> flushingMap = null;
    private final Storage storage;

    // Я использую семафор вместо Lock потому что я хочу делать lock() в одном потоке, а unlock() - в другом
    // В случае с Lock я получал бы IllegalMonitorStateException
    private final Semaphore flushLock = new Semaphore(1);
    private final long flushThresholdBytes;

    // readLock - пишем значение
    // writeLock - очищаем map
    private final ReadWriteLock mapUpsertExchangeLock = new ReentrantReadWriteLock();
    private final ExecutorService backgroundFlushQueue = Executors.newSingleThreadExecutor();
    private final ExecutorService backgroundCompactQueue = Executors.newSingleThreadExecutor();


    public DaoImpl(Config config) throws IOException {
        flushThresholdBytes = config.flushThresholdBytes();
        storage = new Storage(config);
    }

    Iterator<Entry<MemorySegment>> firstNsstablesIterator(int n) {
        return new DaoIterator(n,
                null,
                null,
                storage,
                Collections.emptyNavigableMap(),
                null);
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return new DaoIterator(storage.getTotalSStables(), from, to, storage, map, flushingMap);
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        var value = map.get(key);
        if (value != null) {
            if (value.value() != null) {
                return value;
            }
            return null;
        }
        if (flushingMap != null) {
            var flushingValue = flushingMap.get(key);
            if (flushingValue != null) {
                if (flushingValue.value() != null) {
                    return flushingValue;
                }
                return null;
            }
        }
        return storage.get(key);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        mapUpsertExchangeLock.readLock().lock();
        try {
            map.put(entry.key(), entry);
            long sizeToAdd = entry.key().byteSize();
            if (entry.value() != null) {
                sizeToAdd += entry.value().byteSize();
            }
            memoryMapSize.addAndGet(sizeToAdd);
        } finally {
            mapUpsertExchangeLock.readLock().unlock();
        }

        if (memoryMapSize.get() <= flushThresholdBytes) {
            return;
        }
        mapUpsertExchangeLock.writeLock().lock();
        try {
            if (memoryMapSize.get() <= flushThresholdBytes) {
                return;
            }
            if (flushLock.tryAcquire()) {
                flushingMap = map;
                renewMap();
                backgroundFlushQueue.execute(this::backgroundFlush);
            } else {
                throw new DaoException.DaoMemoryException(
                        "Upsert happened with no free space and flushing already executing");
            }
        } finally {
            mapUpsertExchangeLock.writeLock().unlock();
        }
    }

    @Override
    public void compact() {
        backgroundCompactQueue.execute(() -> {
            int totalSStables = storage.getTotalSStables();
            var iterator = firstNsstablesIterator(totalSStables);
            if (!iterator.hasNext()) {
                return;
            }
            try {
                storage.compact(iterator, firstNsstablesIterator(totalSStables), totalSStables);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // TODO: Сделать flush неблокирующим
    // Одновременно может работать только один flush
    // Если какой-то flush в процессе исполнение и приходит запрос на еще один flush,
    // мы добавляем эту задачу в очередь и ждем завершения
    //
    // Вызов flush блокирует вызывающий его поток
    @Override
    public void flush() throws IOException {
        try {
            flushLock.acquire();
            try {
                mapUpsertExchangeLock.writeLock().lock();
                try {
                    if (map.isEmpty()) {
                        return;
                    }
                    flushingMap = map;
                    renewMap();
                } finally {
                    mapUpsertExchangeLock.writeLock().unlock();
                }
                writeMapIntoFile(flushingMap);
                storage.incTotalSStablesAmount();
            } finally {
                flushingMap = null;
                flushLock.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

    }

    private void renewMap() {
        memoryMapSize.set(0);
        map = new ConcurrentSkipListMap<>(DaoImpl::compareMemorySegments);
    }

    private void backgroundFlush() {
        try {
            writeMapIntoFile(flushingMap);
            storage.incTotalSStablesAmount();
            flushingMap = null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            flushLock.release();
        }
    }

    private void writeMapIntoFile(NavigableMap<MemorySegment, Entry<MemorySegment>> mapToWrite) throws IOException {
        if (mapToWrite.isEmpty()) {
            return;
        }
        storage.writeMapIntoFile(
                mapByteSizeInFile(mapToWrite),
                indexByteSizeInFile(mapToWrite),
                mapToWrite
        );
    }

    private long mapByteSizeInFile(NavigableMap<MemorySegment, Entry<MemorySegment>> mapToWrite) {
        long size = 0;
        for (var entry : mapToWrite.values()) {
            size += 2 * Long.BYTES;
            size += entry.key().byteSize();
            if (entry.value() != null) {
                size += entry.value().byteSize();
            }
        }
        return size;
    }

    private long indexByteSizeInFile(NavigableMap<MemorySegment, Entry<MemorySegment>> mapToWrite) {
        return (long) mapToWrite.size() * (Integer.BYTES + Long.BYTES);
    }

    @Override
    public void close() throws IOException {
        backgroundFlushQueue.close();
        backgroundCompactQueue.close();
        if (!map.isEmpty()) {
            flush();
        }
        storage.close();
    }

    public static int compareMemorySegments(MemorySegment segment1, MemorySegment segment2) {
        long offset = segment1.mismatch(segment2);
        if (offset == -1) {
            return 0;
        } else if (offset == segment1.byteSize()) {
            return -1;
        } else if (offset == segment2.byteSize()) {
            return 1;
        }
        return Byte.compare(segment1.get(ValueLayout.JAVA_BYTE, offset), segment2.get(ValueLayout.JAVA_BYTE, offset));
    }

    public static int compareMemorySegmentsUsingOffset(MemorySegment segment1,
                                                       MemorySegment segment2,
                                                       long segment2Offset,
                                                       long segment2Size) {
        long mismatch = MemorySegment.mismatch(segment1,
                0,
                segment1.byteSize(),
                segment2,
                segment2Offset,
                segment2Offset + segment2Size);
        if (mismatch == -1) {
            return 0;
        } else if (mismatch == segment1.byteSize()) {
            return -1;
        } else if (mismatch == segment2Size) {
            return 1;
        }
        return Byte.compare(segment1.get(ValueLayout.JAVA_BYTE, mismatch),
                segment2.get(ValueLayout.JAVA_BYTE, segment2Offset + mismatch));
    }
}
