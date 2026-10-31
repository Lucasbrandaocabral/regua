"""Calibração automática: acha as bordas das tabelas numa imagem do jogo.

A borda onde a bola encosta é uma linha reta e longa onde a borracha da tabela (clara) encontra a
sombra que ela faz no pano (escura). Isso vale para qualquer cor de mesa. O ponto de partida são as
bordas fortes em faixas largas da janela que formam uma mesa 2:1, porque o lugar da mesa muda com o
formato da janela (no Google Play Games ainda tem a barra lateral). Das que formam 2:1 vale a mais de
dentro: nas mesas com trilho enfeitado as bordas do trilho são mais fortes que a da borracha, mas
ficam do lado de fora dela. Se isso falhar, tenta o layout do celular: a mesa ocupa uma fração fixa da
altura da janela e fica centralizada.
"""

from __future__ import annotations

import numpy as np

from .geometry import Table

# Medidas de um print 2408x1080 do 8 Ball Pool, em frações da altura da janela.
TOP = 255.5 / 1080
BOTTOM = 984.5 / 1080
BALL_PER_HEIGHT = 22.0 / 729.0  # raio da bola / altura da mesa (fixo no jogo)
MIN_EDGE_CONTRAST = 25.0
EDGE_CANDIDATES = 15  # bordas mais fortes de cada lado testadas na busca larga
ASPECT = (1.95, 2.05)  # largura/altura aceita para a mesa da busca larga (o jogo é 2:1)


def luminance(rgb: np.ndarray) -> np.ndarray:
    return rgb[..., 0] * 0.299 + rgb[..., 1] * 0.587 + rgb[..., 2] * 0.114


def guess_table(width: int, height: int) -> Table:
    """Mesa pelo layout padrão: altura proporcional à janela, 2:1, centralizada."""
    top, bottom = TOP * height, BOTTOM * height
    h = bottom - top
    cx = width / 2
    return Table(cx - h, top, cx + h, bottom, BALL_PER_HEIGHT * h)


def find_table(rgb: np.ndarray, start: Table | None = None) -> Table | None:
    """Refina as quatro bordas a partir de ``start`` (ou da busca larga e, se ela falhar, do chute
    pelo layout). Devolve None se o resultado não parecer uma mesa (proporção longe de 2:1 ou bordas
    fracas)."""
    h, w = rgb.shape[:2]
    lum = luminance(rgb.astype(np.float32))
    # A busca larga já acha a borda certa: o refinamento só olha uns pixels em volta dela, senão
    # pularia para a borda mais forte do trilho, logo ao lado.
    starts = [(start, WIDE_SEARCH)] if start else [(_coarse_table(lum), NARROW_SEARCH), (guess_table(w, h), WIDE_SEARCH)]
    for t, search in starts:
        found = _refine(lum, t, search) if t else None
        if found:
            return found
    return None


WIDE_SEARCH = 0.08  # em alturas da mesa: quanto o refinamento procura em volta de um chute
NARROW_SEARCH = 0.012  # em volta de uma borda já achada


def _coarse_table(lum: np.ndarray) -> Table | None:
    """Sem saber onde a mesa está: as bordas fortes de cada lado, medidas no meio da janela (que
    sempre cai dentro da mesa), combinadas na mesa 2:1 mais de dentro (a de menor área)."""
    h, w = lum.shape
    cols = np.arange(int(0.3 * w), int(0.7 * w), 2)
    rows = np.arange(int(0.4 * h), int(0.6 * h), 2)
    tops = _edge_candidates(lum, cols, np.arange(2, h // 2), axis=0, outside=-1)
    bottoms = _edge_candidates(lum, cols, np.arange(h // 2, h - 3), axis=0, outside=+1)
    lefts = _edge_candidates(lum, rows, np.arange(2, w // 2), axis=1, outside=-1)
    rights = _edge_candidates(lum, rows, np.arange(w // 2, w - 3), axis=1, outside=+1)
    best, best_area = None, float("inf")
    for top, _ in tops:
        for bottom, _ in bottoms:
            height = bottom - top
            for left, _ in lefts:
                for right, _ in rights:
                    width = right - left
                    if ASPECT[0] <= width / height <= ASPECT[1] and width * height < best_area:
                        best, best_area = (left, top, right, bottom), width * height
    if best is None:
        return None
    left, top, right, bottom = best
    return Table(left, top, right, bottom, BALL_PER_HEIGHT * (bottom - top))


def _refine(lum: np.ndarray, t: Table, search_fraction: float = WIDE_SEARCH) -> Table | None:
    h, w = lum.shape
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
        search = max(3.0, search_fraction * th)
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
    scores = _edge_scores(lum, span, positions, axis, outside)
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


def _edge_candidates(lum: np.ndarray, span: np.ndarray, positions: np.ndarray, axis: int,
                     outside: int) -> list[tuple[float, float]]:
    """Os picos de contraste mais fortes (posição, contraste), para a busca larga."""
    scores = _edge_scores(lum, span, positions, axis, outside)
    peaks = [
        i for i in range(1, len(scores) - 1)
        if scores[i] >= MIN_EDGE_CONTRAST and scores[i] >= scores[i - 1] and scores[i] > scores[i + 1]
    ]
    peaks.sort(key=lambda i: -scores[i])
    return [(float(positions[i]), float(scores[i])) for i in peaks[:EDGE_CANDIDATES]]


def _edge_scores(lum: np.ndarray, span: np.ndarray, positions: np.ndarray, axis: int, outside: int) -> np.ndarray:
    if axis == 0:
        before = lum[positions[:, None] + outside, span[None, :]]
        after = lum[positions[:, None] - outside, span[None, :]]
    else:
        before = lum[span[None, :], positions[:, None] + outside]
        after = lum[span[None, :], positions[:, None] - outside]
    # Borracha clara do lado de fora, sombra escura do lado de dentro; a mediana ignora bolas e linhas.
    return np.median(before - after, axis=1)
