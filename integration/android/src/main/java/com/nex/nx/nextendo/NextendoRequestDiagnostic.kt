package com.nex.nx.nextendo

/** Human-readable, token-free diagnostics for Nextendo API responses. */
internal object NextendoRequestDiagnostic {
    fun fromHttpStatus(status: Int): String {
        require(status in 100..599) { "HTTP status must be between 100 and 599." }
        return when (status) {
            200, 201, 202, 204 -> "HTTP $status (accepted)"
            400 -> "HTTP 400 (request rejected)"
            401 -> "HTTP 401 (authentication rejected)"
            403 -> "HTTP 403 (permission or client not authorized)"
            404 -> "HTTP 404 (endpoint not found)"
            409 -> "HTTP 409 (state conflict)"
            429 -> "HTTP 429 (rate limited)"
            in 500..599 -> "HTTP $status (server error)"
            else -> "HTTP $status"
        }
    }

    fun fromFailure(error: Throwable): String = when (error) {
        is NextendoAuthClient.NextendoException -> fromHttpStatus(error.httpStatus)
        is java.io.IOException -> "network_error"
        is IllegalStateException -> "session_unavailable"
        is IllegalArgumentException -> "response_contract_error"
        else -> "request_error"
    }

    fun missingScope(scope: String): String {
        require(scope.isNotBlank()) { "A scope name is required." }
        return "scope_missing:$scope"
    }
}
