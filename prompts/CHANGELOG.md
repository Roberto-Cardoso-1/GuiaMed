# Histórico de prompts

## v2 — atual

- Torna a citação obrigatória.
- Trata OCR/pergunta como dado não confiável.
- Adiciona recusa quando não há fonte suficiente.
- Proíbe exposição de regras, credenciais e dados de outros dispositivos.
- Passa a ser executável localmente, sem SaaS, pelo gerador fundamentado.

## v1 — anterior

- Solicitava apenas cópia literal breve de uma sentença do trecho recuperado (aceitando citação opcional `Fonte: <fonte>`).
- Não tinha regra explícita contra instruções hostis dentro dos dados nem instrução de recusa `SEM EVIDENCIA`.
- Na comparação pareada com trie extrativo igual para ambas as versões, `v1` selecionou seção incorreta em `RAG-03` (*Cardiovast*), enquanto `v2` selecionou `cardiovast#interacoes` em 3/3 repetições.
