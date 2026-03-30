package com.webzworld.codingai.auth.repo;

import com.webzworld.codingai.auth.entity.Role;
import com.webzworld.codingai.auth.entity.enums.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepositary extends JpaRepository<Role, Integer> {
    Optional<Role> findByRoleName(AppRole roleName);
}