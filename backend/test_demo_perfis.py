import unittest
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, patch

from fastapi import HTTPException

import main


class DemoProfilesTest(unittest.IsolatedAsyncioTestCase):
    async def test_only_owner_queries_the_record_and_both_attempts_are_audited(self):
        connection = AsyncMock()
        connection.fetchval.side_effect = [True, "audit-a", "audit-b"]

        @asynccontextmanager
        async def transaction():
            yield

        @asynccontextmanager
        async def acquire():
            yield connection

        connection.transaction = transaction
        pool = type("Pool", (), {"acquire": staticmethod(acquire)})()
        with patch.dict(main.os.environ, {
            "GUIAMED_DEMO_TOKEN_A": "a" * 32,
            "GUIAMED_DEMO_TOKEN_B": "b" * 32,
        }), patch.object(main, "db_pool", pool):
            allowed = await main.consultar_alergia_demo("Bearer " + "a" * 32)
            with self.assertRaises(HTTPException) as denied:
                await main.consultar_alergia_demo("Bearer " + "b" * 32)
            with self.assertRaises(HTTPException) as unauthenticated:
                await main.consultar_alergia_demo("Bearer invalid")

        self.assertEqual(allowed, {"alergia_registrada": True, "auditoria_id": "audit-a"})
        self.assertEqual(denied.exception.status_code, 403)
        self.assertEqual(denied.exception.detail, {
            "mensagem": "Acesso negado", "auditoria_id": "audit-b",
        })
        self.assertEqual(unauthenticated.exception.status_code, 401)
        calls = connection.fetchval.await_args_list
        self.assertEqual(len(calls), 3)
        self.assertIn("check_allergy", calls[0].args[0])
        self.assertEqual(calls[1].args[2], "demo_alergia_permitida")
        self.assertEqual(calls[2].args[2], "demo_alergia_negada")
        self.assertNotEqual(calls[1].args[1], calls[2].args[1])
