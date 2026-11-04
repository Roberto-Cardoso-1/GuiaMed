import unittest

from main import (
    AlergiaResponse,
    ReceitaResponse,
    VerificarResponse,
    app,
    parse_recipe,
    parse_verification,
)


class ApiContractTests(unittest.TestCase):
    def test_routes_expose_the_v1_response_models(self) -> None:
        routes = {route.path: route for route in app.routes}
        self.assertIs(routes["/verificar"].response_model, VerificarResponse)
        self.assertIs(routes["/alergias"].response_model, AlergiaResponse)
        self.assertIs(routes["/receita"].response_model, ReceitaResponse)

    def test_allergy_and_verification_contract(self) -> None:
        payload = parse_verification("DIPIR0NA 5OO MG", ["dipirona"]).model_dump()
        self.assertEqual(
            set(payload),
            {
                "nome",
                "miligramas",
                "validade",
                "vencido",
                "alerta_alergia",
                "componentes",
                "posologia",
                "mensagem",
                "perigo",
            },
        )
        self.assertEqual(payload["nome"], "Dipirona Sódica")
        self.assertEqual(payload["miligramas"], "500mg")
        self.assertIsNone(payload["validade"])
        self.assertTrue(payload["alerta_alergia"])
        self.assertTrue(payload["perigo"])

        allergy_payload = AlergiaResponse(
            sucesso=True,
            mensagem="Alergia registrada.",
            alergias_registradas=["dipirona"],
        ).model_dump()
        self.assertEqual(
            set(allergy_payload),
            {"sucesso", "mensagem", "alergias_registradas"},
        )

    def test_recipe_contract_preserves_600_mg(self) -> None:
        payload = parse_recipe("IBUPROFENO 600 MG 8/8H POR 5 DIAS").model_dump()
        self.assertEqual(
            set(payload),
            {"medicamentos", "posologia_completa", "horarios", "mensagem"},
        )
        self.assertEqual(payload["medicamentos"][0]["nome"], "Ibuprofeno")
        self.assertEqual(payload["medicamentos"][0]["dosagem"], "600mg")


if __name__ == "__main__":
    unittest.main()
