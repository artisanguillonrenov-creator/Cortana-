package io.github.artisanguillonrenov.cortana

import io.github.artisanguillonrenov.cortana.core.connections.HomeAssistantConnector
import io.github.artisanguillonrenov.cortana.core.connections.HomeRoomTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRoomTargetTest {
    private fun e(id: String, name: String?) = HomeAssistantConnector.Entity(id, "on", name, emptyMap())

    @Test fun targetIsGroundedInActualEntityNameOrIdentifier() {
        assertTrue(HomeRoomTarget.matches(e("light.cuisine_plafond", "Plafond"), "cuisine"))
        assertTrue(HomeRoomTarget.matches(e("light.ab12", "Cuisine principale"), "cuisine"))
        assertTrue(HomeRoomTarget.matches(e("light.ab12", "Éclairage Séjour"), "sejour"))
        assertTrue(HomeRoomTarget.matches(e("light.chambre_1", "Chambre 1"), "chambre 1"))
        assertFalse(HomeRoomTarget.matches(e("light.chambre_10", "Chambre 10"), "chambre 1"))
    }

    @Test fun refusingAmbiguousOrUnknownRoomDoesNotGuess() {
        assertFalse(HomeRoomTarget.matches(e("light.salon", "Salon"), "cuisine"))
        assertFalse(HomeRoomTarget.matches(e("light.ab12", "Lumière"), "salle de bain"))
        assertFalse(HomeRoomTarget.matches(e("light.salon", "Salon"), "s"))
        assertFalse(HomeRoomTarget.matches(e("light.salon", "Salon"), ""))
        assertFalse(HomeRoomTarget.matches(e("light.cuisine", "Cuisine"), "salon"))
    }
}
