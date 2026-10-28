"""Calibração automática: acha as bordas das tabelas numa imagem do jogo.

A borda onde a bola encosta é uma linha reta e longa onde a borracha da tabela (clara) encontra a
sombra que ela faz no pano (escura). Isso vale para qualquer cor de mesa. O chute inicial vem do
layout do 8 Ball Pool: a mesa ocupa uma fração fixa da altura da janela e fica centralizada.
"""

from __future__ import annotations

import numpy as np

from .geometry import Table

# Medidas de um print 2408x1080 do 8 Ball Pool, em frações da altura da janela.
TOP = 255.5 / 1080
BOTTOM = 984.5 / 1080
BALL_PER_HEIGHT = 22.0 / 729.0  # raio da bola / altura da mesa (fixo no jogo)
MIN_EDGE_CONTRAST = 25.0


def luminance(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def guess_table(width: int, height: int) -> Table:
    """Mesa pelo layout padrão: altura proporcional à janela, 2:1, centralizada."""
    top, bottom = TOP * height, BOTTOM * height
    h = bottom - top
    cx = width / 2
    return Table(cx - h, top, cx + h, bottom, BALL_PER_HEIGHT * h)


def find_table(rgb: np.ndarray, start: Table | None = None) -> Table | None:
    """Refina as quatro bordas a partir de ``start`` (ou do chute pelo layout). Devolve None se o
    resultado não parecer uma mesa (proporção longe de 2:1 ou bordas fracas)."""
    h, w = rgb.shape[:2]
    lum = luminance(rgb.astype(np.float32))
    t = start or guess_table(w, h)
    left, top, right, bottom = t.left, t.top, t.right, t.bottom
    for _ in range(3):
        tw, th = right - left, bottom - top
        cx = (left + right) / 2
        # Colunas e linhas no meio de cada tabela, longe das caçapas.
        cols = np.concatenate([
            np.arange(left + 0.12 * tw, cx - 0.08 * tw, 3),
            np.arange(cx + 0.08 * tw, right - 0.12 * tw, 3),
        ]).astype(int)
        rows = np.arange(top + 0.15 * th, bottom - 0.15 * th, 3).astype(int)
        cols = cols[(cols >= 0) & (cols < w)]
        rows = rows[(rows >= 0) & (rows < h)]
        if len(cols) < 10 or len(rows) < 10:
            return None
        search = 0.08 * th
        top = _edge(lum, cols, top - search, top + search, axis=0, outside=-1)
        bottom = _edge(lum, cols, bottom - search, bottom + search, axis=0, outside=+1)
        left = _edge(lum, rows, left - search, left + search, axis=1, outside=-1)
        right = _edge(lum, rows, right - search, right + search, axis=1, outside=+1)
        if None in (top, bottom, left, right):
            return None
    width, height = right - left, bottom - top
    if height <= 0 or not 1.9 <= width / height <= 2.1:
        return None
    return Table(left, top, right, bottom, BALL_PER_HEIGHT * height)


def _edge(lum: np.ndarray, span: np.ndarray, lo: float, hi: float, axis: int, outside: int) -> float | None:
    """Posição (com fração de pixel) da borda mais forte entre ``lo`` e ``hi``. ``axis`` 0 procura
    uma linha horizontal (y), 1 uma vertical (x); ``outside`` diz de que lado fica a tabela."""
    size = lum.shape[axis]
    positions = np.arange(max(2, int(lo)), min(size - 3, int(hi)) + 1)
    if len(positions) < 3:
        return None
    if axis == 0:
        before = lum[positions[:, None] + outside, span[None, :]]
        after = lum[positions[:, None] - outside, span[None, :]]
    else:
        before = lum[span[None, :], positions[:, None] + outside]
        after = lum[span[None, :], positions[:, None] - outside]
    # Borracha clara do lado de fora, sombra escura do lado de dentro; a mediana ignora bolas e linhas.
    scores = np.median(before - after, axis=1)
    i = int(np.argmax(scores))
    if scores[i] < MIN_EDGE_CONTRAST:
        return None
    offset = 0.0
    if 0 < i < len(scores) - 1:
        a, b, c = scores[i - 1], scores[i], scores[i + 1]
        denom = a - 2 * b + c
        if denom < 0:
            offset = 0.5 * (a - c) / denom
    return float(positions[i] + offset)
