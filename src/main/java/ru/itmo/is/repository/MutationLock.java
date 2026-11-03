package ru.itmo.is.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

/**
 * Schema-scoped, transaction-owned lock. Always acquire BEFORE loading domain entities.
 * Durable idempotency claims, when present, must precede this guard (never the reverse).
 * Ordinary writers may run concurrently; reference deletion and merge exclude
 * every writer until commit/rollback. Never upgrade shared to exclusive.
 */
@ApplicationScoped
public class MutationLock {
    @PersistenceContext(unitName = "lab1PU")
    EntityManager em;

    @Transactional(Transactional.TxType.MANDATORY)
    public void shared() {
        em.createNativeQuery("SELECT lab1_lock_writes(FALSE)").getSingleResult();
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public void exclusive() {
        em.createNativeQuery("SELECT lab1_lock_writes(TRUE)").getSingleResult();
    }
}
