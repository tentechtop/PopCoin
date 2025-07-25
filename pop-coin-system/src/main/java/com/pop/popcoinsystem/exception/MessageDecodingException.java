package com.pop.popcoinsystem.exception;

/**
 * Encoding exception.
 */
public class MessageDecodingException extends RuntimeException {
	public MessageDecodingException(String message)
	{
		super(message);
	}

	public MessageDecodingException(String message, Throwable cause)
	{
		super(message, cause);
	}
}
