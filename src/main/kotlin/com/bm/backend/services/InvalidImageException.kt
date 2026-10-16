package com.bm.backend.services

/** Thrown when an uploaded image's content is not a decodable JPEG/PNG. */
class InvalidImageException(message: String = "Invalid image file", cause: Throwable? = null) :
    Exception(message, cause)
