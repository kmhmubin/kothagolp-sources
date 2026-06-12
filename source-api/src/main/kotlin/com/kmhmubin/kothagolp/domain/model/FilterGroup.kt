package com.kmhmubin.kothagolp.domain.model

data class FilterGroup(
    val key: String,
    val label: String,
    val options: List<FilterOption>,
    val defaultValue: String? = null
)
