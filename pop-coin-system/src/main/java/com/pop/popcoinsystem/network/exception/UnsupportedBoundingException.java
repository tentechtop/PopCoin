package com.pop.popcoinsystem.network.exception;

public class UnsupportedBoundingException extends Exception {
    public UnsupportedBoundingException(Class<?> aClass) {
        super("Output type not supported" + aClass.getName());
    }
}
