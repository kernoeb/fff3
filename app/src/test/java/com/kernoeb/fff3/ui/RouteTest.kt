package com.kernoeb.fff3.ui

import com.kernoeb.fff3.ble.ControlTarget
import com.kernoeb.fff3.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteTest {

    @Test
    fun everyRouteRoundTrips() {
        val routes = listOf(
            Route.Home,
            Route.AddDevice,
            Route.Settings,
            Route.Control(ControlTarget.Device("BE:FF:20:00:00:01")),
            Route.Control(ControlTarget.Group("0d1f6c2e-8a1b-4f6e-9a55-1c2d3e4f5a6b")),
            Route.Music(),
            Route.Music(ControlTarget.Device("BE:FF:20:00:00:01")),
            Route.Music(ControlTarget.Group("0d1f6c2e-8a1b-4f6e-9a55-1c2d3e4f5a6b")),
        )
        for (route in routes) assertEquals(route, Route.decode(route.encode()))
    }

    @Test
    fun macAddressColonsSurvive() {
        assertEquals("control/device:AA:BB:CC:DD:EE:FF", Route.Control(ControlTarget.Device("AA:BB:CC:DD:EE:FF")).encode())
    }

    @Test
    fun garbageGivesNull() {
        assertNull(Route.decode(null))
        assertNull(Route.decode("nope"))
        assertNull(Route.decode("control/"))
        assertNull(Route.decode("control/device:"))
        assertNull(Route.decode("control/lamp:1"))
        assertNull(ControlTarget.decode("group"))
    }
}
