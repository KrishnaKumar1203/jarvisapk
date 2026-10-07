"""Bounded PDF/image OCR bridge. Tesseract itself must be installed separately."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import Any

LOGGER = logging.getLogger("jarvis.vision")
DEFAULT_MAX_BYTES = 32 * 1024 * 1024
DEFAULT_MAX_CHARS = 200_000
DEFAULT_MAX_PAGES = 50
DEFAULT_MAX_PIXELS = 40_000_000


class VisionInputError(ValueError):
    """Raised when the requested input violates the bridge's safety limits."""


def validate_input(path: Path, max_bytes: int) -> Path:
    if max_bytes <= 0:
        raise VisionInputError("The maximum file size must be positive.")
    resolved = path.expanduser().resolve(strict=True)
    if not resolved.is_file():
        raise VisionInputError("The selected path is not a regular file.")
    if resolved.stat().st_size > max_bytes:
        raise VisionInputError(f"File exceeds the configured limit of {max_bytes} bytes.")
    if resolved.suffix.lower() not in {".pdf", ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}:
        raise VisionInputError(f"Unsupported OCR input extension: {resolved.suffix or '(none)'}")
    return resolved


def _bounded_append(parts: list[str], text: str, max_chars: int) -> None:
    remaining = max_chars - sum(len(part) for part in parts)
    if remaining > 0 and text:
        parts.append(text[:remaining])


def extract_text(
    path: Path,
    language: str = "eng",
    max_bytes: int = DEFAULT_MAX_BYTES,
    max_chars: int = DEFAULT_MAX_CHARS,
    max_pages: int = DEFAULT_MAX_PAGES,
    max_pixels: int = DEFAULT_MAX_PIXELS,
) -> dict[str, Any]:
    if not language or len(language) > 64 or any(ch.isspace() for ch in language):
        raise VisionInputError("OCR language must be a non-empty Tesseract language code.")
    if min(max_chars, max_pages, max_pixels) <= 0:
        raise VisionInputError("OCR page, pixel, and character limits must be positive.")

    try:
        import fitz
        import pytesseract
        from PIL import Image
    except ImportError as error:
        raise RuntimeError(
            "Python OCR dependencies are unavailable. Install tools/python/requirements.txt."
        ) from error

    Image.MAX_IMAGE_PIXELS = max_pixels
    path = validate_input(path, max_bytes)
    pieces: list[str] = []
    pages_processed = 0

    if path.suffix.lower() == ".pdf":
        try:
            with fitz.open(path) as document:
                if document.is_encrypted:
                    raise VisionInputError("Encrypted PDFs cannot be processed without a password.")
                for page in document:
                    if pages_processed >= max_pages:
                        break
                    pages_processed += 1
                    text = page.get_text("text").strip()
                    if not text:
                        page_width = max(1, int(page.rect.width * 2))
                        page_height = max(1, int(page.rect.height * 2))
                        if page_width * page_height > max_pixels:
                            raise VisionInputError(
                                "Rendered PDF page would exceed the configured pixel limit."
                            )
                        pixmap = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
                        with Image.open(__import__("io").BytesIO(pixmap.tobytes("png"))) as image:
                            text = pytesseract.image_to_string(image, lang=language, timeout=60).strip()
                    _bounded_append(pieces, text + "\n", max_chars)
                    if sum(map(len, pieces)) >= max_chars:
                        break
        except VisionInputError:
            raise
        except Exception as error:
            raise RuntimeError(f"PDF OCR failed: {type(error).__name__}: {error}") from error
    else:
        try:
            with Image.open(path) as image:
                image.load()
                if image.width * image.height > max_pixels:
                    raise VisionInputError("Image exceeds the configured pixel limit.")
                pages_processed = 1
                _bounded_append(
                    pieces,
                    pytesseract.image_to_string(image, lang=language, timeout=60),
                    max_chars,
                )
        except VisionInputError:
            raise
        except Exception as error:
            raise RuntimeError(f"Image OCR failed: {type(error).__name__}: {error}") from error

    content = "".join(pieces).strip()
    if not content:
        raise RuntimeError("OCR completed but no readable text was found.")
    return {
        "schemaVersion": 1,
        "fileName": path.name,
        "mediaType": "application/pdf" if path.suffix.lower() == ".pdf" else "image",
        "pagesProcessed": pages_processed,
        "characters": len(content),
        "text": content,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract text from a PDF or image using PyMuPDF and Tesseract.")
    parser.add_argument("file", type=Path)
    parser.add_argument("--language", default="eng")
    parser.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES)
    parser.add_argument("--max-characters", type=int, default=DEFAULT_MAX_CHARS)
    parser.add_argument("--max-pages", type=int, default=DEFAULT_MAX_PAGES)
    arguments = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.INFO,
        format='{"level":"%(levelname)s","logger":"%(name)s","message":"%(message)s"}',
        stream=sys.stderr,
    )
    try:
        source = validate_input(arguments.file, arguments.max_bytes)
        result = extract_text(
            source,
            language=arguments.language,
            max_bytes=arguments.max_bytes,
            max_chars=arguments.max_characters,
            max_pages=arguments.max_pages,
        )
        sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")
        return 0
    except (OSError, VisionInputError, RuntimeError) as error:
        LOGGER.error("%s: %s", type(error).__name__, error)
        sys.stderr.write(json.dumps({"error": str(error)}, ensure_ascii=False) + "\n")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
