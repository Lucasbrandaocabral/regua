"""Guarda o quadro que a régua analisou, para ver depois o que ela não reconheceu.

Saem dois PNG em Imagens/Regua: o quadro puro (dá para abrir em "Testar com um print…" e rodar a
régua de novo nele) e uma cópia com a mesa que a régua usou em ciano, o que ela achou em magenta e os
números no topo, como no app do celular.
"""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np
from PySide6.QtCore import QPointF, QRectF, QStandardPaths, Qt
from PySide6.QtGui import QColor, QFont, QImage, QPainter, QPen

from .geometry import Table
from .vision import AimResult


def default_folder() -> Path:
    pictures = QStandardPaths.writableLocation(QStandardPaths.PicturesLocation) or str(Path.home())
    return Path(pictures) / "Regua"


def save_frame(frame: np.ndarray, table: Table, result: AimResult | None, folder: Path | None = None) -> Path:
    """Grava o quadro puro e o anotado; devolve o caminho do puro."""
    folder = folder or default_folder()
    folder.mkdir(parents=True, exist_ok=True)
    name = time.strftime("regua-%Y%m%d-%H%M%S") + ("-com-mira" if result else "-sem-mira")
    raw = folder / f"{name}.png"
    image = _to_image(frame)
    if not image.save(str(raw)):
        raise OSError(f"não deu para gravar {raw}")
    annotated = _annotated(image, table, result)
    if not annotated.save(str(folder / f"{name}-anotado.png")):
        raise OSError(f"não deu para gravar a cópia anotada em {folder}")
    return raw


def _to_image(frame: np.ndarray) -> QImage:
    rgb = np.ascontiguousarray(frame[..., :3], dtype=np.uint8)
    h, w = rgb.shape[:2]
    # Copia: o QImage só aponta para os bytes do array.
    return QImage(rgb.data, w, h, 3 * w, QImage.Format_RGB888).copy()


def _annotated(image: QImage, table: Table, result: AimResult | None) -> QImage:
    out = image.convertToFormat(QImage.Format_RGB32)
    p = QPainter(out)
    p.setRenderHint(QPainter.Antialiasing)
    p.setBrush(Qt.NoBrush)
    p.setPen(QPen(QColor(0, 255, 255), 2))
    p.drawRect(QRectF(table.left, table.top, table.width, table.height))
    r = table.ball_radius
    if result is not None:
        p.setPen(QPen(QColor(255, 0, 255), 2))
        end = result.cue + result.direction * result.guide_length
        p.drawEllipse(QPointF(result.cue.x, result.cue.y), r, r)
        p.drawLine(QPointF(result.cue.x, result.cue.y), QPointF(end.x, end.y))
        if result.ghost is not None:
            p.drawEllipse(QPointF(result.ghost.x, result.ghost.y), r, r)
        if result.object_ball is not None:
            p.drawEllipse(QPointF(result.object_ball.x, result.object_ball.y), 0.5 * r, 0.5 * r)
    found = "sem mira" if result is None else f"branca {result.cue.x:.0f},{result.cue.y:.0f}"
    text = (f"quadro {image.width()}x{image.height()}  mesa {table.left:.1f} {table.top:.1f} {table.right:.1f} "
            f"{table.bottom:.1f} r={r:.1f}  {found}")
    font = QFont()
    font.setPixelSize(max(12, image.height() // 40))
    p.setFont(font)
    # Texto branco com contorno escuro, para ler em cima de qualquer coisa.
    p.setPen(QColor(0, 0, 0))
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        p.drawText(QPointF(12 + dx, 8 + font.pixelSize() + dy), text)
    p.setPen(QColor(255, 255, 255))
    p.drawText(QPointF(12, 8 + font.pixelSize()), text)
    p.end()
    return out
