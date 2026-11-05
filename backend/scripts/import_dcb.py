"""Extrai denominações de medicamentos de uma planilha oficial DCB da Anvisa.

Usa somente a biblioteca padrão para manter o processo reproduzível e evitar
dependências de planilha no backend em produção.
"""

from __future__ import annotations

import argparse
import json
import re
import unicodedata
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET


MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
NS = {"x": MAIN_NS}
MEDICINE_CLASSIFICATIONS = {"IFA", "INF", "BIO", "RAD"}


def _column_index(reference: str) -> int:
    letters = re.match(r"[A-Z]+", reference)
    if not letters:
        return 0
    result = 0
    for char in letters.group(0):
        result = result * 26 + ord(char) - ord("A") + 1
    return result - 1


def read_rows(path: Path) -> list[list[str]]:
    with zipfile.ZipFile(path) as workbook:
        shared: list[str] = []
        shared_root = ET.fromstring(workbook.read("xl/sharedStrings.xml"))
        for item in shared_root.findall("x:si", NS):
            shared.append("".join(node.text or "" for node in item.findall(".//x:t", NS)))

        sheet_root = ET.fromstring(workbook.read("xl/worksheets/sheet1.xml"))
        rows: list[list[str]] = []
        for row in sheet_root.findall(".//x:sheetData/x:row", NS):
            values: dict[int, str] = {}
            for cell in row.findall("x:c", NS):
                reference = cell.attrib.get("r", "A1")
                value_node = cell.find("x:v", NS)
                value = value_node.text if value_node is not None and value_node.text else ""
                if cell.attrib.get("t") == "s" and value:
                    value = shared[int(value)]
                values[_column_index(reference)] = value.strip()
            if values:
                width = max(values) + 1
                rows.append([values.get(index, "") for index in range(width)])
        return rows


def normalize(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    return "".join(char for char in decomposed if not unicodedata.combining(char)).upper().strip()


def extract_medicines(rows: list[list[str]]) -> list[dict[str, str]]:
    header_index = next(
        index
        for index, row in enumerate(rows)
        if any("DENOMINACAO COMUM BRASILEIRA" in normalize(cell) for cell in row)
    )
    headers = [normalize(cell) for cell in rows[header_index]]

    def find_column(*needles: str) -> int:
        return next(
            index
            for index, header in enumerate(headers)
            if all(needle in header for needle in needles)
        )

    name_column = find_column("DENOMINACAO", "BRASILEIRA")
    class_column = find_column("CLASSIFICACAO")
    code_column = find_column("DCB")

    medicines: list[dict[str, str]] = []
    seen: set[str] = set()
    for row in rows[header_index + 1 :]:
        if max(name_column, class_column, code_column) >= len(row):
            continue
        classification = normalize(row[class_column])
        name = re.sub(r"\s+", " ", row[name_column]).strip()
        if classification not in MEDICINE_CLASSIFICATIONS or len(name) < 4:
            continue
        normalized_name = normalize(name)
        if normalized_name in seen:
            continue
        seen.add(normalized_name)
        medicines.append(
            {
                "dcb": row[code_column].strip(),
                "nome": name,
                "classificacao": classification,
            }
        )

    return sorted(medicines, key=lambda item: normalize(item["nome"]))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--inspect", action="store_true")
    args = parser.parse_args()

    rows = read_rows(args.source)
    if args.inspect:
        for row in rows[:20]:
            print(row)
        return

    medicines = extract_medicines(rows)
    if not args.output:
        raise SystemExit("--output é obrigatório fora do modo --inspect")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(medicines, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"{len(medicines)} denominações gravadas em {args.output}")


if __name__ == "__main__":
    main()
