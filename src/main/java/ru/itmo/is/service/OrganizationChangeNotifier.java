package ru.itmo.is.service;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import ru.itmo.is.websocket.OrganizationWebSocket;

import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class OrganizationChangeNotifier {
    private static final Logger LOG = Logger.getLogger(OrganizationChangeNotifier.class.getName());

    @Resource(lookup = "java:comp/TransactionSynchronizationRegistry")
    TransactionSynchronizationRegistry transactionRegistry;

    public void afterCommit(String type, Integer id) {
        try {
            int status = transactionRegistry.getTransactionStatus();
            if (status == Status.STATUS_ACTIVE) {
                transactionRegistry.registerInterposedSynchronization(new Synchronization() {
                    @Override
                    public void beforeCompletion() {
                    }

                    @Override
                    public void afterCompletion(int completionStatus) {
                        if (completionStatus == Status.STATUS_COMMITTED) {
                            broadcast(type, id);
                        }
                    }
                });
                return;
            }
            // Only a positively identified non-transactional call may send immediately.
            // Unknown, completing and rolled-back transactions must never publish changes.
            if (status == Status.STATUS_NO_TRANSACTION) {
                broadcast(type, id);
            }
        } catch (RuntimeException failure) {
            // A registration failure does not mean the surrounding transaction committed.
            LOG.log(Level.WARNING, "Change notification suppressed: transaction completion is not confirmed", failure);
        }
    }

    void broadcast(String type, Integer id) {
        OrganizationWebSocket.broadcast(type, id);
    }
}
