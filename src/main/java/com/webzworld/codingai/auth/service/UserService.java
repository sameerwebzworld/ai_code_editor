package com.webzworld.codingai.auth.service;

import com.webzworld.codingai.auth.dto.GenericResponseDto;
import com.webzworld.codingai.auth.dto.UserDto;

public interface UserService {
    GenericResponseDto createUser(UserDto userDto);

}
