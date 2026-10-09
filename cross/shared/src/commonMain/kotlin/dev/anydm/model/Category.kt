package dev.anydm.model

import kotlinx.serialization.Serializable

/** Downloads, the default category; fixed by the API's migration. */
const val DOWNLOADS_CATEGORY_ID = "00000000-0000-4000-8000-000000000001"

/** A category as the manager and the pickers show it. web: `CategoryItem`. */
@Serializable
data class CategoryDto(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    /** Relative to the download folder; "" is the download folder itself. */
    val folder: String = "",
    val position: Int = 0,
    /** Downloads: its folder is fixed and it can't be deleted. */
    val builtin: Boolean = false,
    /** List items in it. */
    val count: Int = 0,
)

@Serializable
data class CategoryListDto(
    val categories: List<CategoryDto> = emptyList(),
)

/** A row's category. web: `CategoryRef`. */
@Serializable
data class CategoryRefDto(
    val id: String = "",
    val name: String = "",
)
