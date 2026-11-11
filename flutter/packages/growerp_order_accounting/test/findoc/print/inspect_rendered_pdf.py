#!/usr/bin/env python3
"""Independent PDF geometry/font/header audit. Requires PyMuPDF (fitz).
Exit nonzero if the PDF fails or inspection cannot be performed.
No production network or database access.
"""
import argparse
import json
import sys
from pathlib import Path


def inspect(path: Path, min_pages: int):
    try:
        import fitz
    except ImportError as exc:
        raise RuntimeError("PyMuPDF unavailable: install in isolated test venv") from exc
    doc = fitz.open(str(path))
    problems = []
    pages = []
    if len(doc) < min_pages:
        problems.append(f"Expected >= {min_pages} pages, got {len(doc)}")
    for index, page in enumerate(doc):
        rect = page.rect
        if abs(rect.width - 595.276) > 2 or abs(rect.height - 841.89) > 2:
            problems.append(f"Page {index+1}: not A4 portrait ({rect.width}x{rect.height})")
        blocks = page.get_text("dict").get("blocks", [])
        out_of_bounds = []
        for block in blocks:
            if block.get("type") != 0:
                continue
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    bbox = fitz.Rect(span["bbox"])
                    if bbox.x0 < -0.5 or bbox.y0 < -0.5 or bbox.x1 > rect.width + 0.5 or bbox.y1 > rect.height + 0.5:
                        out_of_bounds.append(span.get("text", "")[:30])
        if out_of_bounds:
            problems.append(f"Page {index+1}: out-of-page text: {out_of_bounds[:3]}")
        text = page.get_text()
        # Some PDF extractors reorder Arabic glyphs; inspect extracted content
        # but do not treat glyph text extraction alone as visual shaping proof.
        has_table_header = any(term in text for term in ("الإجمالي", "يلامجإلا"))
        if not has_table_header:
            problems.append(f"Page {index+1}: table heading not extractable; visual review required")
        fonts = [font[3] for font in page.get_fonts(full=True)]
        if not any("DejaVu" in font for font in fonts):
            problems.append(f"Page {index+1}: DejaVu Arabic font not reported by PDF")
        drawings = page.get_drawings()
        blue = (41 / 255, 83 / 255, 175 / 255)
        has_blue = any(
            drawing.get("fill") is not None
            and all(abs(a - b) < 0.08 for a, b in zip(drawing["fill"][:3], blue))
            for drawing in drawings
        )
        if not has_blue:
            problems.append(f"Page {index+1}: reference blue fill not detected")
        pages.append({
            "number": index + 1,
            "size_points": [round(rect.width, 2), round(rect.height, 2)],
            "fonts": fonts,
            "header_extractable": has_table_header,
            "reference_blue_detected": has_blue,
            "out_of_bounds_text": out_of_bounds,
        })
    return {"file": str(path), "page_count": len(doc), "pages": pages,
            "problems": problems, "passed": not problems,
            "visual_arabic_shaping_verified": False,
            "reference_image_pixel_match_verified": False}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pdf", type=Path)
    parser.add_argument("--min-pages", type=int, default=2)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if not args.pdf.is_file():
        print(f"PDF does not exist: {args.pdf}", file=sys.stderr)
        return 2
    try:
        report = inspect(args.pdf, args.min_pages)
    except Exception as exc:
        print(f"Inspection blocked: {exc}", file=sys.stderr)
        return 2
    output = json.dumps(report, ensure_ascii=False, indent=2)
    print(output)
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
