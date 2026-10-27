"""
Preenche dbo.bula_chunk.embedding com OpenAI text-embedding-3-small (1536 dims).

- Lê chunks com embedding NULL e atualiza em lote.
- A chave vem do AMBIENTE (OPENAI_API_KEY) - nunca fica no código/repositório.
- Custo estimado da base inicial: 144 chunks x ~400 tokens = 57,6k tokens
  -> ~US$ 0,0012 (US$ 0,02 / 1M tokens). Irrelevante; ver docs/arquitetura.md.

Uso:
    pip install openai pyodbc
    set OPENAI_API_KEY=sk-...        (Windows)
    set GUIAMED_SQL=Driver={ODBC Driver 18 for SQL Server};Server=localhost,1433;Database=GuiaMed;UID=sa;PWD=GuiaMed!Dev123;TrustServerCertificate=yes
    python scripts/embed_chunks.py
"""
import os
import sys

import pyodbc
from openai import OpenAI

BATCH = 32


def main() -> None:
    api_key = os.environ.get("OPENAI_API_KEY")
    conn_str = os.environ.get("GUIAMED_SQL")
    if not api_key or not conn_str:
        sys.exit("Defina OPENAI_API_KEY e GUIAMED_SQL no ambiente (nunca no código).")

    client = OpenAI(api_key=api_key)
    cn = pyodbc.connect(conn_str)
    cur = cn.cursor()

    cur.execute("SELECT id, conteudo FROM dbo.bula_chunk WHERE embedding IS NULL")
    rows = cur.fetchall()
    print(f"{len(rows)} chunks sem embedding.")
    for i in range(0, len(rows), BATCH):
        lote = rows[i : i + BATCH]
        resp = client.embeddings.create(
            model="text-embedding-3-small",
            input=[r.conteudo for r in lote],
        )
        for row, emb in zip(lote, resp.data):
            cur.execute(
                "UPDATE dbo.bula_chunk SET embedding = CAST(? AS VECTOR(1536)) WHERE id = ?",
                ("[" + ",".join(f"{v:.6g}" for v in emb.embedding) + "]", row.id),
            )
        cn.commit()
        print(f"  ok: {i + len(lote)}/{len(rows)}")

    print("Embeddings atualizados. Busca vetorial pronta (usp_buscar_bulas).")


if __name__ == "__main__":
    main()
