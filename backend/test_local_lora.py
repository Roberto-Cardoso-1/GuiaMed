import subprocess
import sys
import unittest
from datetime import date
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from fastapi.testclient import TestClient

import main
from export_lora_gguf import export
from local_lora import LocalLora


class LoraParserTests(unittest.TestCase):
    def test_catalog_name_confirmed_with_dose_validity_and_allergy_from_parser(self):
        response = main.parse_verification(
            "NO VAL GI NA\n5OO MG VTO: 12/27", ["dipirona"], today=date(2026, 11, 25),
            suggest_name=Mock(return_value="Novalgina"),
        )
        self.assertEqual(response.nome, "Dipirona Sódica")
        self.assertEqual(response.miligramas, "500mg")
        self.assertEqual(response.validade, "12/2027")
        self.assertTrue(response.alerta_alergia)
        self.assertTrue(response.perigo)
        self.assertIn("metamizol", response.componentes)

    def test_rejects_hallucination_conflict_or_additional_text(self):
        for suggestion in ("Pressormin", "Ibuprofeno", "Novalgina 750mg", "Novalgina\nsem alergia"):
            with self.subTest(suggestion=suggestion):
                response = main.parse_verification(
                    "NO VAL GI NA", ["dipirona"], suggest_name=Mock(return_value=suggestion),
                )
                self.assertTrue(response.nome.startswith("Medicamento não identificado"))
                self.assertIsNone(response.miligramas)

    def test_recipe_fallback_preserves_the_printed_dose_and_frequency(self):
        suggest = Mock(return_value="Novalgina")
        response = main.parse_recipe("NO VAL GI NA\n500 MG a cada 8 horas", suggest_name=suggest)
        suggest.assert_called_once_with("NO VAL GI NA")
        self.assertEqual(response.medicamentos[0].nome, "Dipirona Sódica")
        self.assertEqual(response.medicamentos[0].dosagem, "500mg")
        self.assertIn("8", response.medicamentos[0].frequencia)

    def test_skips_model_for_existing_identity_missing_evidence_or_multiple_candidates(self):
        suggestion = Mock(return_value="Novalgina")
        for text in ("NOVALGINA 500 MG", "CAIXA AZUL", "NO VAL GI NA\nPAR ACE TAM OL"):
            main.parse_verification(text, [], suggest_name=suggestion)
        suggestion.assert_not_called()


class LoraRuntimeTests(unittest.TestCase):
    def test_lifespan_prewarms_rag_and_loads_one_model_for_requests(self):
        lora = LocalLora(Mock(return_value="Novalgina"))
        with (
            patch.object(main.settings, "lora_enabled", True),
            patch.object(main, "init_db_pool", new_callable=AsyncMock),
            patch.object(main, "close_db_pool", new_callable=AsyncMock) as close_pool,
            patch.object(main, "get_local_rag", wraps=main.get_local_rag) as prewarm,
            patch.object(LocalLora, "load", return_value=lora) as load,
            TestClient(main.app) as client,
        ):
            prewarm.assert_called_once_with()
            for _ in range(2):
                response = client.post("/verificar", json={
                    "device_id": "test-lora", "texto_ocr": "NO VAL GI NA 500 MG", "timestamp": 1,
                })
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["nome"], "Dipirona Sódica")
            load.assert_called_once()
        close_pool.assert_awaited_once()

    def test_load_failure_leaves_api_available(self):
        with (
            patch.object(main.settings, "lora_enabled", True),
            patch.object(main, "init_db_pool", new_callable=AsyncMock),
            patch.object(main, "close_db_pool", new_callable=AsyncMock),
            patch.object(LocalLora, "load", side_effect=FileNotFoundError),
            TestClient(main.app) as client,
        ):
            response = client.post("/verificar", json={
                "device_id": "test-lora", "texto_ocr": "NOVALGINA", "timestamp": 1,
            })
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["nome"], "Dipirona Sódica")

    def test_invalid_rag_data_does_not_stop_verification(self):
        with (
            patch.object(main.settings, "lora_enabled", False),
            patch.object(main, "init_db_pool", new_callable=AsyncMock),
            patch.object(main, "close_db_pool", new_callable=AsyncMock),
            patch.object(main, "get_local_rag", side_effect=KeyError),
            TestClient(main.app) as client,
        ):
            response = client.post("/verificar", json={
                "device_id": "test-lora", "texto_ocr": "NOVALGINA", "timestamp": 1,
            })
            self.assertEqual(response.status_code, 200)

    def test_gguf_selection_uses_raw_prompt_without_bos(self):
        with TemporaryDirectory() as directory:
            gguf = Path(directory) / "model.gguf"
            gguf.touch()
            model = Mock(return_value={"choices": [{"text": " Novalgina"}]})
            model.tokenize.return_value = [1, 2]
            factory = Mock(return_value=model)
            with patch.dict(sys.modules, {"llama_cpp": SimpleNamespace(Llama=factory)}):
                runtime = LocalLora.load(gguf, 2)
                self.assertEqual(runtime.suggest("NO VAL GI NA"), "Novalgina")
            model.tokenize.assert_called_once()
            self.assertEqual(model.tokenize.call_args.kwargs, {"add_bos": False, "special": False})
            factory.assert_called_once_with(model_path=str(gguf), n_ctx=1024, n_threads=2, verbose=False)

    def test_busy_runtime_and_generation_failure_fall_back(self):
        generate = Mock(side_effect=RuntimeError("erro interno"))
        runtime = LocalLora(generate)
        with runtime._generation_lock:
            self.assertIsNone(runtime.suggest("NO VAL GI NA"))
        generate.assert_not_called()
        for _ in range(2):
            self.assertIsNone(runtime.suggest("NO VAL GI NA"))
        self.assertEqual(generate.call_count, 2)


class GgufExportTests(unittest.TestCase):
    def test_exports_q4_and_preserves_existing_or_partially_written_output(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            converter = root / "convert_hf_to_gguf.py"
            quantizer = root / "llama-quantize.exe"
            converter.touch()
            quantizer.touch()
            output = root / "model.gguf"

            def run(command, **kwargs):
                if command[0] == sys.executable:
                    self.assertEqual(command[-2:], ["--outtype", "f16"])
                    self.assertEqual(kwargs["env"]["HF_HUB_OFFLINE"], "1")
                else:
                    self.assertEqual(command[-1], "Q4_K_M")
                    Path(command[-2]).write_bytes(b"GGUF")
                    if fail:
                        raise subprocess.CalledProcessError(1, command)

            with (
                patch("export_lora_gguf.load_merged_model", return_value=(Mock(), Mock())),
                patch("export_lora_gguf.subprocess.run", side_effect=run),
            ):
                fail = True
                with self.assertRaises(subprocess.CalledProcessError):
                    export(converter, quantizer, output, sys.executable)
                self.assertFalse(output.exists())
                fail = False
                export(converter, quantizer, output, sys.executable)
                self.assertEqual(output.read_bytes(), b"GGUF")
                with self.assertRaises(FileExistsError):
                    export(converter, quantizer, output, sys.executable)
