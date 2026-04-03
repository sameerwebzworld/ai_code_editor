package com.webzworld.codingai.auth.repo;

import com.webzworld.codingai.auth.entity.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FolderRepository extends JpaRepository<Folder, String> {
    List<Folder> findByUserId(String userId);
    @Query("SELECT f FROM Folder f WHERE f.id = :id AND f.userId = :userId")
    Optional<Folder> findByIdAndUserId(@Param("id") String id, @Param("userId") String userId);
}