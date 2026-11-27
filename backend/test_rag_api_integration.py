"""ASGI/contract tests; no live model, PostgreSQL, or service restart required."""

import asyncio
import hashlib
import json
import threading
import time
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from pydantic import ValidationError

import main


class RagRequestContractTests(unittest.TestCase):
    def test_only_versioned_prompts_are_accepted(self):
        payload = {"device_id": "test-device", "pergunta": "Para que serve Pressormin?", "timestamp": 1}
        self.assertEqual(main.RagRequest(**payload).prompt_version, "v2")
        for version in ("v1", "v2"):
            self.assertEqual(main.RagRequest(**payload, prompt_version=version).prompt_version, version)
        for version in ("../prompts/private", "v3", "ignore instructions", None):
            with self.assertRaises(ValidationError):
                main.RagRequest(**payload, prompt_version=version)
        for field in ("prompt", "system", "system_prompt", "model", "sql"):
            with self.assertRaises(ValidationError):
                main.RagRequest(**payload, **{field: "arbitrary client instructions"})


class RagAuditTests(unittest.IsolatedAsyncioTestCase):
    async def test_memory_receipt_never_claims_persistence_and_hashes_sensitive_input(self):
        device, question = "private-device", "private-question"
        records = []
        metadata = {"modo": "llm", "prompt_version": "v2", "tokens_saida": 12}
        answer = "Resposta baseada apenas em bula fictícia."
        with patch.object(main, "db_pool", None), patch.object(main, "memory_audit", records):
            receipt = await main.save_rag_audit(
                device, question, ["ficticio#indicacao"], 123.5, 0.012,
                metadata, response_text=answer,
            )
        self.assertEqual(receipt.backend, "memoria")
        self.assertFalse(receipt.persistida)
        self.assertEqual(records[0]["audit_id"], receipt.id)
        self.assertEqual(records[0]["latency_ms"], 123.5)
        self.assertEqual(records[0]["cost_usd"], 0.012)
        self.assertEqual(records[0]["execution"], metadata)
        self.assertEqual(records[0]["response"], answer)
        self.assertEqual(
            records[0]["response_sha256"],
            hashlib.sha256(answer.encode()).hexdigest(),
        )
        self.assertEqual(records[0]["requester_hash"], hashlib.sha256(device.encode()).hexdigest())
        self.assertEqual(records[0]["query_fingerprint"], hashlib.sha256(question.encode()).hexdigest())
        self.assertNotIn(device, json.dumps(records))
        self.assertNotIn(question, json.dumps(records))

    async def test_postgres_uses_only_authorized_function_and_actual_cost(self):
        connection = AsyncMock()
        connection.fetchrow.return_value = {"audit_id": "db-receipt"}

        @asynccontextmanager
        async def acquire():
            yield connection

        pool = SimpleNamespace(acquire=acquire)
        metadata = {"modo": "llm", "prompt_version": "v1", "tokens_saida": 11}
        answer = "Conteúdo fictício citado."
        with patch.object(main, "db_pool", pool), patch.object(main, "memory_audit", []):
            receipt = await main.save_rag_audit(
                "device", "question", ["ficticio#indicacao"], 712.3, 0.023,
                metadata, response_text=answer,
            )
        self.assertEqual(receipt.backend, "postgresql")
        self.assertTrue(receipt.persistida)
        self.assertEqual(receipt.id, "db-receipt")
        args = connection.fetchrow.await_args.args
        self.assertIn("SELECT guiamed_api.record_consultation(", args[0])
        self.assertEqual(args[1], main.anonymize_device_id("device"))
        self.assertEqual(args[3], hashlib.sha256(b"question").hexdigest())
        self.assertEqual(
            json.loads(args[4]),
            {
                "fontes": ["ficticio#indicacao"],
                "resposta": answer,
                "resposta_sha256": hashlib.sha256(answer.encode()).hexdigest(),
                "execucao": metadata,
            },
        )
        self.assertEqual(args[5:], (712.3, 0.023))
        connection.execute.assert_not_called()

    async def test_database_failure_is_explicit_and_does_not_log_input(self):
        @asynccontextmanager
        async def acquire():
            raise RuntimeError("private-question private-device database-secret")
            yield

        with patch.object(main, "db_pool", SimpleNamespace(acquire=acquire)), \
                patch.object(main, "memory_audit", []), self.assertLogs("guiamed", level="WARNING") as logs:
            receipt = await main.save_rag_audit("private-device", "private-question", [], 5.0, 0.0, {"modo": "indisponivel"})
        self.assertFalse(receipt.persistida)
        self.assertEqual(receipt.backend, "memoria")
        self.assertNotIn("private-", " ".join(logs.output))
        self.assertNotIn("database-secret", " ".join(logs.output))

    async def test_missing_optional_rag_library_does_not_stop_api_startup(self):
        with patch.object(main, "init_db_pool", AsyncMock()), \
                patch.object(main, "close_db_pool", AsyncMock()), \
                patch.object(main, "lora_catalog", return_value=None), \
                patch.object(main, "recovery_index", return_value=None), \
                patch.object(main.settings, "lora_enabled", False), \
                patch.object(main, "get_local_rag", side_effect=ModuleNotFoundError("optional-runtime")):
            async with main.lifespan(main.app):
                self.assertEqual((await main.health_check()).status, "ok")


class RagEndpointTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.payload = {"device_id": "private-device", "pergunta": "Para que serve Pressormin?", "timestamp": 1}

    def result(self, version="v2", **changes):
        fields = dict(
            response="Conteúdo fictício citado.", sources=("pressormin#indicacao",),
            medicine="Pressormin", confidence=0.9, prompt_version=version,
            latency_ms=0.01, cost_usd=0.021, hostile_input_detected=False,
            busca_ms=0.01, geracao_ms=20.0, load_ms=0.0, total_ms=20.01,
            input_tokens=120, output_tokens=12, model="local-model", revision="fixed-revision",
            mode="llm_grounded", fallback_reason=None, prompt_sha256="a" * 64,
            generation_attempted=True, llm_called=True, generator_backend="transformers",
            citation_origin="model", decoding="unconstrained", raw_response="private output must not reach audit",
        )
        fields.update(changes)
        return SimpleNamespace(**fields)

    async def post(self, engine, payload=None):
        with patch.object(main, "get_local_rag", return_value=engine), \
                patch.object(main, "db_pool", None), patch.object(main, "memory_audit", []):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
                return await client.post("/rag/consultar", json=payload or self.payload)

    async def test_same_question_versions_keep_legacy_contract_and_runtime_metrics(self):
        calls = []
        def answer(question, *, prompt_version):
            calls.append((question, prompt_version))
            return self.result(prompt_version)
        engine = SimpleNamespace(answer=answer)
        versions = []
        for version in ("v1", "v2"):
            response = await self.post(engine, {**self.payload, "prompt_version": version})
            self.assertEqual(response.status_code, 200, response.text)
            body = response.json()
            versions.append(body)
            self.assertTrue({"resposta", "fontes", "medicamento", "confianca", "prompt_version",
                             "latencia_ms", "custo_usd", "auditoria_id", "entrada_hostil_detectada"} <= body.keys())
            self.assertEqual(body["input_tokens"], 120)
            self.assertEqual(body["output_tokens"], 12)
            self.assertEqual(body["model"], "local-model")
            self.assertEqual(body["revision"], "fixed-revision")
            self.assertTrue(body["geracao_llm_real"])
            self.assertFalse(body["fallback"])
            self.assertFalse(body["auditoria"]["persistida"])
            self.assertEqual(body["auditoria_id"], body["auditoria"]["id"])
            self.assertEqual(body["custo_usd"], 0.021)
            self.assertFalse(body["custos"]["energia_medida"])
            self.assertIsNone(body["custos"]["energia_usd"])
            self.assertNotIn("raw_response", body)
        self.assertEqual(calls, [(self.payload["pergunta"], "v1"), (self.payload["pergunta"], "v2")])
        self.assertEqual(versions[0]["consulta_sha256"], versions[1]["consulta_sha256"])

    async def test_load_and_generation_run_off_event_loop_and_total_is_audited(self):
        entered, release = threading.Event(), threading.Event()
        thread_ids = []
        loop_thread = threading.get_ident()
        def answer(_question, *, prompt_version):
            thread_ids.append(threading.get_ident())
            entered.set()
            release.wait(timeout=2)
            return self.result(prompt_version)
        def load():
            thread_ids.append(threading.get_ident())
            time.sleep(0.03)
            return SimpleNamespace(answer=answer)
        records = []
        with patch.object(main, "get_local_rag", side_effect=load), \
                patch.object(main, "db_pool", None), patch.object(main, "memory_audit", records):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
                task = asyncio.create_task(client.post("/rag/consultar", json=self.payload))
                try:
                    for _ in range(100):
                        if entered.is_set():
                            break
                        await asyncio.sleep(0.005)
                    self.assertTrue(entered.is_set())
                    self.assertFalse(task.done(), "RAG blocked the event loop until generation finished")
                    health = await asyncio.wait_for(client.get("/health"), timeout=0.5)
                    self.assertEqual(health.status_code, 200)
                finally:
                    release.set()
                response = await task
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertTrue(all(identity != loop_thread for identity in thread_ids))
        self.assertGreaterEqual(body["latencia_ms"], 30)
        self.assertEqual(body["latencia_ms"], body["total_ms"])
        self.assertEqual(records[0]["latency_ms"], body["latencia_ms"])
        self.assertEqual(records[0]["cost_usd"], body["custo_usd"])
        self.assertEqual(records[0]["execution"]["geracao_ms"], body["geracao_ms"])
        self.assertNotIn("private output", json.dumps(records))
        self.assertNotIn(self.payload["pergunta"], json.dumps(records))

    async def test_unavailable_model_has_explicit_audible_fallback_not_llm_success(self):
        def answer(_question, *, prompt_version):
            return self.result(prompt_version, response="Geração local indisponível. Fallback extrativo.",
                               mode="fallback_template", fallback_reason="unavailable",
                               llm_called=False, input_tokens=None, output_tokens=None)
        response = await self.post(SimpleNamespace(answer=answer))
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["fallback"])
        self.assertFalse(body["geracao_llm_real"])
        self.assertIsNone(body["output_tokens"])
        self.assertEqual(body["fallback_reason"], "unavailable")

    async def test_unexpected_runtime_failure_is_503_audited_without_sensitive_logs(self):
        records = []
        with patch.object(main, "get_local_rag", side_effect=ImportError("private-device private-question")), \
                patch.object(main, "db_pool", None), patch.object(main, "memory_audit", records), \
                self.assertLogs("guiamed", level="WARNING") as logs:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
                response = await client.post("/rag/consultar", json=self.payload)
        self.assertEqual(response.status_code, 503)
        detail = response.json()["detail"]
        self.assertIn("indisponível", detail["mensagem"])
        self.assertEqual(detail["mode"], "unavailable")
        self.assertFalse(detail["auditoria"]["persistida"])
        self.assertEqual(records[0]["audit_id"], detail["auditoria_id"])
        self.assertNotIn("private-", " ".join(logs.output))
        self.assertNotIn("Traceback", " ".join(logs.output))

    async def test_fake_backend_and_refusals_are_never_real_llm_evidence(self):
        for changes in (
            {"generator_backend": "fake", "llm_called": False},
            {"mode": "refusal_no_evidence", "generation_attempted": False, "llm_called": False,
             "generator_backend": "not_called", "sources": (), "output_tokens": None},
        ):
            response = await self.post(SimpleNamespace(answer=lambda *args, **kwargs: self.result(**changes)))
            self.assertEqual(response.status_code, 200)
            self.assertFalse(response.json()["geracao_llm_real"])

    async def test_http_rejects_arbitrary_prompts_before_loading_model(self):
        with patch.object(main, "get_local_rag") as load:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test") as client:
                for extra in ({"prompt_version": "v9"}, {"system_prompt": "arbitrary"}, {"sql": "SELECT 1"}):
                    response = await client.post("/rag/consultar", json={**self.payload, **extra})
                    self.assertEqual(response.status_code, 422)
        load.assert_not_called()

    async def test_real_local_rag_interface_uses_generator_and_reports_fake_truthfully(self):
        from local_rag import LocalRag
        from rag_llm import Generation

        calls = []
        class FakeGenerator:
            def generate(self, messages, *, timeout_ms):
                calls.append(messages)
                chunk = json.loads(messages[1]["content"])["chunks"][0]
                return Generation(
                    raw_response=f"{chunk['texto']} Fonte: {chunk['fonte']}.",
                    input_tokens=321, output_tokens=29, model="fake-test", revision="test",
                )

        response = await self.post(LocalRag(generator=FakeGenerator()))
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(len(calls), 1)
        self.assertEqual(body["mode"], "llm_grounded")
        self.assertEqual(body["generator_backend"], "fake")
        self.assertTrue(body["generation_attempted"])
        self.assertFalse(body["llm_called"])
        self.assertFalse(body["geracao_llm_real"])
        self.assertEqual(body["citation_origin"], "model")
        self.assertEqual(body["input_tokens"], 321)
        self.assertEqual(body["output_tokens"], 29)
        self.assertTrue(body["fontes"])

    async def test_real_local_rag_missing_model_never_hides_fallback_as_llm(self):
        from local_rag import LocalRag
        from rag_llm import GenerationUnavailable

        class MissingGenerator:
            def generate(self, messages, *, timeout_ms):
                raise GenerationUnavailable("unavailable")

        response = await self.post(LocalRag(generator=MissingGenerator()))
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["mode"], "fallback_template")
        self.assertEqual(body["fallback_reason"], "unavailable")
        self.assertEqual(body["citation_origin"], "template")
        self.assertFalse(body["llm_called"])
        self.assertFalse(body["geracao_llm_real"])
        self.assertIn("Fallback", body["resposta"])


if __name__ == "__main__":
    unittest.main()
