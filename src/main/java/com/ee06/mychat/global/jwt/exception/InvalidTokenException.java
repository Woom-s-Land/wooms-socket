package com.ee06.mychat.global.jwt.exception;

import com.ee06.mychat.global.exception.ErrorCode;
import lombok.Getter;

import static com.ee06.mychat.global.exception.ErrorCode.INVALID_TOKEN;


@Getter
public class InvalidTokenException extends RuntimeException {
    private final ErrorCode errorCode;

    public InvalidTokenException() {
        this.errorCode = INVALID_TOKEN;
    }
}
