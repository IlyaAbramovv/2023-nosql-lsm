package ru.vk.itmo.abramovilya;

import ru.vk.itmo.BaseEntry;
import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {
    private final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> map =
            new ConcurrentSkipListMap<>(DaoImpl::compareMemorySegments);
    private final Path storagePath;
    private final Arena arena = Arena.ofShared();
    private final String sstableBaseName = "storage";
    private final String indexBaseName = "table";
    private final Path metaFilePath;

    private final List<FileChannel> sstableFileChannels = new ArrayList<>();
    private final List<MemorySegment> sstableMappedList = new ArrayList<>();
    private final List<FileChannel> indexFileChannels = new ArrayList<>();
    private final List<MemorySegment> indexMappedList = new ArrayList<>();

    public DaoImpl(Config config) {
        storagePath = config.basePath();

        metaFilePath = storagePath.resolve("meta");
        try {
            if (!Files.exists(metaFilePath)) {
                Files.writeString(metaFilePath, "-1", StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            }

            int totalSSTables = Integer.parseInt(Files.readString(metaFilePath));
            for (int sstableNum = 0; sstableNum < totalSSTables; sstableNum++) {
                Path sstablePath = storagePath.resolve(sstableBaseName + sstableNum);
                Path indexPath = storagePath.resolve(indexBaseName + sstableNum);

                FileChannel sstableFileChannel = FileChannel.open(sstablePath, StandardOpenOption.READ);
                sstableFileChannels.add(sstableFileChannel);
                MemorySegment sstableMapped = sstableFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(sstablePath), arena);
                sstableMappedList.add(sstableMapped);

                FileChannel indexFileChannel = FileChannel.open(indexPath, StandardOpenOption.READ);
                indexFileChannels.add(indexFileChannel);
                MemorySegment indexMapped = indexFileChannel.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(indexPath), arena);
                indexMappedList.add(indexMapped);
            }

        } catch (IOException e) {
//            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return new DaoIterator(getTotalSStables(), from, to, sstableMappedList, indexMappedList, map);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        map.put(entry.key(), entry);
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

        int totalSStables = getTotalSStables();
        for (int sstableNum = totalSStables; sstableNum >= 0; sstableNum--) {
            var foundEntry = seekForValueInFile(key, sstableNum);
            if (foundEntry != null) {
                if (foundEntry.value() != null) {
                    return foundEntry;
                }
                return null;
            }
        }
        return null;
    }

    private Entry<MemorySegment> seekForValueInFile(MemorySegment key, int sstableNum) {
        Path storageFilePath = storagePath.resolve(sstableBaseName + sstableNum);

        if (!Files.exists(storageFilePath)) {
            return null;
        }

        MemorySegment storageMapped = sstableMappedList.get(sstableNum);
        MemorySegment indexMapped = indexMappedList.get(sstableNum);

        FoundSegmentIndexIndexValue found = upperBound(key, storageMapped, indexMapped, indexMapped.byteSize());
        if (compareMemorySegments(found.found(), key) != 0) {
            return null;
        } else {
            return getEntryFromIndexFile(storageMapped, indexMapped, found.index());
        }
    }

    static FoundSegmentIndexIndexValue upperBound(MemorySegment key, MemorySegment storageMapped, MemorySegment indexMapped, long indexSize) {
        long l = -1;
        long r = indexMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, indexSize - 2 * Long.BYTES);

        while (r - l > 1) {
            long m = (r + l) / 2;
            MemorySegment ms = getKeyFromStorageFileAndEntryNum(storageMapped, indexMapped, m);

            if (compareMemorySegments(key, ms) > 0) {
                l = m;
            } else {
                r = m;
            }
        }

        MemorySegment found = getKeyFromStorageFileAndEntryNum(storageMapped, indexMapped, r);
        return new FoundSegmentIndexIndexValue(found, r);
    }

    record FoundSegmentIndexIndexValue(MemorySegment found, long index) {
    }

    private Entry<MemorySegment> getEntryFromIndexFile(MemorySegment storageMapped, MemorySegment indexMapped, long entryNum) {
        long offsetInStorageFile = indexMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 2 * Long.BYTES * entryNum + Long.BYTES);

        long keySize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetInStorageFile);
        offsetInStorageFile += Long.BYTES;
        MemorySegment key = storageMapped.asSlice(offsetInStorageFile, keySize);
        offsetInStorageFile += keySize;

        long valueSize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetInStorageFile);
        offsetInStorageFile += Long.BYTES;
        MemorySegment value;
        if (valueSize == -1) {
            value = null;
        } else {
            value = storageMapped.asSlice(offsetInStorageFile, valueSize);
        }
        return new BaseEntry<>(key, value);
    }

    private static MemorySegment getKeyFromStorageFileAndEntryNum(MemorySegment storageMapped, MemorySegment indexMapped, long entryNum) {
        long offsetInStorageFile = indexMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, 2 * Long.BYTES * entryNum + Long.BYTES);

        long msSize = storageMapped.get(ValueLayout.JAVA_LONG_UNALIGNED, offsetInStorageFile);
        return storageMapped.asSlice(offsetInStorageFile + Long.BYTES, msSize);
    }


    @Override
    public void flush() throws IOException {
        writeMapIntoFile();
        incTotalSStablesAmount();
    }

    private void incTotalSStablesAmount() throws IOException {
        int totalSStables = getTotalSStables();
        Files.writeString(metaFilePath, String.valueOf(totalSStables + 1));
    }

    @Override
    public void close() throws IOException {
        if (arena.scope().isAlive()) {
            arena.close();
        }
        flush();
        for (FileChannel fc : sstableFileChannels) {
            if (fc.isOpen()) fc.close();
        }
        for (FileChannel fc : indexFileChannels) {
            if (fc.isOpen()) fc.close();
        }
    }

    private void writeMapIntoFile() throws IOException {
        if (map.isEmpty()) {
            return;
        }

        int currSStableNum = getTotalSStables();
        Path sstablePath = storagePath.resolve(sstableBaseName + currSStableNum);
        Path indexPath = storagePath.resolve(indexBaseName + currSStableNum);

        long storageWriteOffset = 0;
        long indexWriteOffset = 0;
        try (var storageChannel = FileChannel.open(sstablePath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE);

             var indexChannel = FileChannel.open(indexPath,
                     StandardOpenOption.READ,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.CREATE);

             var storageArena = Arena.ofConfined();
             var indexArena = Arena.ofConfined()) {

            MemorySegment mappedStorage = storageChannel.map(FileChannel.MapMode.READ_WRITE, 0, calcMapByteSizeInFile(), storageArena);
            MemorySegment mappedIndex = indexChannel.map(FileChannel.MapMode.READ_WRITE, 0, calcIndexByteSizeInFile(), indexArena);

            long entryNum = 0;
            for (var entry : map.values()) {
                mappedIndex.set(ValueLayout.JAVA_LONG_UNALIGNED, indexWriteOffset, entryNum);
                indexWriteOffset += Long.BYTES;
                mappedIndex.set(ValueLayout.JAVA_LONG_UNALIGNED, indexWriteOffset, storageWriteOffset);
                indexWriteOffset += Long.BYTES;
                entryNum++;

                writeMemorySegment(entry.key(), mappedStorage, storageWriteOffset);
                storageWriteOffset += Long.BYTES;
                storageWriteOffset += entry.key().byteSize();
                writeMemorySegment(entry.value(), mappedStorage, storageWriteOffset);
                storageWriteOffset += Long.BYTES;
                if (entry.value() != null) storageWriteOffset += entry.value().byteSize();
            }
            mappedStorage.load();
        }
    }

    private int getTotalSStables() {
        return sstableFileChannels.size();
    }

    private long calcIndexByteSizeInFile() {
        return map.size() * Long.BYTES * 2L;
    }

    private long calcMapByteSizeInFile() {
        long size = 0;
        for (var entry : map.values()) {
            size += 2 * Long.BYTES;
            size += entry.key().byteSize();
            if (entry.value() != null) {
                size += entry.value().byteSize();
            }
        }
        return size;
    }

    // Every memorySegment in file has the following structure:
    // 8 bytes - size, <size> bytes - value
    // If memorySegment has the size of -1 byte, then it means its value is DELETED
    private void writeMemorySegment(MemorySegment memorySegment, MemorySegment mapped, long writeOffset) {
        if (memorySegment != null) {
            long msSize = memorySegment.byteSize();
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset, msSize);
            writeOffset += Long.BYTES;
            MemorySegment.copy(memorySegment, 0, mapped, writeOffset, msSize);
        } else {
            mapped.set(ValueLayout.JAVA_LONG_UNALIGNED, writeOffset, -1);
        }
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
        return Byte.compare(
                segment1.get(ValueLayout.JAVA_BYTE, offset),
                segment2.get(ValueLayout.JAVA_BYTE, offset)
        );
    }
}
