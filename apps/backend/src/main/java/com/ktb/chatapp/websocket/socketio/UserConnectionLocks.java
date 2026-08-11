package com.ktb.chatapp.websocket.socketio;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * Serializes connection lifecycle changes for the same user.
 *
 * <p>A fixed number of stripes avoids retaining one lock per user forever while
 * still allowing unrelated users to connect and disconnect concurrently.</p>
 */
@Component
public class UserConnectionLocks {

    private static final int STRIPE_COUNT = 256;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

    public UserConnectionLocks() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public void withLock(String userId, Runnable action) {
        ReentrantLock lock = locks[Math.floorMod(userId.hashCode(), locks.length)];
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
