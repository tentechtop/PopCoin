package com.pop.popcoinsystem.exception;

import java.util.concurrent.TimeoutException;

public class ResponseTimeoutException extends TimeoutException {
    public ResponseTimeoutException(String message) {
        super(message);
    }
}