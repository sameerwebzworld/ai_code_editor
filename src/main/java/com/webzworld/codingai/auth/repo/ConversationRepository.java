package com.webzworld.codingai.auth.repo;

import com.webzworld.codingai.auth.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId ORDER BY c.updatedAt DESC")
    List<Conversation> findAllByUserId(@Param("userId") String userId);

    @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.userId = :userId")
    Optional<Conversation> findByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Conversation c WHERE c.userId = :userId")
    void deleteAllByUserId(@Param("userId") String userId);
}
