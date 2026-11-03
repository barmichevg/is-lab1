package ru.itmo.is.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class IdempotencyRepository {
    @PersistenceContext(unitName = "lab1PU")
    EntityManager em;

    public boolean claim(String owner, String operation, String key, String fingerprint) {
        return em.createNativeQuery("""
                INSERT INTO api_idempotency(owner_name, operation, request_key, request_sha256)
                VALUES (?1, ?2, ?3, ?4)
                ON CONFLICT (owner_name, operation, request_key) DO NOTHING
                """)
                .setParameter(1, owner).setParameter(2, operation)
                .setParameter(3, key).setParameter(4, fingerprint).executeUpdate() == 1;
    }

    public StoredResult find(String owner, String operation, String key) {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT request_sha256, CAST(response_json AS TEXT)
                FROM api_idempotency
                WHERE owner_name = ?1 AND operation = ?2 AND request_key = ?3
                FOR UPDATE
                """)
                .setParameter(1, owner).setParameter(2, operation).setParameter(3, key)
                .getSingleResult();
        return new StoredResult((String) row[0], (String) row[1]);
    }

    public void complete(String owner, String operation, String key, String response) {
        int changed = em.createNativeQuery("""
                UPDATE api_idempotency SET response_json = CAST(?4 AS JSONB), completed_at = CURRENT_TIMESTAMP
                WHERE owner_name = ?1 AND operation = ?2 AND request_key = ?3 AND response_json IS NULL
                """)
                .setParameter(1, owner).setParameter(2, operation).setParameter(3, key)
                .setParameter(4, response).executeUpdate();
        if (changed != 1) throw new IllegalStateException("Idempotency result was not completed");
    }

    public record StoredResult(String fingerprint, String response) {}
}
