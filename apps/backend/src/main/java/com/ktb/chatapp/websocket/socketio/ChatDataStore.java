package com.ktb.chatapp.websocket.socketio;

import java.util.Optional;

/**
 * Data store interface for chat-related data storage.
 * Provides key-value storage operations for chat user and room data.
 */
public interface ChatDataStore {
    
    /**
     * Retrieve a value by key
     *
     * @param key the storage key
     * @param type the type of value to retrieve
     * @param <T> the type parameter
     * @return Optional containing the value if found, empty otherwise
     */
    <T> Optional<T> get(String key, Class<T> type);
    
    /**
     * Store a value with the given key
     *
     * @param key the storage key
     * @param value the value to store
     */
    void set(String key, Object value);

    default <T> T getAndSet(String key, T value, Class<T> type) {
        T previous = get(key, type).orElse(null);
        set(key, value);
        return previous;
    }
    
    /**
     * Delete a value by key
     *
     * @param key the storage key
     */
    void delete(String key);

    default boolean refresh(String key) {
        return get(key, Object.class).isPresent();
    }

    default boolean compareAndDelete(String key, Object expectedValue) {
        Optional<Object> current = get(key, Object.class);
        if (current.isPresent() && current.get().equals(expectedValue)) {
            delete(key);
            return true;
        }
        return false;
    }

    default java.util.Set<String> getSet(String key) {
        return get(key, java.util.Set.class)
                .map(value -> new java.util.HashSet<>((java.util.Set<String>) value))
                .orElseGet(java.util.HashSet::new);
    }

    default void addToSet(String key, String value) {
        java.util.Set<String> values = getSet(key);
        values.add(value);
        set(key, values);
    }

    default boolean setContains(String key, String value) {
        return getSet(key).contains(value);
    }

    default void removeFromSet(String key, String value) {
        java.util.Set<String> values = getSet(key);
        values.remove(value);
        if (values.isEmpty()) {
            delete(key);
        } else {
            set(key, values);
        }
    }
    
    int size();
}
