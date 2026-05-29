package com.qiniu.back.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiniu.back.domain.ResponseDTO;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;

public class ResponseUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void write(HttpServletResponse response, ResponseDTO<?> body) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
