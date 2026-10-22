package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitGeofenceTest {
    private val home = OrbitGeofenceDefinition("Home", 31.2, 121.4)

    @Test fun `coordinates radius and transition must be valid`() {
        assertThrows(IllegalArgumentException::class.java) { home.copy(latitude = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(longitude = 181.0) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(radius = 20.0) }
        assertThrows(IllegalArgumentException::class.java) { home.copy(transition = "BOTH") }
    }

    @Test fun `accuracy and deadband prevent edge bounce`() {
        assertEquals(true, home.side(150.0, 20.0))
        assertNull(home.side(195.0, 20.0))
        assertNull(home.side(225.0, 5.0))
        assertNull(home.side(260.0, 20.0))
        assertEquals(false, home.side(280.0, 20.0))
        assertNull(home.side(Double.NaN, 0.0))
        assertNull(home.side(0.0, -1.0))
    }

    @Test fun `distance uses metres and handles the date line`() {
        assertEquals(0.0, home.distance(31.2, 121.4), 0.001)
        assertEquals(111.195, home.distance(31.201, 121.4), 0.1)
        val dateLine = home.copy(latitude = 0.0, longitude = 179.999)
        assertEquals(222.39, dateLine.distance(0.0, -179.999), 0.1)
    }
}
