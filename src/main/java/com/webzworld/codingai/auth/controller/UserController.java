package com.webzworld.codingai.auth.controller;

import com.webzworld.codingai.auth.dto.GenericResponseDto;
import com.webzworld.codingai.auth.dto.UserDto;
import com.webzworld.codingai.auth.entity.User;
import com.webzworld.codingai.auth.repo.UserRepositary;
import com.webzworld.codingai.auth.security.LoginRequest;
import com.webzworld.codingai.auth.security.LoginResponse;
import com.webzworld.codingai.auth.security.jwt.JwtUtils;
import com.webzworld.codingai.auth.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/apiv1/user")
public class UserController {
    private UserService userService;
    private AuthenticationManager authenticationManager;
    private JwtUtils jwtUtils;
    private UserRepositary userRepositary;

    public UserController(UserService userService, AuthenticationManager authenticationManager, JwtUtils jwtUtils, UserRepositary userRepositary) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.userRepositary = userRepositary;
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody  UserDto userDto){
        return new ResponseEntity<GenericResponseDto>(userService.createUser(userDto), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest){
        String email = loginRequest.getEmail();
        String password = loginRequest.getPassword();

        Authentication authenticate = null;
        try {
            authenticate = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
        }
        catch (BadCredentialsException e) {
            System.out.println("bad crenentials");
        }
        SecurityContextHolder.getContext().setAuthentication(authenticate);
        User user = (User)authenticate.getPrincipal();
        String token = jwtUtils.generateTokenFromUsername(user);
        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken(token);
        UserDto userDto =  new UserDto();
        userDto.setEmail(user.getEmail());
        userDto.setName(user.getName());
        loginResponse.setUserDto(userDto);
        return new ResponseEntity<LoginResponse>(loginResponse,HttpStatus.OK);
    }


}
