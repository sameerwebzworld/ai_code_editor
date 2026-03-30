package com.webzworld.codingai.auth.repo;

import com.webzworld.codingai.auth.entity.Message;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId ORDER BY m.createdAt ASC")
    List<Message> findAllByConversationId(@Param("conversationId") String conversationId);

    @Query(value = "SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC LIMIT :limit",
            nativeQuery = true)
    List<Message> findLastNByConversationId(@Param("conversationId") String conversationId, @Param("limit") int limit);

    @Modifying
    @Transactional
    @Query("DELETE FROM Message m WHERE m.conversationId = :conversationId")
    void deleteAllByConversationId(@Param("conversationId") String conversationId);

    // Count messages in a conversation
    @Query("SELECT COUNT(m) FROM Message m WHERE m.conversationId = :conversationId")
    long countByConversationId(@Param("conversationId") String conversationId);
}
