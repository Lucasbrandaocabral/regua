import math
import os

import numpy as np

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtGui import QGuiApplication  # noqa: E402

from regua_pc.app import _load_image  # noqa: E402
from regua_pc.geometry import Vec2  # noqa: E402
from regua_pc.snapshot import save_frame  # noqa: E402
from regua_pc.vision import AimDetector  # noqa: E402

from .synthetic import SyntheticTable  # noqa: E402


def test_saves_raw_and_annotated_frame(tmp_path):
    # O texto da cópia anotada precisa das fontes, que vêm com o QGuiApplication.
    _app = QGuiApplication.instance() or QGuiApplication([])
    scene = SyntheticTable()
    cue = Vec2(600, 280)
    d = Vec2.of_angle(math.radians(20))
    target = cue + d * (8 * scene.r)
    scene.ball(target, (40, 150, 60))
    scene.aim_at(cue, target, d)
    frame = scene.frame()
    result = AimDetector().detect(frame, scene.table)
    assert result is not None

    raw = save_frame(frame, scene.table, result, tmp_path)
    assert raw.name.startswith("regua-") and raw.name.endswith("-com-mira.png")
    # O quadro puro volta igual por "Testar com um print…", para rodar a régua nele de novo.
    assert np.array_equal(_load_image(str(raw)), frame)
    annotated = _load_image(str(raw.with_name(raw.stem + "-anotado.png")))
    assert annotated.shape == frame.shape and not np.array_equal(annotated, frame)

    empty = SyntheticTable()
    assert save_frame(empty.frame(), empty.table, None, tmp_path).name.endswith("-sem-mira.png")
