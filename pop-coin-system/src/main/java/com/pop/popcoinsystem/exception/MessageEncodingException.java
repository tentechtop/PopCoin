package com.pop.popcoinsystem.exception;

/**
 * Encoding exception.
 */
public class MessageEncodingException extends RuntimeException {
	public MessageEncodingException(String message)
	{
		super(message);
	}

	public MessageEncodingException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
