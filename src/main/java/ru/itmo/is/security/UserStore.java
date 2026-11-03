package ru.itmo.is.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Small persistent account store for one Payara process. */
public final class UserStore {
    private final Path path;
    private final Properties users = new Properties();
    public UserStore(Path path) throws IOException {
        this.path = path.toAbsolutePath();
        if (Files.exists(this.path)) try (Reader r = Files.newBufferedReader(this.path)) { users.load(r); }
    }
    public synchronized String hash(String username) { return users.getProperty(username); }
    public synchronized boolean register(String username, String hash) throws IOException {
        if (users.containsKey(username)) return false;
        Properties updated = new Properties(); updated.putAll(users); updated.setProperty(username, hash);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".accounts-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                updated.store(writer, "Lab 1 accounts: PBKDF2 hashes only");
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            users.putAll(updated);
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }
}
