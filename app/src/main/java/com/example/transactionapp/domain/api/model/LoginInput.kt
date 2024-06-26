package com.example.transactionapp.domain.api.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LoginInput(
    @Json(name = "email")
    val email: String,
    @Json(name = "password")
    val password: String
)