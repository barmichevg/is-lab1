package ru.itmo.is.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import ru.itmo.is.dto.OrganizationResponseCodec;
import ru.itmo.is.dto.OrganizationResponseDto;
import ru.itmo.is.exception.ApiValidationException;
import ru.itmo.is.repository.IdempotencyRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Durable per-user/per-operation/per-key coordination. The claim, business write
 * and response snapshot commit together. No monitor or result cache is held in this JVM.
 */
@ApplicationScoped
public class OrganizationCreationGuard {
    private static final Set<String> OPERATIONS = Set.of("create", "merge", "add-employee");

    @Inject
    IdempotencyRepository repository;

    @Transactional
    public OrganizationResponseDto createOnce(String owner, String operation, String key,
                                               String payload, Supplier<OrganizationResponseDto> creator) {
        if (key == null) return creator.get(); // No database claim, and no global/per-key JVM lock.
        if (owner == null || !owner.matches("[A-Za-z0-9_.-]{1,64}") || operation == null || !OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Invalid idempotency scope");
        }
        if (key.isBlank() || key.length() > 256) {
            throw new ApiValidationException(List.of("idempotency key: must contain 1 to 256 characters"));
        }
        String fingerprint = fingerprint(payload);
        if (!repository.claim(owner, operation, key, fingerprint)) {
            // INSERT ON CONFLICT waits only for this exact key. Under READ COMMITTED
            // the following query sees the first caller's committed result, including
            // after a restart or from another Payara process.
            IdempotencyRepository.StoredResult previous = repository.find(owner, operation, key);
            if (!fingerprint.equals(previous.fingerprint())) {
                throw new ApiValidationException(List.of("idempotency key: already used with another request"));
            }
            if (previous.response() == null) throw new IllegalStateException("Incomplete committed idempotency result");
            return OrganizationResponseCodec.decode(previous.response());
        }
        // CDI service @Transactional(REQUIRED) joins THIS transaction. A failure in
        // execution, encoding, persistence or commit rolls back both claim and mutation.
        OrganizationResponseDto value = creator.get();
        repository.complete(owner, operation, key, OrganizationResponseCodec.encode(value));
        return value;
    }

    static String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
