package com.kernoeb.fff3.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouriteOrderTest {

    private fun fav(id: String) = Favourite(id, id, emptyList())

    @Test
    fun followsTheGivenIdsAndKeepsOthersAtTheEnd() {
        val list = listOf(fav("a"), fav("b"), fav("c"), fav("d"))
        assertEquals(listOf("c", "a", "b", "d"), list.inOrderOf(listOf("c", "a")).map { it.id })
        assertEquals(listOf("d", "c", "b", "a"), list.inOrderOf(listOf("d", "c", "b", "a", "gone")).map { it.id })
    }
}
