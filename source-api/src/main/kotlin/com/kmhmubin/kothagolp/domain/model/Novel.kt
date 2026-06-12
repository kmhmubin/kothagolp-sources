package com.kmhmubin.kothagolp.domain.model

data class Novel(
    val name: String,
    val url: String,
    val posterUrl: String? = null,
    val rating: Int? = null,
    val latestChapter: String? = null,
    val apiName: String = ""
)
