# Relatório de limitações, riscos residuais e próximos passos

## Escopo e o que ainda não funciona

O GuiaMed demonstra uma interface acessível, processamento local, integração
acadêmica com IA e controles PostgreSQL, mas não é um dispositivo médico nem
foi validado clinicamente. Os resultados de IA foram obtidos principalmente em
textos OCR sintéticos. A execução automatizada não substitui o teste físico de
câmera, iluminação, enquadramento, TTS, STT e vibração com pessoas cegas ou com
baixa visão. No último ciclo não havia aparelho conectado; por isso latência
fim a fim, consumo de bateria e experiência de uso permanecem pendentes.

O adapter ativo reconheceu 23 de 25 casos positivos do conjunto reservado. Os
layouts quebrados de ácido acetilsalicílico e ibuprofeno foram recusados. Essa
escolha é intencionalmente conservadora, mas pode frustrar o usuário e exigir
uma nova fotografia. O conjunto cobre 14 identidades e não demonstra
generalização para as 10.777 entradas do catálogo DCB, manuscritos, caixas
danificadas, reflexos ou abreviações fora dos padrões treinados.

O RAG usa 24 bulas fictícias e 144 chunks. Uma citação válida prova a origem do
trecho, mas não garante que o trecho responda semanticamente à pergunta: no
caso Cardiovast, o prompt v1 citou contraindicações quando a pergunta solicitava
interações. O prompt v2 corrigiu o exemplo, porém cinco perguntas repetidas três
vezes são insuficientes para afirmar superioridade geral.

O prazo da geração local é cooperativo. Uma operação de CPU já iniciada pode
ultrapassar o orçamento até o próximo passo de token. A carga fria do modelo
pode levar vários segundos e não foi medida no aparelho. O artefato aprovado
usa FP32; versões GGUF quantizadas ainda não foram promovidas por avaliação
equivalente.

## Riscos que permaneceram

O principal risco é um falso positivo: identificar a caixa errada e falar uma
informação convincente. As margens de similaridade, a recusa em ambiguidades e
a pós-checagem reduzem esse risco, mas o conjunto negativo é pequeno. Também há
risco de validade não encontrada, dose associada ao medicamento errado em
receitas complexas e nomes quebrados entre linhas. O sistema não deve sugerir
diagnóstico ou prescrição e sempre precisa orientar confirmação profissional.

Prompt injection foi recusado no caso medido e em verificações de segurança,
mas o espaço de ataques é muito maior. Texto hostil pode aparecer em embalagem,
receita ou fundo da imagem. As defesas atuais — delimitação da entrada, lista de
saídas permitidas, RAG com fonte e recusa antes do modelo — precisam de testes
contínuos com variações multilíngues e codificadas.

No banco, RLS e mascaramento foram comprovados com dados fictícios e papéis
locais. Uma configuração administrativa incorreta ainda pode ampliar acesso.
Hashes de identificadores reduzem exposição, mas valores previsíveis podem ser
atacados por dicionário; em produção seria necessário segredo do servidor
(HMAC), rotação e gestão formal de chaves. O banco padrão do workspace chegou a
apresentar esquema de auditoria não provisionado; a prova final usou banco
isolado preparado com os scripts versionados.

O custo externo é zero, mas isso não significa operação gratuita. Energia foi
apenas estimada a partir de potência assumida; não houve wattímetro nem cálculo
de depreciação do hardware. Os quatro cenários de IA também não formam um
experimento fatorial perfeito: geração direta e runtime usam conjuntos
diferentes, o que limita comparações causais.

## O que o grupo faria a seguir

1. Construir um conjunto cego com fotos reais e anonimizadas, incluindo
   iluminação ruim, reflexos, caixas amassadas, manuscritos e medicamentos não
   cadastrados; medir sensibilidade, precisão e taxa de recusa por subgrupo.
2. Realizar teste de usabilidade com pessoas cegas, com consentimento e
   supervisão, medindo tempo para concluir tarefas, compreensão da fala e
   recuperação de erros.
3. Isolar a geração em processo separado para impor deadline rígido e comparar
   FP32 com GGUF quantizado no mesmo hardware e no aparelho.
4. Ampliar prompt injection, negativos adversariais e testes de associação de
   doses; manter respostas perigosas bloqueadas por regras determinísticas.
5. Automatizar migrações do PostgreSQL, HMAC de identificadores, retenção da
   auditoria e verificação CI das permissões/RLS.
6. Repetir os quatro cenários no mesmo conjunto cego e medir energia com
   instrumento físico, reportando intervalo de confiança e custo total.

Até essas etapas, o GuiaMed deve ser tratado como protótipo acadêmico de
acessibilidade. A política segura é recusar quando houver dúvida e nunca
substituir médico ou farmacêutico.
