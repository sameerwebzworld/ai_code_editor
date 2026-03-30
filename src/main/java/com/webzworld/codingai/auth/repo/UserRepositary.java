package com.webzworld.codingai.auth.repo;

import com.webzworld.codingai.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepositary extends JpaRepository<User,Integer> {
    boolean existsByEmail(String email);
    Optional<User> findByEmail(String email);

}
