# Interface RAG local para integração e comparação pareada

Implementação síncrona, CPU/offline. O Programador deve chamar `answer` com `await asyncio.to_thread(...)`; `main.py` não é alterado aqui. Não modificar o dataset `data/qa/rag_prompt_comparison.json`.

```python
from local_rag import LocalRag

rag = LocalRag()  # índice de 144 chunks; não carrega pesos ainda
query = "Para que serve Pressormin?"
hits = rag.search(query)  # tuple[SearchHit]; medir esta busca uma única vez
v1 = rag.answer(query, prompt_version="v1", hits=hits)
v2 = rag.answer(query, prompt_version="v2", hits=hits)
evidence_v1 = v1.evaluation_record()
evidence_v2 = v2.evaluation_record()
```

`hits` permite congelar os MESMOS objetos, ordem, scores e conteúdos. Não chama `search` outra vez quando fornecido. Aceita somente chunks intactos do próprio índice e scores válidos. `busca_ms` com hits fornecidos mede apenas validação; o benchmark deve registrar separadamente o tempo da busca original. Não alterar hits entre versões.

`LocalRag(data_path=Path(...), generator=fake)` permite testes sem pesos. O fake implementa `generate(messages, *, timeout_ms)` e retorna `rag_llm.Generation`. `get_local_rag()` e `get_rag_llm()` mantêm instâncias reutilizadas; o modelo base carrega na primeira geração, uma vez, com cache de falhas. Nenhum adapter OCR é carregado.

`answer(query, prompt_version='v2', *, hits=None, timeout_ms=15000)` retorna `RagResult` com:

- Campos compatíveis existentes: `response`, `sources`, `medicine`, `confidence`, `prompt_version`, `latency_ms`, `cost_usd`, `hostile_input_detected`.
- `raw_response`: saída decodificada do modelo ANTES do filtro, inclusive quando rejeitada/truncada por timeout; `None` quando não há saída. Não avaliar o efeito do prompt apenas por `response`.
- `generation_attempted`: interface de geração chamada; `llm_called`: `model.generate` real foi chamado; `generator_backend`: `transformers`, `fake` ou `not_called`. `decoding`: `extractive_trie`, `unconstrained` ou `not_called`. Distinguir tentativas de carga/falha, recusas antes do modelo e chamadas reais.
- `mode`: `llm_grounded`, `fallback_template`, `refusal_no_evidence`, `refusal_hostile`.
- `fallback_reason`: `unavailable`, `timeout`, `context_limit`, `generation_error`, `unverified_generation` ou `None`.
- `busca_ms`, `geracao_ms`, `total_ms` (igual a `latency_ms`), `load_ms` (subconjunto da geração, não somar duas vezes).
- `input_tokens`, `output_tokens`: contagens do tokenizer/modelo, não estimativas; `None` se indisponíveis. `model`, `revision`, `prompt_sha256` identificam exatamente o experimento.
- `citation_origin`: `model` quando a citação literal veio da saída; `structured_metadata` quando apenas `sources` identifica a fonte; `template` no fallback; `none` nas recusas. Citações produzidas pelo sistema não são atribuídas ao LLM.

`evaluation_record()` devolve todos os campos em dict, incluindo resposta bruta; não grava nem registra em logs. O benchmark pode persistir esse dict para as perguntas sintéticas autorizadas. Não persistir receitas pessoais ou saídas que as reproduzam sem autorização apropriada. O módulo não grava banco nem evidências automaticamente.

Os arquivos `prompts/v1.md` e `v2.md` têm uma única seção `EXECUTABLE_PROMPT_BEGIN/END`. Somente essa seção vira a mensagem system. Consulta/chunks seguem como JSON de dados em user; notas Markdown ficam fora do prompt. As duas versões usam os mesmos parâmetros de geração e guardrails.

Segurança: recusa sem evidência (inclusive quando nenhum medicamento é identificado na consulta); instrução hostil reconhecida recusa antes do modelo; fontes aceitas pertencem aos hits. O modelo pequeno pode inventar/parafrasear incorretamente: o filtro aceita apenas prefixos completos de sentenças a partir do início de um chunk recuperado (e, em `#posologia`, apenas o chunk integral para preservar esquema inicial e dose máxima), com whitespace equivalente e citação opcional correspondente; a geração real usa trie extrativo sobre essas sequências válidas. O sistema não chama esse fallback de geração. `raw_response` permanece disponível para medir a qualidade e o efeito do prompt independentemente desse filtro.

`cost_usd=0` mede custo de APIs externas, não energia/hardware. Timeout é cooperativo entre tokens, inclui preparação/fila após carga; cold load é medido, mas não interrompido. Uma operação CPU já iniciada pode ultrapassar o prazo. O chamador não deve supor deadline HTTP rígido.
