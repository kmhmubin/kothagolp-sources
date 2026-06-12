package com.kmhmubin.kothagolp.domain.model

data class MainPageResult(
    val url: String,
    val novels: List<Novel>,
    val hasNextPage: Boolean = true
)
