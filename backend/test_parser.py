import unittest
from datetime import date

from main import normalize_ocr, parse_recipe, parse_verification


class MedicationParserTests(unittest.TestCase):
    def test_corrects_common_ocr_errors(self):
        response = parse_verification("DIPIR0NA SODICA 5OO MG", [])
        self.assertEqual("Dipirona Sódica", response.nome)
        self.assertEqual("500mg", response.miligramas)

    def test_fuzzy_matches_nimesulida(self):
        response = parse_verification("NIMESULLDA 100 MG", [])
        self.assertEqual("Nimesulida", response.nome)
        self.assertEqual("100mg", response.miligramas)

    def test_keeps_ibuprofen_600_mg_and_does_not_invent_expiration(self):
        response = parse_verification("IBUPROFENO 600 MG caixa com 20 comprimidos", [])
        self.assertEqual("Ibuprofeno", response.nome)
        self.assertEqual("600mg", response.miligramas)
        self.assertIsNone(response.validade)
        self.assertFalse(response.vencido)

    def test_only_explicit_expiration_is_used(self):
        response = parse_verification(
            "IBUPROFENO 600 MG VALIDADE 10/2026",
            [],
            today=date(2026, 11, 4),
        )
        self.assertEqual("10/2026", response.validade)
        self.assertTrue(response.vencido)

    def test_partial_allergy_match_sets_danger(self):
        response = parse_verification("NOVALGINA 500 MG", ["dipirona"])
        self.assertTrue(response.alerta_alergia)
        self.assertTrue(response.perigo)

    def test_recipe_preserves_detected_dosage(self):
        response = parse_recipe("IBUPROFENO 600 MG 8/8H POR 5 DIAS APOS AS REFEICOES")
        self.assertEqual(1, len(response.medicamentos))
        self.assertEqual("600mg", response.medicamentos[0].dosagem)

    def test_normalization_fixes_numeric_context(self):
        self.assertIn("600 MG", normalize_ocr("Ibuprofeno 6OO mg"))

    def test_recognizes_rosuvastatin_from_front_package(self):
        response = parse_verification(
            "althaia rosuvastatlna calcica comprimido revestido 2O MG medicamento generico",
            [],
        )
        self.assertEqual("Rosuvastatina Cálcica", response.nome)
        self.assertEqual("20mg", response.miligramas)


if __name__ == "__main__":
    unittest.main()
