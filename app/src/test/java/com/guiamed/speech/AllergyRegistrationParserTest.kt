package com.guiamed.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class AllergyRegistrationParserTest {
    @Test
    fun `camera com acento abre o escaneamento`() {
        assertEquals(
            AllergyRegistrationInputType.CAMERA,
            AllergyRegistrationParser.parse("Quero usar a câmera").type,
        )
    }

    @Test
    fun `nome do medicamento permanece como nome`() {
        val parsed = AllergyRegistrationParser.parse("maleato de enalapril")
        assertEquals(AllergyRegistrationInputType.MEDICATION_NAME, parsed.type)
        assertEquals("maleato de enalapril", parsed.medicationName)
    }

    @Test
    fun `confirmacao e cancelamento sao deterministas`() {
        assertEquals(
            AllergyRegistrationInputType.CONFIRM,
            AllergyRegistrationParser.parse("sim, confirmo").type,
        )
        assertEquals(
            AllergyRegistrationInputType.CANCEL,
            AllergyRegistrationParser.parse("não").type,
        )
    }
}
