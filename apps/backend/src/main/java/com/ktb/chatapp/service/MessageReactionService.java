package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageReactionService {

    private final MongoTemplate mongoTemplate;

    public Optional<Message> update(String messageId, String reaction, String userId, boolean add) {
        if (reaction == null || reaction.isBlank() || reaction.contains(".") || reaction.startsWith("$")) {
            throw new IllegalArgumentException("유효하지 않은 리액션입니다.");
        }

        Query query = Query.query(Criteria.where("_id").is(messageId));
        String field = "reactions." + reaction;
        Update update = add ? new Update().addToSet(field, userId) : new Update().pull(field, userId);
        Message updated = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), Message.class);
        return Optional.ofNullable(updated);
    }
}
