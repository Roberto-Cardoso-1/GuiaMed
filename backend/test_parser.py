import unittest
from datetime import date

from main import load_dcb_catalog, normalize_ocr, parse_recipe, parse_verification


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

    def test_reads_common_expiration_label_from_package_bottom(self):
        response = parse_verification(
            "ATORVASTATINA 20 MG VTO: 12/27",
            [],
            today=date(2026, 11, 4),
        )
        self.assertEqual("12/2027", response.validade)
        self.assertFalse(response.vencido)

    def test_partial_allergy_match_sets_danger(self):
        response = parse_verification("NOVALGINA 500 MG", ["dipirona"])
        self.assertTrue(response.alerta_alergia)
        self.assertTrue(response.perigo)

    def test_recipe_preserves_detected_dosage(self):
        response = parse_recipe("IBUPROFENO 600 MG 8/8H POR 5 DIAS APOS AS REFEICOES")
        self.assertEqual(1, len(response.medicamentos))
        self.assertEqual("600mg", response.medicamentos[0].dosagem)

    def test_recipe_joins_name_dosage_and_instructions_on_adjacent_lines(self):
        response = parse_recipe(
            "ATORVASTATINA CALCICA\n"
            "20 MG\n"
            "TOMAR 1 COMPRIMIDO A NOITE DURANTE 30 DIAS"
        )
        self.assertEqual(1, len(response.medicamentos))
        medicine = response.medicamentos[0]
        self.assertEqual("atorvastatina cálcica", medicine.nome)
        self.assertEqual("20mg", medicine.dosagem)
        self.assertEqual("a noite", medicine.frequencia)
        self.assertEqual("30 dias", medicine.duracao)
        self.assertIn("tomar 1 comprimido", medicine.instrucoes or "")

    def test_recipe_stops_context_before_next_medicine(self):
        response = parse_recipe(
            "PARACETAMOL\n500 MG\nA CADA 8 HORAS\n"
            "OMEPRAZOL\n20 MG\nUMA VEZ AO DIA EM JEJUM"
        )
        self.assertEqual(2, len(response.medicamentos))
        self.assertEqual("500mg", response.medicamentos[0].dosagem)
        self.assertEqual("20mg", response.medicamentos[1].dosagem)
        self.assertEqual("uma vez ao dia", response.medicamentos[1].frequencia)

    def test_normalization_fixes_numeric_context(self):
        self.assertIn("600 MG", normalize_ocr("Ibuprofeno 6OO mg"))

    def test_recognizes_rosuvastatin_from_front_package(self):
        response = parse_verification(
            "althaia rosuvastatlna calcica comprimido revestido 2O MG medicamento generico",
            [],
        )
        self.assertEqual("Rosuvastatina Cálcica", response.nome)
        self.assertEqual("20mg", response.miligramas)

    def test_loads_broad_official_anvisa_catalog(self):
        exact_index, _ = load_dcb_catalog()
        self.assertGreater(len(exact_index), 10_000)
        self.assertIn("ATORVASTATINA", exact_index)

    def test_recognizes_anvisa_dcb_outside_curated_catalog(self):
        response = parse_verification("ATORVASTATINA CALCICA 40 MG", [])
        self.assertEqual("atorvastatina cálcica", response.nome)
        self.assertEqual("40mg", response.miligramas)

    def test_fuzzy_matches_anvisa_dcb_without_guessing_short_words(self):
        response = parse_verification("ATORVASTATLNA 20 MG", [])
        self.assertEqual("atorvastatina", response.nome)

        unknown = parse_verification("CAIXA AZUL USO ORAL 20 MG", [])
        self.assertEqual("Medicamento não identificado com segurança", unknown.nome)


if __name__ == "__main__":
    unittest.main()
