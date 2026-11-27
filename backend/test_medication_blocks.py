"""Dose and frequency must belong to the identified medication block."""

import unittest
from datetime import date
from unittest.mock import Mock

from main import parse_recipe, parse_verification


class MedicationBlockTests(unittest.TestCase):
    markers = ("", "2) ", "2. ", "(2) ", "[2] ", "2 - ", "2: ",
               "A) ", "- ", "* ", "• ", "● ", "▪ ", "‣ ", "– ", "— ")

    def test_unknown_headers_never_supply_previous_dose_or_frequency(self):
        for marker in self.markers:
            for unknown in (
                "Pressormin 900 MG A CADA 6 HORAS",
                "Pressormin\n900 MG A CADA 6 HORAS",
                "Pressormin\n900 MG\nA CADA 6 HORAS",
            ):
                with self.subTest(marker=marker, unknown=unknown):
                    result = parse_recipe("1) OMEPRAZOL\n" + marker + unknown)
                    self.assertEqual([m.nome for m in result.medicamentos], ["Omeprazol"])
                    self.assertEqual(result.medicamentos[0].dosagem, "dosagem não identificada")
                    self.assertEqual(result.medicamentos[0].frequencia, "frequência não identificada")

    def test_own_dose_and_frequency_survive_unknown_neighbor(self):
        for marker in self.markers:
            with self.subTest(marker=marker):
                result = parse_recipe(
                    "OMEPRAZOL\n20 MG\nUMA VEZ AO DIA EM JEJUM\n"
                    + marker + "Pressormin\n900 MG A CADA 6 HORAS"
                )
                medicine = result.medicamentos[0]
                self.assertEqual(medicine.dosagem, "20mg")
                self.assertEqual(medicine.frequencia, "uma vez ao dia")
                self.assertIn("em jejum", medicine.instrucoes)

    def test_missing_frequency_cannot_come_from_unknown_next_item(self):
        for marker in self.markers:
            with self.subTest(marker=marker):
                medicine = parse_recipe(
                    "OMEPRAZOL 20 MG\n" + marker + "Pressormin\nA CADA 6 HORAS"
                ).medicamentos[0]
                self.assertEqual(medicine.dosagem, "20mg")
                self.assertEqual(medicine.frequencia, "frequência não identificada")

    def test_recognized_numbered_items_keep_separate_multiline_contexts(self):
        for marker in self.markers:
            with self.subTest(marker=marker):
                result = parse_recipe(
                    marker + "OMEPRAZOL\n20 MG\nUMA VEZ AO DIA\n"
                    + marker + "PARACETAMOL\n750 MG\nA CADA 8 HORAS"
                )
                self.assertEqual([m.nome for m in result.medicamentos], ["Omeprazol", "Paracetamol"])
                self.assertEqual([m.dosagem for m in result.medicamentos], ["20mg", "750mg"])
                self.assertEqual([m.frequencia for m in result.medicamentos],
                                 ["uma vez ao dia", "a cada 8 horas"])

    def test_usage_bullets_and_labels_are_continuations(self):
        for dose, usage in (("- 20 MG", "• TOMAR 1 COMPRIMIDO A NOITE"),
                            ("DOSAGEM: 20 MG", "FREQUENCIA: UMA VEZ AO DIA"),
                            ("20 MG", "POR 5 DIAS")):
            with self.subTest(dose=dose, usage=usage):
                medicine = parse_recipe("OMEPRAZOL\n" + dose + "\n" + usage).medicamentos[0]
                self.assertEqual(medicine.dosagem, "20mg")
                if "NOITE" in usage:
                    self.assertEqual(medicine.frequencia, "a noite")
                if "FREQUENCIA" in usage:
                    self.assertEqual(medicine.frequencia, "uma vez ao dia")
                if "DIAS" in usage:
                    self.assertEqual(medicine.duracao, "5 dias")

    def test_known_item_after_unknown_block_does_not_take_earlier_strength(self):
        result = parse_recipe(
            "1) Pressormin\n900 MG A CADA 6 HORAS\n"
            "2) OMEPRAZOL\n20 MG\nUMA VEZ AO DIA"
        )
        self.assertEqual([m.dosagem for m in result.medicamentos], ["20mg"])
        self.assertEqual(result.medicamentos[0].frequencia, "uma vez ao dia")

    def test_unknown_header_after_semicolon_is_a_boundary(self):
        result = parse_recipe("OMEPRAZOL; - Pressormin; 900 MG A CADA 6 HORAS")
        self.assertEqual(result.medicamentos[0].dosagem, "dosagem não identificada")
        self.assertEqual(result.medicamentos[0].frequencia, "frequência não identificada")

    def test_inline_item_markers_end_the_block_after_ocr_flattens_lines(self):
        for marker in self.markers[1:]:
            with self.subTest(marker=marker):
                text = "OMEPRAZOL " + marker + "Pressormin 900 MG A CADA 6 HORAS"
                item = parse_recipe(text).medicamentos[0]
                self.assertEqual(item.dosagem, "dosagem não identificada")
                self.assertEqual(item.frequencia, "frequência não identificada")
                self.assertIsNone(parse_verification(text, []).miligramas)

    def test_flattened_known_items_keep_their_own_strengths(self):
        items = parse_recipe("1) OMEPRAZOL 20 MG 2) PARACETAMOL 750 MG").medicamentos
        self.assertEqual([item.dosagem for item in items], ["20mg", "750mg"])

    def test_unknown_word_cannot_match_only_the_prefix_of_a_usage_verb(self):
        for unknown in ("Tomarmin", "Usarmin", "Aplicarmin", "Ingerirmin"):
            with self.subTest(unknown=unknown):
                medicine = parse_recipe(
                    "OMEPRAZOL\n" + unknown + "\n900 MG A CADA 6 HORAS"
                ).medicamentos[0]
                self.assertEqual(medicine.dosagem, "dosagem não identificada")
                self.assertEqual(medicine.frequencia, "frequência não identificada")

    def test_preface_on_same_line_never_supplies_recipe_dose_or_frequency(self):
        for own, dose, frequency in (("20 MG A NOITE", "20mg", "a noite"),
                                     ("", "dosagem não identificada", "frequência não identificada")):
            with self.subTest(own=own):
                medicine = parse_recipe(
                    "OBSERVACAO 100 MG A CADA 6 HORAS OMEPRAZOL " + own
                ).medicamentos[0]
                self.assertEqual(medicine.dosagem, dose)
                self.assertEqual(medicine.frequencia, frequency)

    def test_long_package_ignores_preface_and_keeps_safety(self):
        for separator in ("\n", " "):
            with self.subTest(separator=separator):
                text = separator.join([
                    "OBSERVACAO 100 MG A CADA 6 HORAS",
                    *("TEXTO LEGAL USO ORAL" for _ in range(120)),
                    "NOVALGINA 5OO MG VAL: 10/26",
                ])
                result = parse_verification(text, ["metamizol"], today=date(2026, 11, 26))
                self.assertEqual(result.nome, "Dipirona Sódica")
                self.assertEqual(result.miligramas, "500mg")
                self.assertEqual(result.validade, "10/2026")
                self.assertTrue(result.vencido and result.alerta_alergia and result.perigo)

    def test_package_joins_only_its_own_multiline_strength(self):
        result = parse_verification(
            "OBSERVACAO 100 MG\nOMEPRAZOL\n20 MG\nVAL: 12/27", []
        )
        self.assertEqual(result.miligramas, "20mg")
        self.assertEqual(result.validade, "12/2027")

    def test_package_without_own_strength_does_not_borrow_preface_or_neighbor(self):
        for text in ("OBSERVACAO 100 MG\nOMEPRAZOL",
                     "OBSERVACAO 100 MG OMEPRAZOL",
                     "OMEPRAZOL\n2) Pressormin\n900 MG"):
            with self.subTest(text=text):
                result = parse_verification(text, [])
                self.assertEqual(result.nome, "Omeprazol")
                self.assertIsNone(result.miligramas)

    def test_lora_recovered_identity_uses_own_block_after_preface(self):
        result = parse_verification(
            "OBSERVACAO 100 MG\nNO VAL GI NA\n5OO MG VTO: 12/27", ["dipirona"],
            suggest_name=Mock(return_value="Novalgina"),
        )
        self.assertEqual(result.nome, "Dipirona Sódica")
        self.assertEqual(result.miligramas, "500mg")
        self.assertTrue(result.alerta_alergia and result.perigo)

    def test_ambiguous_names_still_refused_and_strength_is_not_assigned(self):
        result = parse_verification("NOVALGINA 500 MG + METFORMINA 850 MG", [])
        self.assertTrue(result.nome.startswith("Medicamento não identificado"))
        self.assertIsNone(result.miligramas)

    def test_package_descriptors_still_stop_before_unknown_neighbor(self):
        for text in (
            "OMEPRAZOL\nMEDICAMENTO GENERICO\n2) Pressormin\n900 MG",
            "OMEPRAZOL\n10\nPressormin\n900 MG",
            "OBSERVACAO 100 MG\nOMEPRAZOL\nMEDICAMENTO GENERICO",
        ):
            with self.subTest(text=text):
                result = parse_verification(text, [])
                self.assertEqual(result.nome, "Omeprazol")
                self.assertIsNone(result.miligramas)


if __name__ == "__main__":
    unittest.main()
