package ru.itmo.is.service;

import jakarta.annotation.Resource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import ru.itmo.is.websocket.OrganizationWebSocket;

@ApplicationScoped
public class OrganizationChangeNotifier {
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
                            OrganizationWebSocket.broadcast(type, id);
                        }
                    }
                });
                return;
            }
            if (status == Status.STATUS_MARKED_ROLLBACK || status == Status.STATUS_ROLLING_BACK) {
                return;
            }
        } catch (RuntimeException ignored) {
        }

        OrganizationWebSocket.broadcast(type, id);
    }
}
