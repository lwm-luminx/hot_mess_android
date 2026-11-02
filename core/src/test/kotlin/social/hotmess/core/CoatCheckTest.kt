package social.hotmess.core

import com.audiencekit.Admission
import com.audiencekit.AdmissionKind
import com.audiencekit.AdmissionStatus
import com.audiencekit.CoatCheck
import com.audiencekit.CoverCharge
import com.audiencekit.CoverPurchase
import com.audiencekit.Money
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoatCheckTest {
    private val coatCheck = CoatCheck(amount = Money(300), total = Money(350), night = "2026-10-09", payable = true)
    private val charge = CoverCharge(amount = Money(1000), total = Money(1112), night = "2026-10-09", payable = true, coatCheck = coatCheck)
    private val coatPass = Admission(
        id = "c-1",
        status = AdmissionStatus.PAID,
        kind = AdmissionKind.COAT_CHECK,
        night = "2026-10-09",
        quantity = 2,
        total = Money(700),
    )

    private fun venue(charge: CoverCharge?, coatCheckPass: Admission? = null, cover: Admission? = null) =
        Venue(id = "v-1", name = "The Wildrose", tickets = VenueTicketsMixin(VenueCoverMixin(charge, cover, coatCheckPass)))

    @Test
    fun aPayableCoatCheckCanBePaid() {
        val offer = CoatCheckOffer.of(venue(charge))!!
        assertEquals(CoverAction.PAY, offer.action)
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("Coat check $3.50 a coat", offer.line)
        } finally {
            Locale.setDefault(previous)
        }
        assertNull(CoatCheckOffer.of(venue(charge.copy(coatCheck = null))))
        assertNull(CoatCheckOffer.of(venue(null)))
    }

    @Test
    fun aCoatCheckThatCantBePaidHereIsPaidAtTheCoatCheck() {
        assertEquals(CoverAction.PAY_AT_DOOR, CoatCheckOffer.of(venue(charge.copy(coatCheck = coatCheck.copy(payable = false))))?.action)
    }

    @Test
    fun aPaidCoatCheckShowsItsPassButACoverPassDoesNot() {
        val offer = CoatCheckOffer.of(venue(charge, coatCheckPass = coatPass))!!
        assertEquals(CoverAction.SHOW_PASS, offer.action)
        assertEquals(coatPass, offer.pass)
        // The cover pass is the cover's, not the coat check's.
        val cover = coatPass.copy(kind = AdmissionKind.COVER)
        assertEquals(CoverAction.PAY, CoatCheckOffer.of(venue(charge, coatCheckPass = cover))?.action)
        // Last night's coat check doesn't count tonight.
        assertEquals(CoverAction.PAY, CoatCheckOffer.of(venue(charge, coatCheckPass = coatPass.copy(night = "2026-10-08")))?.action)
    }

    @Test
    fun passesSayHowManyCoats() {
        assertEquals("2 coats", coatPass.quantityText)
        assertEquals("1 coat", coatPass.copy(quantity = 1).quantityText)
        assertEquals("3 people", coatPass.copy(kind = AdmissionKind.COVER, quantity = 3).quantityText)
        assertNull(coatPass.copy(kind = AdmissionKind.COVER, quantity = 1).quantityText)
    }

    @Test
    fun aCoatCheckTheVenueCantTakeSaysSo() {
        val pending = coatPass.copy(status = AdmissionStatus.PENDING)
        val payment = CoverPayment.of(CoverPurchase(admission = pending, provider = "SQUARE"))
        assertTrue(payment is CoverPayment.Unavailable)
        assertTrue(payment.message.contains("coat check"))
    }
}
