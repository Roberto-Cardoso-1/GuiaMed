package com.guiamed.ocr

import com.guiamed.data.MedicamentoReceita
import com.guiamed.data.ReceitaResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class PrescriptionReadPipelineTest {
    private fun partial(name: String = "Ibuprofeno") = ReceitaResponse(
        listOf(MedicamentoReceita(name, "500mg", "frequência não identificada", null, null)),
        "", emptyList(), null,
    )

    @Test fun timeoutKeepsFirstTileAndNeverRecapturesTheSheet() = runBlocking {
        var originals = 0
        val result = readPrescriptionWithinBudget(
            ReadBudget(250), 0,
            original = { originals++; partial() },
            enhanced = { publish -> publish(partial("Amoxicilina")); awaitCancellation() },
            remote = { fail("No remaining budget for HTTP"); it },
        )!!
        assertEquals(1, originals)
        assertEquals(listOf("Ibuprofeno", "Amoxicilina"), result.medicamentos.map { it.nome })
        assertTrue(result.mensagem!!.contains("Leitura parcial"))
    }

    @Test fun keepsUsefulPartialAndNetworkDiagnostics() = runBlocking {
        val result = readPrescriptionWithinBudget(
            ReadBudget(2_000), 0, { partial() }, { null },
            { it.copy(mensagem = "Sem conexão com o servidor. Usando processamento local.") },
        )!!
        assertEquals("500mg", result.medicamentos.single().dosagem)
        assertTrue(result.mensagem!!.contains("Sem conexão"))
    }

    @Test fun completeItemCanConsultUnrecognizedNeighborWithoutExtraOcr() = runBlocking {
        var calls = 0
        val complete = partial().copy(medicamentos = listOf(partial().medicamentos.single().copy(frequencia = "8 horas")))
        readPrescriptionWithinBudget(
            ReadBudget(2_000), 0, { complete },
            { fail("Unnecessary OCR"); null }, { calls++; it }, needsRemote = { true },
        )
        assertEquals(1, calls)
        readPrescriptionWithinBudget(
            ReadBudget(2_000), 0, { complete },
            { fail("Unnecessary OCR"); null }, { fail("Unnecessary HTTP"); it },
        )
        Unit
    }

    @Test fun userCancellationDoesNotReturnStalePartial() = runBlocking {
        assertNull(withTimeoutOrNull(100) {
            readPrescriptionWithinBudget(ReadBudget(5_000), 0, { partial() }, { awaitCancellation() }, { it })
        })
    }
}
