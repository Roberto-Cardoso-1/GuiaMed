"""Exporta o LoRA v2 mesclado para GGUF usando ferramentas locais."""

import argparse
import os
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

from local_lora import load_merged_model


def export(
    converter: Path,
    quantizer: Path,
    output: Path,
    converter_python: str,
    quantization: str = "Q4_K_M",
) -> None:
    if output.suffix.lower() != ".gguf":
        raise ValueError("A saída deve ter extensão .gguf")
    if output.exists():
        raise FileExistsError(f"A saída já existe: {output}")
    for tool in (converter, quantizer):
        if not tool.is_file():
            raise FileNotFoundError(f"Ferramenta local indisponível: {tool}")

    tokenizer, model = load_merged_model()
    output.parent.mkdir(parents=True, exist_ok=True)
    offline_env = {**os.environ, "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1"}
    with TemporaryDirectory(prefix=".guiamed-gguf-", dir=output.parent) as temporary:
        directory = Path(temporary)
        checkpoint = directory / "merged"
        fp16 = directory / "merged-f16.gguf"
        quantized = directory / "merged-quantized.gguf"
        model.save_pretrained(checkpoint, safe_serialization=True)
        tokenizer.save_pretrained(checkpoint)
        subprocess.run(
            [converter_python, str(converter), str(checkpoint), "--outfile", str(fp16), "--outtype", "f16"],
            check=True,
            env=offline_env,
        )
        if quantization == "F16":
            result = fp16
        else:
            subprocess.run([str(quantizer), str(fp16), str(quantized), quantization], check=True)
            result = quantized
        with result.open("rb") as artifact:
            if artifact.read(4) != b"GGUF":
                raise ValueError("A exportação não produziu um GGUF válido")
        # Publica o arquivo completo sem substituir uma saída criada concorrentemente.
        os.link(result, output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--converter", required=True, type=Path)
    parser.add_argument("--quantizer", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--quantization", choices=("Q4_K_M", "Q8_0", "F16"), default="Q4_K_M")
    parser.add_argument("--converter-python", default=sys.executable)
    args = parser.parse_args()
    try:
        export(
            args.converter.resolve(), args.quantizer.resolve(), args.output.resolve(),
            args.converter_python, args.quantization,
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")
