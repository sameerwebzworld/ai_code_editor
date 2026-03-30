package com.webzworld.codingai.auth.entity;

import com.webzworld.codingai.auth.entity.enums.AppRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "role")
public class Role {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private int roleId;
    @Enumerated(EnumType.STRING)
    private AppRole roleName;
    @OneToMany(mappedBy = "role")
    private List<User> users;
}
