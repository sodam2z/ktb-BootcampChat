package com.ktb.chatapp.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.Assert;

public class BannedWordChecker {

    private final Node root = new Node();

    public BannedWordChecker(Set<String> bannedWords) {
        Set<String> normalizedWords =
                bannedWords.stream()
                        .filter(word -> word != null && !word.isBlank())
                        .map(word -> word.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        Assert.notEmpty(normalizedWords, "Banned words set must not be empty");

        normalizedWords.forEach(this::insert);
        buildFailureLinks();
    }

    public boolean containsBannedWord(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        Node node = root;

        for (int i = 0; i < normalizedMessage.length(); i++) {
            char current = normalizedMessage.charAt(i);

            while (node != root && !node.children.containsKey(current)) {
                node = node.failure;
            }

            node = node.children.getOrDefault(current, root);
            if (node.terminal) {
                return true;
            }
        }

        return false;
    }

    private void insert(String word) {
        Node node = root;
        for (int i = 0; i < word.length(); i++) {
            char current = word.charAt(i);
            node = node.children.computeIfAbsent(current, ignored -> new Node());
        }
        node.terminal = true;
    }

    private void buildFailureLinks() {
        root.failure = root;
        Queue<Node> queue = new ArrayDeque<>();

        for (Node child : root.children.values()) {
            child.failure = root;
            queue.add(child);
        }

        while (!queue.isEmpty()) {
            Node node = queue.remove();

            for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                char current = entry.getKey();
                Node child = entry.getValue();
                Node fallback = node.failure;

                while (fallback != root && !fallback.children.containsKey(current)) {
                    fallback = fallback.failure;
                }

                child.failure = fallback.children.getOrDefault(current, root);
                child.terminal = child.terminal || child.failure.terminal;
                queue.add(child);
            }
        }
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private Node failure;
        private boolean terminal;
    }
}
