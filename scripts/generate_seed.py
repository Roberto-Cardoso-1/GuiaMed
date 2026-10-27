"""
Gera db/03_seed.sql (carga inicial: medicamentos + chunks de bula) e
data/bulas_md/*.md (documentos legíveis usados como fonte do RAG).

Fonte: data/bulas.json (base 100% fictícia).

Uso:
    python scripts/generate_seed.py
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JSON_PATH = ROOT / "data" / "bulas.json"
SQL_OUT = ROOT / "db" / "03_seed.sql"
MD_DIR = ROOT / "data" / "bulas_md"

SECOES = ["indicacao", "posologia", "contraindicacoes", "efeitos_adversos", "interacoes", "advertencias"]

TITULO_SECAO = {
    "indicacao": "Indicação",
    "posologia": "Posologia",
    "contraindicacoes": "Contraindicações",
    "efeitos_adversos": "Efeitos adversos",
    "interacoes": "Interações medicamentosas",
    "advertencias": "Advertências",
}


def sql_str(s: str) -> str:
    """Escapa string para literal NVARCHAR no T-SQL."""
    return "N'" + s.replace("'", "''") + "'"


def main() -> None:
    data = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    bulas = data["bulas"]
    MD_DIR.mkdir(parents=True, exist_ok=True)

    linhas = [
        "-- ============================================================================",
        "-- GuiaMed | 03_seed.sql | Carga inicial (24 medicamentos fictícios + chunks)",
        "-- GERADO AUTOMATICAMENTE por scripts/generate_seed.py - nao editar a mao.",
        "-- Embeddings ficam NULL aqui; sao preenchidos por scripts/embed_chunks.py.",
        "-- Pre-requisito: rodar 01_ddl.sql antes.",
        "-- ============================================================================",
        "SET NOCOUNT ON;",
        "SET IDENTITY_INSERT dbo.medicamento ON;",
        "",
    ]

    for i, b in enumerate(bulas, start=1):
        linhas.append(
            "INSERT INTO dbo.medicamento "
            "(id, nome_comercial, principio_ativo, classe_terapeutica, tarja, forma_farmaceutica, apresentacao) VALUES "
            f"({i}, {sql_str(b['nome_comercial'])}, {sql_str(b['principio_ativo'])}, {sql_str(b['classe_terapeutica'])}, "
            f"{sql_str(b['tarja'])}, {sql_str(b['forma_farmaceutica'])}, {sql_str(b['apresentacao'])});"
        )

    linhas.append("SET IDENTITY_INSERT dbo.medicamento OFF;")
    linhas.append("")

    # Chunking escolhido: 1 chunk = 1 secao da bula (granularidade semantica,
    # mantem contexto completo da secao; tamanho ~400-700 caracteres).
    for i, b in enumerate(bulas, start=1):
        for secao in SECOES:
            linhas.append(
                "INSERT INTO dbo.bula_chunk (medicamento_id, secao, conteudo, embedding) VALUES "
                f"({i}, {sql_str(secao)}, {sql_str(b[secao])}, NULL);"
            )

    linhas.append("")
    linhas.append(f"-- Total esperado: {len(bulas)} medicamentos, {len(bulas) * len(SECOES)} chunks.")
    SQL_OUT.write_text("\n".join(linhas), encoding="utf-8")

    # Versao Markdown (fonte legível do RAG e evidencia da base de documentos)
    for i, b in enumerate(bulas, start=1):
        md = [
            f"# {b['nome_comercial']} ({b['forma_farmaceutica']})",
            "",
            f"**Princípio ativo:** {b['principio_ativo']}  ",
            f"**Classe terapêutica:** {b['classe_terapeutica']}  ",
            f"**Tarja:** {b['tarja']}  ",
            f"**Apresentação:** {b['apresentacao']}  ",
            "",
            "> ⚠️ Bula fictícia criada para fins didáticos (GuiaMed). Não é um medicamento real.",
            "",
        ]
        for secao in SECOES:
            md.append(f"## {TITULO_SECAO[secao]}")
            md.append("")
            md.append(b[secao])
            md.append("")
        (MD_DIR / f"{i:02d}-{b['nome_comercial'].lower()}.md").write_text("\n".join(md), encoding="utf-8")

    print(f"OK: {SQL_OUT} ({len(bulas)} medicamentos, {len(bulas) * len(SECOES)} chunks)")
    print(f"OK: {MD_DIR} ({len(bulas)} arquivos .md)")


if __name__ == "__main__":
    main()
