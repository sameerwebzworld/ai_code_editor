package com.webzworld.codingai.auth.security;

import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.repo.UserRepositary;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

@Component
public class UserDetailsServiceImpl implements UserDetailsService {

    private UserRepositary userRepositary;

    public UserDetailsServiceImpl(UserRepositary userRepositary) {
        this.userRepositary = userRepositary;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepositary.findByEmail(username).orElseThrow(()-> new RuntimeException("email not found"));
        return user;
    }
}
