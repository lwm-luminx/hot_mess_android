package social.hotmess.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpHistoryTest {
    private val alertJson = """
        {"id":"a1","state":"CLOSED","concerns":["UNWELL"],"summary":"They don't feel well","createdAt":"2026-11-26T03:00:00Z",
         "venue":{"id":"v1","name":"Club Try-Angles"},
         "messages":[
          {"id":"m1","body":"Food Bank","fromStaff":true,"senderName":"Dee",
           "resource":{"id":"r1","title":"Food Bank","phone":"555-0100","address":"1 Main St","intakeNote":"No ID needed.","sensitive":false}},
          {"id":"m2","body":"Safe place","fromStaff":true,"senderName":"Dee",
           "resource":{"id":"r2","title":"Safe place","sensitive":true}},
          {"id":"m3","body":"Thanks","fromStaff":false,"senderName":"You"}]}
    """.trimIndent()

    private fun alert() = HotMessApi.json.decodeFromString(SafetyAlert.serializer(), alertJson)

    @Test
    fun readsASharedPlaceFromTheThread() {
        val resource = alert().messages.first().resource!!
        assertEquals("tel:5550100", resource.callUri)
        assertEquals("geo:0,0?q=1+Main+St", resource.mapUri)
        assertNull(resource.textUri)
        assertNull(alert().messages.last().resource)
    }

    @Test
    fun keepsSharedPlacesOnThePhoneButNeverASensitiveOneAndClears() {
        val file = File.createTempFile("help-history", ".json").apply { delete() }
        val history = HelpHistory(file)
        history.record(alert())
        history.record(listOf(alert()))
        val entry = history.entries.value.single()
        assertEquals(listOf("r1"), entry.resources.map { it.id })
        assertEquals("Club Try-Angles", entry.venueName)
        assertFalse(file.readText().contains("Safe place"))
        assertFalse(file.readText().contains("Thanks"))
        assertEquals(history.entries.value, HelpHistory(file).entries.value)

        history.clear()
        assertTrue(history.entries.value.isEmpty())
        assertFalse(file.exists())
    }
}
