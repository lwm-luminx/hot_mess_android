package social.hotmess.core

import kotlinx.serialization.Serializable

/**
 * The proof of age the United Kingdom and Australia ask for (`me.ageCheck`): a one-time purchase in the app pays an
 * age check company to confirm the member is 18 or older. Everywhere else nothing is asked beyond the 18+
 * confirmation at sign-in.
 */
@Serializable
data class AgeCheck(
    /** Whether they still owe a check. */
    val required: Boolean = false,
    /** GB or AU when their country asks for one. */
    val region: String? = null,
    /** What to do next, as the API names it: see [Step]. */
    val state: String = "NOT_REQUIRED",
    /** The one-time purchase's product ID in each of the audience's apps. */
    val productIds: List<String> = emptyList(),
    /** Whether the API refuses posting until the check passes. While false they may carry on and be asked again. */
    val enforced: Boolean = false,
    /** What to show before the purchase sheet. */
    val explainer: Explainer? = null,
) {
    @Serializable
    data class Explainer(val title: String, val body: String, val points: List<String> = emptyList())

    /** What the app does next. A state this build doesn't know asks nothing. */
    enum class Step { NONE, EXPLAIN_AND_BUY, START, WAIT, DONE }

    val step: Step
        get() = when (state) {
            "PURCHASE_NEEDED" -> Step.EXPLAIN_AND_BUY
            "READY" -> Step.START
            "IN_PROGRESS" -> Step.WAIT
            "PASSED" -> Step.DONE
            else -> Step.NONE
        }

    /** The product this app sells: its own package's, when the audience lists it. */
    fun productId(packageName: String): String? {
        val own = "$packageName.$PRODUCT_SUFFIX"
        return productIds.firstOrNull { it == own }
    }

    companion object {
        const val PRODUCT_SUFFIX = "agecheck"

        /** Countries whose members need proof of age, as Play Billing names them. */
        val REGIONS = setOf("GB", "AU")
    }
}

/** A vendor session opened by `startAgeCheck`: its hosted page, and where they stand now. */
data class AgeCheckSession(val url: String?, val provider: String, val ageCheck: AgeCheck)
