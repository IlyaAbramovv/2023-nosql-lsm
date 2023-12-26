package ru.vk.itmo.viktorkorotkikh;

public class LSMDaoOutOfMemoryException extends RuntimeException {
    public LSMDaoOutOfMemoryException() {
        super("LSMDao memory tables is full. Please wait for background flushing to complete.");
    }
}
