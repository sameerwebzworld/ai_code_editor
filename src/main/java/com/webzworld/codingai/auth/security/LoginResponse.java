package com.webzworld.codingai.auth.security;

import com.webzworld.codingai.auth.dto.UserDto;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponse {
    private String token;
    private UserDto userDto;
}
