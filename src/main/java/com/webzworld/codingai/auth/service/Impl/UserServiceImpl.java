package com.webzworld.codingai.auth.service.Impl;

import com.webzworld.codingai.auth.dto.GenericResponseDto;
import com.webzworld.codingai.auth.dto.UserDto;
import com.webzworld.codingai.auth.entity.Role;
import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.entity.enums.AppRole;
import com.webzworld.codingai.auth.repo.RoleRepositary;
import com.webzworld.codingai.auth.repo.UserRepositary;
import com.webzworld.codingai.auth.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl implements UserService {
    private UserRepositary userRepositary;
    private PasswordEncoder passwordEncoder;
    private RoleRepositary roleRepositary;

    public UserServiceImpl(UserRepositary userRepositary, PasswordEncoder passwordEncoder, RoleRepositary roleRepositary) {
        this.userRepositary = userRepositary;
        this.passwordEncoder = passwordEncoder;
        this.roleRepositary = roleRepositary;
    }

    @Override
    public GenericResponseDto createUser(UserDto userDto) {
        if (userRepositary.existsByEmail(userDto.getEmail())) {
            throw new RuntimeException("Email already exists");
        }

        User user = new User();
        user.setName(userDto.getName());
        user.setEmail(userDto.getEmail());
        user.setPassword(this.passwordEncoder.encode(userDto.getPassword()));
        Role role = roleRepositary.findByRoleName(AppRole.ROLE_USER).orElseThrow(()->new RuntimeException("Role Not Found"));
        user.setRole(role);
        userRepositary.saveAndFlush(user);
        return new GenericResponseDto("success","New Account Created with email-"+user.getEmail());
    }
}
