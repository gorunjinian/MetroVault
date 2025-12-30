package com.gorunjinian.metrovault.data.model

/**
 * Generic result type for operations that can succeed or fail.
 *
 * This sealed class provides a type-safe way to handle success and error cases
 * with optional associated values.
 *
 * @param T The type of the success value
 * @param E The type of the error value (typically an error enum or String)
 */
sealed class Result<T, E> {
    /**
     * Represents a successful operation with an associated value.
     *
     * @param value The result value of the successful operation
     */
    data class Success<T, E>(val value: T) : Result<T, E>()

    /**
     * Represents a failed operation with an associated error.
     *
     * @param error The error value (typically an error enum or String)
     */
    data class Error<T, E>(val error: E) : Result<T, E>()

    // ==================== Convenience Methods ====================

    /**
     * Checks if this result is a success.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Checks if this result is an error.
     */
    fun isError(): Boolean = this is Error

    /**
     * Returns the success value, or null if this is an error.
     */
    fun getOrNull(): T? = (this as? Success<T, E>)?.value

    /**
     * Returns the error, or null if this is a success.
     */
    fun errorOrNull(): E? = (this as? Error<T, E>)?.error

    /**
     * Folds this result into a single value.
     * Applies one of the given functions depending on whether this is a success or error.
     *
     * @param onSuccess Function to apply to the success value
     * @param onError Function to apply to the error value
     * @return The result of applying the appropriate function
     */
    inline fun <R> fold(
        onSuccess: (T) -> R,
        onError: (E) -> R
    ): R = when (val result = this) {
        is Success -> onSuccess(result.value)
        is Error -> onError(result.error)
    }

    /**
     * Executes the given function for side effects if this is a success.
     * Returns this result unchanged.
     *
     * @param block Function to execute with the success value
     * @return This result unchanged
     */
    inline fun onSuccess(block: (T) -> Unit): Result<T, E> {
        if (this is Success) block(this.value)
        return this
    }

    /**
     * Executes the given function for side effects if this is an error.
     * Returns this result unchanged.
     *
     * @param block Function to execute with the error value
     * @return This result unchanged
     */
    inline fun onError(block: (E) -> Unit): Result<T, E> {
        if (this is Error) block(this.error)
        return this
    }
}
