import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from vision_ocr import VisionInputError, validate_input


class InputValidationTests(unittest.TestCase):
    def test_rejects_unsupported_file_type(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "notes.docx"
            path.write_text("document", encoding="utf-8")

            with self.assertRaisesRegex(VisionInputError, "Unsupported OCR input extension"):
                validate_input(path, 1024)

    def test_rejects_file_above_byte_limit(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "image.png"
            path.write_bytes(b"0123456789")

            with self.assertRaisesRegex(VisionInputError, "configured limit"):
                validate_input(path, 4)

    def test_accepts_regular_pdf_within_limit(self):
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "sample.pdf"
            path.write_bytes(b"%PDF-test")

            self.assertEqual(validate_input(path, 1024), path.resolve())


if __name__ == "__main__":
    unittest.main()
