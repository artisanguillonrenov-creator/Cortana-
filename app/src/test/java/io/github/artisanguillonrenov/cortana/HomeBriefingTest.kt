package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.connections.HomeAssistantConnector
import io.github.artisanguillonrenov.cortana.core.connections.HomeBriefing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBriefingTest {
    private fun entity(id: String, state: String, label: String = id, attrs: Map<String, String> = emptyMap()) =
        HomeAssistantConnector.Entity(id, state, label, attrs)

    @Test fun summarizesExistingPermittedStateWithoutSendingCommands() {
        val result = HomeBriefing.summarize(listOf(
            entity("light.cuisine", "on", "Cuisine"),
            entity("light.salon", "off", "Salon"),
            entity("lock.porte", "unlocked", "Porte"),
            entity("alarm_control_panel.maison", "triggered", "Alarme"),
            entity("climate.sejour", "heat", "Séjour", mapOf("current_temperature" to "21.5")),
            entity("switch.machine", "unavailable", "Machine"),
        ))
        assertEquals(6, result.entitiesSeen)
        assertEquals(listOf("Cuisine"), result.activeLights)
        assertEquals(listOf("Porte"), result.openAccess)
        assertEquals(listOf("Alarme"), result.urgent)
        assertEquals(listOf("Machine"), result.unavailable)
        assertTrue(result.format().contains("21.5 °C"))
    }

    @Test fun distinguishesMotionFromDoorAndSmokeSensors() {
        val result = HomeBriefing.summarize(listOf(
            entity("binary_sensor.motion_hall", "on", "Mouvement", mapOf("device_class" to "motion")),
            entity("binary_sensor.door_front", "on", "Porte d'entrée", mapOf("device_class" to "door")),
            entity("binary_sensor.smoke", "on", "Fumée", mapOf("device_class" to "smoke"))
        ))
        assertEquals(listOf("Porte d'entrée"), result.openAccess)
        assertEquals(listOf("Fumée"), result.urgent)
        assertFalse(result.openAccess.contains("Mouvement"))
        assertFalse(result.urgent.contains("Mouvement"))
    }

    @Test fun roomFilterAndOutputLimitAreBounded() {
        val lights = (1..50).map { entity("light.cuisine_$it", "on", "Cuisine $it") } +
            entity("light.salon", "on", "Salon")
        val result = HomeBriefing.summarize(lights, room = "cuisine", maxPerSection = 3)
        assertEquals(50, result.entitiesSeen)
        assertEquals(3, result.activeLights.size)
        assertTrue(result.activeLights.all { it.contains("Cuisine") })
    }

    @Test fun unknownOrInvalidSensorsDoNotProduceFalseEmergencies() {
        val result = HomeBriefing.summarize(listOf(
            entity("alarm_control_panel.house", "unknown"),
            entity("binary_sensor.fire", "unavailable", "Fumée", mapOf("device_class" to "smoke")),
            entity("climate.x", "heat", attrs = mapOf("current_temperature" to "NaN")),
        ))
        assertTrue(result.urgent.isEmpty())
        assertTrue(result.openAccess.isEmpty())
        assertTrue(result.temperatures.isEmpty())
        assertEquals(2, result.unavailable.size)
    }
}
