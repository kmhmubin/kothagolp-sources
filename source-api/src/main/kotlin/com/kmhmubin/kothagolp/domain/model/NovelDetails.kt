package com.kmhmubin.kothagolp.domain.model

data class NovelDetails(
    val url: String,
    val name: String,
    val chapters: List<Chapter>,
    val author: String? = null,
    val posterUrl: String? = null,
    val synopsis: String? = null,
    val tags: List<String>? = null,
    val rating: Int? = null,
    val peopleVoted: Int? = null,
    val status: String? = null,
    val views: Int? = null,
    val relatedNovels: List<Novel>? = null
)
