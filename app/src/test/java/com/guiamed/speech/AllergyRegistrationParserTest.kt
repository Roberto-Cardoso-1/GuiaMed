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
        assertEquals(
            AllergyRegistrationInputType.CAMERA,
            AllergyRegistrationParser.parse("câmara").type,
        )
        assertEquals(
            AllergyRegistrationInputType.CAMERA,
            AllergyRegistrationParser.parse("ler pela câmera").type,
        )
        assertEquals(
            AllergyRegistrationInputType.CAMERA,
            AllergyRegistrationParser.parse("foto").type,
        )
        assertEquals(
            AllergyRegistrationInputType.CAMERA,
            AllergyRegistrationParser.parse("sim, usar câmera").type,
        )
    }

    @Test
    fun `nome do medicamento permanece como nome e ignora palavras genericas`() {
        val parsed = AllergyRegistrationParser.parse("Tenho alergia a maleato de enalapril")
        assertEquals(AllergyRegistrationInputType.MEDICATION_NAME, parsed.type)
        assertEquals("maleato de enalapril", parsed.medicationName)
        assertEquals(AllergyRegistrationInputType.UNKNOWN, AllergyRegistrationParser.parse("remédio").type)
        assertEquals(AllergyRegistrationInputType.UNKNOWN, AllergyRegistrationParser.parse("ok").type)
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
        assertEquals(
            AllergyRegistrationInputType.CANCEL,
            AllergyRegistrationParser.parse("errado").type,
        )
    }
}
