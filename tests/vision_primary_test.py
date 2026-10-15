"""Vision-primary (v24) server helpers — no network. Missing third-party modules (firebase etc.) are stubbed.
Run: PYTHONPATH=. python tests/vision_primary_test.py"""
import sys
from unittest.mock import MagicMock

while True:
    try:
        import app as A
        break
    except ModuleNotFoundError as e:
        parts = (e.name or "").split(".")
        added = False
        for i in range(1, len(parts) + 1):
            n = ".".join(parts[:i])
            if n not in sys.modules:
                sys.modules[n] = MagicMock(); added = True
        if not added:
            raise

fails = []
def check(name, cond):
    print(("PASS " if cond else "FAIL ") + name)
    if not cond: fails.append(name)

# 1) box_2d (0-1000, [ymin,xmin,ymax,xmax]) -> real-pixel bbox, independent of image downscale
p = {"highlights": [{"box_2d": [100, 200, 150, 400], "color": "#2563EB", "action_hint": "tap", "label": "OK"}]}
els = A._vision_boxes_to_elements(p, 1080, 2400)
r = A.sanitize_highlight_response(p, 1080, 2400, elements=els)
check("box -> px bbox", r["highlights"][0]["bbox"] == [216, 240, 216, 120])

# 2) 0-1 scale tolerated
p = {"highlights": [{"box_2d": [0.1, 0.2, 0.15, 0.4], "color": "#2563EB"}]}
els = A._vision_boxes_to_elements(p, 1080, 2400)
check("0-1 scale", els and els[0]["bbox"] == [216, 240, 216, 120])

# 3) junk boxes dropped, never a guessed highlight
p = {"highlights": [{"box_2d": [0, 0, 0, 0]}, {"box_2d": "x"}, {"label": "no box"}]}
els = A._vision_boxes_to_elements(p, 1080, 2400)
r = A.sanitize_highlight_response(p, 1080, 2400, elements=els)
check("junk dropped", r["highlights"] == [])

# 4) swapped / out-of-range coordinates clamped
p = {"highlights": [{"box_2d": [900, 900, 1500, 1500], "color": "#2563EB"}]}
els = A._vision_boxes_to_elements(p, 1000, 1000)
check("clamped to screen", els and els[0]["bbox"][0] + els[0]["bbox"][2] <= 1000 + 24)

# 5) prompt stays small and carries no hard-coded app names
check("prompt size", len(A.VISION_GUIDE_INSTRUCTIONS) < 3500)
check("no hardcoded packages", "com." not in A.VISION_GUIDE_INSTRUCTIONS)

# 6) selectors: at least one, groq always uses a vision model
sel = A._vision_pick_selectors({})
check("selector exists", len(sel) >= 1)
check("groq vision model", all(s["model"] == A.GROQ_CHEAPEST_VISION_MODEL for s in sel if s["provider"] == "groq"))
check("body.model ignored for groq", all(s["provider"] != "groq" or s["model"] == A.GROQ_CHEAPEST_VISION_MODEL
                                         for s in A._vision_pick_selectors({"model": "gemini-x"})))

print("\nFAILED: %s" % fails if fails else "\nALL PASS")
sys.exit(1 if fails else 0)
