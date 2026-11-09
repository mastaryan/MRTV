package com.ipvio.stream

import android.content.Context

data class Person(val id: Int, val name: String, val role: String?, val photo: String?)
data class Credits(val cast: List<Person>, val directors: List<Person>, val similar: List<Ref>)
/** [from] = title from which the person was opened (always included on their page, round 14). */
data class PersonRef(val id: Int, val name: String, val photo: String?, val from: String? = null)
data class PersonInfo(
    val id: Int, val name: String, val bio: String?, val photo: String?,
    val born: String?, val place: String?, val dept: String?, val titles: List<Ref>
)

/**
 * Cast, director, similar titles and person pages.
 *
 * MR TV 2: upstream IPVIO loaded these from an outside metadata service. That service code was removed; the Xtream panel
 * has no cast or person data, so these always return null and the cast rows and person pages simply do not appear.
 * The shared screens keep their hooks so upstream IPVIO updates still merge.
 */
object CreditsRepo {
    val lastError: String? = null
    fun trim() {}
    @Suppress("UNUSED_PARAMETER")
    suspend fun credits(ctx: Context, m: Meta): Credits? = null
    @Suppress("UNUSED_PARAMETER")
    suspend fun person(id: Int): PersonInfo? = null
}
