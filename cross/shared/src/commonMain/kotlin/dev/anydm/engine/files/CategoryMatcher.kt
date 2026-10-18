package dev.anydm.engine.files

/** Picks the category a download belongs to; the first by priority (then id) whose rules claim it. */
class CategoryMatcher(
    categories: List<Category>,
) {
    private val ordered = categories.sortedWith(compareBy({ it.priority }, { it.id.value }))

    fun match(facts: SourceFacts): Category? = ordered.firstOrNull { category -> category.rules.any { it.matches(facts) } }
}
