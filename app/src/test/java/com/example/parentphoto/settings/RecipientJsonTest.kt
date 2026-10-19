package com.example.parentphoto.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientJsonTest {
    @Test fun roundTrip_keepsOrderAndUnicode() {
        val list = listOf(
            Recipient("id-1", "長男", "a@example.com"),
            Recipient("id-2", "娘\"quote\"", "b@example.com"),
        )
        assertEquals(list, RecipientJson.decode(RecipientJson.encode(list)))
    }

    @Test fun empty() {
        assertEquals("[]", RecipientJson.encode(emptyList()))
        assertEquals(emptyList<Recipient>(), RecipientJson.decode("[]"))
    }

    @Test fun corruptedJson_returnsEmpty() {
        assertEquals(emptyList<Recipient>(), RecipientJson.decode("not json"))
    }

    @Test fun elementWithoutId_skipped() {
        val json = """[{"name":"x","email":"y"},{"id":"k","name":"n","email":"e"}]"""
        assertEquals(listOf(Recipient("k", "n", "e")), RecipientJson.decode(json))
    }
}
