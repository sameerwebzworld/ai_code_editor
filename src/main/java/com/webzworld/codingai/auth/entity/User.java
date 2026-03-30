package com.webzworld.codingai.auth.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Arrays;
import java.util.Collection;

@Getter
@Setter
@Entity
@Table(name = "users")
public class User implements UserDetails {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @NotNull(message = "name is required")
    @NotBlank(message = "name can not be blank")
    private String name;
    @NotNull(message = "password is required")
    @NotBlank(message = "password can not be blank")
    private String password;
    @NotNull(message = "email is required")
    @NotBlank(message = "email can not be blank")
    @Column(unique = true)
    private String email;
    @ManyToOne
    private Role role;
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // TODO Auto-generated method stub
        return Arrays.asList(new SimpleGrantedAuthority(role.getRoleName().toString()));
    }
    @Override
    public String getUsername() {
        // TODO Auto-generated method stub
        return email;
    }
    @Override
    public String getPassword() {
        // TODO Auto-generated method stub
        return password;
    }
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }
    @Override
    public boolean isEnabled() {
        return true;
    }
}
