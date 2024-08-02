package com.ee06.mychat.global.jwt.exception;

import com.ee06.mychat.global.exception.ErrorCode;
import lombok.Getter;

import static com.ee06.mychat.global.exception.ErrorCode.INVALID_REFRESH_TOKEN;


@Getter
public class InvalidRefreshTokenException extends RuntimeException {
    private final ErrorCode errorCode;

    public InvalidRefreshTokenException() {
        this.errorCode = INVALID_REFRESH_TOKEN;
    }
}
