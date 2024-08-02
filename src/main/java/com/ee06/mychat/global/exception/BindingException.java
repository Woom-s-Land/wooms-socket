package com.ee06.mychat.global.exception;

import lombok.Getter;

@Getter
public class BindingException extends RuntimeException{
    final ErrorCode errorCode;
    final String message;
    public BindingException(String message) {
        this.errorCode = ErrorCode.BINDING_ERROR;
        this.message = message;
    }
}
