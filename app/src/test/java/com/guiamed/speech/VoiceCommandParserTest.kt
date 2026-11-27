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
        assertEquals(VoiceCommandType.CANCEL, VoiceCommandParser.parse("Voltar").type)
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
    fun understandsAllergyManagementRepeatReadingAndReminders() {
        assertEquals(VoiceCommandType.LIST_ALLERGIES, VoiceCommandParser.parse("Quais são minhas alergias?").type)
        assertEquals(VoiceCommandType.LIST_ALLERGIES, VoiceCommandParser.parse("Listar alergias").type)

        val removeWithName = VoiceCommandParser.parse("Remover alergia a dipirona")
        assertEquals(VoiceCommandType.REMOVE_ALLERGY, removeWithName.type)
        assertEquals("dipirona", removeWithName.argument)

        val removeWithArticle = VoiceCommandParser.parse("Apagar a alergia dipirona")
        assertEquals(VoiceCommandType.REMOVE_ALLERGY, removeWithArticle.type)
        assertEquals("dipirona", removeWithArticle.argument)

        val removeWithoutName = VoiceCommandParser.parse("Apagar alergia")
        assertEquals(VoiceCommandType.REMOVE_ALLERGY, removeWithoutName.type)
        assertNull(removeWithoutName.argument)

        assertEquals(VoiceCommandType.REPEAT_LAST_READING, VoiceCommandParser.parse("Repetir última leitura").type)
        assertEquals(VoiceCommandType.REPEAT_LAST_READING, VoiceCommandParser.parse("Ouvir de novo").type)

        assertEquals(VoiceCommandType.CREATE_REMINDER, VoiceCommandParser.parse("Criar lembrete").type)
        assertEquals(VoiceCommandType.CREATE_REMINDER, VoiceCommandParser.parse("Criar alarme às 8 horas").type)
    }

    @Test
    fun unknownSpeechNeverTriggersAFunction() {
        assertEquals(VoiceCommandType.UNKNOWN, VoiceCommandParser.parse("bom dia tudo bem").type)
        assertEquals(VoiceCommandType.UNKNOWN, VoiceCommandParser.parse("dipirona").type)
    }

    @Test
    fun standbyModeDetectsDirectCommandsDistinguishesCancelFromPauseVoice() {
        assertEquals(VoiceCommandType.READ_PACKAGE, VoiceCommandParser.parse("embalagem", strictStandby = true).type)
        assertEquals(VoiceCommandType.READ_PRESCRIPTION, VoiceCommandParser.parse("receita", strictStandby = true).type)
        assertEquals(
            VoiceCommandType.REPEAT_INSTRUCTIONS,
            VoiceCommandParser.parse("repetir instruções", strictStandby = true).type,
        )
        assertEquals(
            VoiceCommandType.UNKNOWN,
            VoiceCommandParser.parse("sair", strictStandby = true).type,
        )
        assertEquals(
            VoiceCommandType.CANCEL,
            VoiceCommandParser.parse("sair", strictStandby = false).type,
        )
        assertEquals(
            VoiceCommandType.UNKNOWN,
            VoiceCommandParser.parse("ajuda", strictStandby = true).type,
        )
        assertEquals(
            VoiceCommandType.PAUSE_VOICE,
            VoiceCommandParser.parse("pausar voz", strictStandby = true).type,
        )
        assertEquals(
            VoiceCommandType.PAUSE_VOICE,
            VoiceCommandParser.parse("silenciar voz", strictStandby = false).type,
        )
    }

    @Test
    fun partialDispatchAcceleratesAtomicCommandsWithoutTruncatingArgumentsOrPrefixes() {
        assertEquals(
            VoiceCommandType.READ_PRESCRIPTION,
            VoiceCommandParser.canDispatchFromPartial("ler receita", strictStandby = false)?.type,
        )
        assertEquals(
            VoiceCommandType.READ_PACKAGE,
            VoiceCommandParser.canDispatchFromPartial("outro remédio", strictStandby = false)?.type,
        )
        assertEquals(
            VoiceCommandType.LIST_ALLERGIES,
            VoiceCommandParser.canDispatchFromPartial("minhas alergias", strictStandby = true)?.type,
        )
        assertEquals(
            VoiceCommandType.CANCEL,
            VoiceCommandParser.canDispatchFromPartial("sair", strictStandby = false)?.type,
        )
        assertEquals(
            VoiceCommandType.PAUSE_VOICE,
            VoiceCommandParser.canDispatchFromPartial("parar de ouvir", strictStandby = false)?.type,
        )

        // Comandos com argumento e prefixos parciais não devem disparar antes do resultado final.
        assertNull(VoiceCommandParser.canDispatchFromPartial("parar", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("encerrar", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("cadastrar alergia", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("remover alergia", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("criar lembrete", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("ler", strictStandby = false))
        assertNull(VoiceCommandParser.canDispatchFromPartial("repetir", strictStandby = false))
    }
}
