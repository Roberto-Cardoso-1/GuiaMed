package com.guiamed.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test
    fun understandsCoreCommandsWithNaturalVariations() {
        assertEquals(VoiceCommandType.READ_PACKAGE, VoiceCommandParser.parse("Leia a embalagem!").type)
        assertEquals(VoiceCommandType.READ_PACKAGE, VoiceCommandParser.parse("Quero ler o remédio").type)
        assertEquals(VoiceCommandType.READ_PRESCRIPTION, VoiceCommandParser.parse("Ler receita médica").type)
        assertEquals(VoiceCommandType.REPEAT_INSTRUCTIONS, VoiceCommandParser.parse("Ajuda").type)
        assertEquals(VoiceCommandType.CANCEL, VoiceCommandParser.parse("Pode encerrar").type)
    }

    @Test
    fun extractsAllergyNameOnlyWhenItWasSpoken() {
        val withName = VoiceCommandParser.parse("Tenho alergia a maleato de enalapril")
        val withoutName = VoiceCommandParser.parse("Cadastrar alergia")

        assertEquals(VoiceCommandType.REGISTER_ALLERGY, withName.type)
        assertEquals("maleato de enalapril", withName.argument)
        assertEquals(VoiceCommandType.REGISTER_ALLERGY, withoutName.type)
        assertNull(withoutName.argument)
    }

    @Test
    fun unknownSpeechNeverTriggersAFunction() {
        assertEquals(VoiceCommandType.UNKNOWN, VoiceCommandParser.parse("bom dia tudo bem").type)
        assertEquals(VoiceCommandType.UNKNOWN, VoiceCommandParser.parse("dipirona").type)
    }
}
