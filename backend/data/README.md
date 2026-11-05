# Catálogo DCB da Anvisa

`dcb_medicamentos.json` é gerado a partir da Lista Consolidada das
Denominações Comuns Brasileiras (DCB) publicada pela Anvisa. O catálogo amplia
o reconhecimento de nomes genéricos no OCR; ele não substitui o registro de um
medicamento e não comprova indicação ou aprovação de uso.

- Fonte incorporada: lista consolidada de maio de 2024.
- URL: <https://www.gov.br/anvisa/pt-br/assuntos/farmacopeia/dcb/2-2024-lista-consolidada-dcb-mai.xlsx>
- SHA-256 do arquivo-fonte: `21FE4A11E10B11C285F66639D70737BAAA96D3D75192543CCCC4288052A886A7`
- Classificações importadas: `IFA`, `INF`, `BIO` e `RAD`.
- Classificações excluídas: excipientes/adjuvantes, homeopáticos e espécies
  vegetais, para reduzir falsos positivos na fotografia da embalagem.

Para regenerar o catálogo:

```powershell
python backend\scripts\import_dcb.py `
  backend\data\source\dcb-anvisa-mai-2024.xlsx `
  --output backend\data\dcb_medicamentos.json
```

O arquivo-fonte original não é versionado. O JSON gerado preserva a DCB, o
nome oficial e a classificação para permitir auditoria da identificação.
