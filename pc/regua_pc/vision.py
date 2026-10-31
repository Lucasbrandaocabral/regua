"""Acha na imagem do jogo a bola branca, a linha de mira, o anel do ponto de choque e a bola alvo.

Porte de ``vision/AimDetector.kt`` do app Android. A imagem é um ``numpy.ndarray`` (altura, largura, 3)
em RGB; as medidas são em pixels da imagem e escalam com o raio da bola da :class:`Table`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field

import numpy as np

from .balls import BallFinder
from .geometry import Table, Vec2

TABLE_EDGE_CONTRAST = 40.0  # borda de uma tabela: na mesa do jogo passa de 90; no menu, perto de 0
MIN_TABLE_EDGES = 3  # das quatro tabelas (a barra de um print pode cobrir uma)
MIN_CUE_FILL = 0.6  # fração do quadrado central que precisa ser branca para ser a branca
OVERLAP_CUE_FILL = 0.3  # com o anel por cima da branca, sobra bem menos branco nela
OVERLAP_CANDIDATES = 10  # e ela pode ficar atrás de listradas com mais branco na lista
OVERLAP_MIN, OVERLAP_MAX = 0.1, 1.95  # em raios: anel por cima da branca (branca quase encostada no alvo)
OVERLAP_RING_SCORE = 0.6  # fração dos pontos do anel com o miolo claro entre o contorno escuro
RING_BAND = 0.145  # em raios: do miolo do anel até o meio do contorno escuro (traço de 0,09 + 0,11 r)
RING_CONTRAST = 60.0  # brancura do miolo do anel menos a do contorno
RING_STROKE = 0.3  # em raios: pontos da borda da branca mais perto que isso do anel estão no traço dele
SHADOW_RATIO_SPREAD = 0.15  # sombra: cada canal do pixel dividido pelo do pano dá quase o mesmo valor
EDGE_LEVEL = 130.0  # brancura no meio do caminho entre a branca (~200+) e o contorno escuro dela
MAX_CUE_CANDIDATES = 6  # bolas brancas testadas (as listradas com o lado branco para cima também são)
HOLLOW_RING = 0.4  # fração branca máxima no miolo do anel (a branca ou outra bola no fim da linha é cheia)
WHITE_BASE = 150.0  # brancura (menor canal de cor) do pano mais claro; acima disso conta como traço
RING_FIT_PX = 0.6  # pontos do anel mais longe que isso do círculo ajustado saem do ajuste
HINT_DEGREES = 15  # quanto a linha pode ter girado desde o quadro anterior
SHORT_GUIDE = 0.6  # em raios: traço mínimo da mira (com o anel quase encostado na branca)
GUIDE_TRIES = 4  # raios de direções diferentes testados na busca da linha
GUIDE_TRY_SPREAD = 3  # graus: raios mais perto que isso de um já testado são o mesmo traço
OUTLIER_PX = 1.5  # distância máxima de um ponto até a reta para continuar no ajuste
MIN_RING_SCORE = 0.55
RING_SAMPLES = 48
RING_SKIP_COS = math.cos(math.radians(25.0))
CLOSE_RING = 3.6  # em raios: até onde procura um anel quase encostado na branca
CLOSE_RING_STEP_DEG = 2.0
OBJECT_CANDIDATES = 89
OBJECT_STEP_DEG = 2.0
MIN_BALL_SCORE = 0.6
BALL_COLOR_DISTANCE = 60
CUE_EXCLUSION = 2.2  # em raios: em volta da branca não procura outras bolas (a mão do jogo fica ali)
BALL_REFRESH_FRAMES = 8

_RING_ANGLES = np.arange(RING_SAMPLES) * 2 * math.pi / RING_SAMPLES
_RING_COS = np.cos(_RING_ANGLES)
_RING_SIN = np.sin(_RING_ANGLES)


@dataclass
class AimResult:
    cue: Vec2  # centro da bola branca
    direction: Vec2  # direção da linha de mira do jogo (unitária)
    guide_length: float  # até onde a linha do jogo aparece, a partir do centro da branca
    ghost: Vec2 | None  # centro do anel onde a branca encosta na bola alvo
    object_ball: Vec2 | None  # centro da bola alvo
    object_guide_length: float  # até onde aparece a linha que sai da bola alvo
    balls: list[Vec2] = field(default_factory=list)  # outras bolas paradas (sem branca e alvo)
    cushion: Vec2 | None = None  # centro do anel quando a branca vai direto numa tabela

    def scaled(self, k: float) -> "AimResult":
        return AimResult(
            self.cue * k, self.direction, self.guide_length * k,
            self.ghost * k if self.ghost else None,
            self.object_ball * k if self.object_ball else None,
            self.object_guide_length * k, [b * k for b in self.balls],
            self.cushion * k if self.cushion else None,
        )

    def shifted(self, offset: Vec2) -> "AimResult":
        return AimResult(
            self.cue + offset, self.direction, self.guide_length,
            self.ghost + offset if self.ghost else None,
            self.object_ball + offset if self.object_ball else None,
            self.object_guide_length, [b + offset for b in self.balls],
            self.cushion + offset if self.cushion else None,
        )


def white_mask(rgb: np.ndarray) -> np.ndarray:
    """Branco "de jogo": claro e sem cor (bola branca e linhas de mira, mas não o pano)."""
    # Canal a canal: reduzir no eixo das cores com min/max do numpy é bem mais lento.
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mn = np.minimum(np.minimum(r, g), b)
    mx = np.maximum(np.maximum(r, g), b)
    return (mn >= 185) & ((mx - mn) <= 70)


def table_visible(rgb: np.ndarray, t: Table) -> bool:
    """A mesa do jogo está na imagem? Em cada tabela a borracha clara fica do lado de fora da linha e
    a sombra escura no pano, logo para dentro (qualquer cor de mesa). No menu e nas outras telas essa
    borda não existe, e texto branco viraria bola e linha de mira."""
    h, w = rgb.shape[:2]
    r = t.ball_radius
    step = max(2, int(round(0.25 * r)))
    reach = max(3, int(round(0.4 * r)))  # tolera a calibração uns pixels fora
    cx = (t.left + t.right) / 2
    # Longe das caçapas, onde a borda é reta.
    cols = np.concatenate([np.arange(t.left + 0.12 * t.width, cx - 0.08 * t.width, step),
                           np.arange(cx + 0.08 * t.width, t.right - 0.12 * t.width, step)]).astype(int)
    rows = np.arange(t.top + 0.15 * t.height, t.bottom - 0.15 * t.height, step).astype(int)
    edges = 0
    for line, span, horizontal, outside in ((t.top, cols, True, -1), (t.bottom, cols, True, 1),
                                            (t.left, rows, False, -1), (t.right, rows, False, 1)):
        best = -math.inf
        center = int(round(line))
        for p in range(center - reach, center + reach + 1):
            if horizontal:
                xo = xi = span
                yo, yi = np.full(len(span), p + outside), np.full(len(span), p - outside)
            else:
                yo = yi = span
                xo, xi = np.full(len(span), p + outside), np.full(len(span), p - outside)
            ok = (xo >= 0) & (xo < w) & (xi >= 0) & (xi < w) & (yo >= 0) & (yo < h) & (yi >= 0) & (yi < h)
            if not ok.any():
                continue
            lo = rgb[yo[ok], xo[ok]].astype(np.float32) @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
            li = rgb[yi[ok], xi[ok]].astype(np.float32) @ np.array([0.299, 0.587, 0.114], dtype=np.float32)
            # Mediana como a do Kotlin (o do meio depois de ordenar): ignora bolas e linhas.
            d = np.sort(lo - li)
            best = max(best, float(d[len(d) // 2]))
        edges += best >= TABLE_EDGE_CONTRAST
    return edges >= MIN_TABLE_EDGES


def _fit_circle(px: np.ndarray, py: np.ndarray, outlier: float = 0.8) -> tuple[Vec2 | None, float]:
    """Círculo por mínimos quadrados (Kåsa), tirando um a um o ponto mais longe dele enquanto algum
    passar de ``outlier`` px (alguns pontos ruins puxam tanto o primeiro ajuste que tirar todos os
    longe de uma vez levaria os bons junto); (None, 0) se sobrarem menos de 8."""
    keep = np.ones(len(px), dtype=bool)
    while keep.sum() >= 8:
        a = np.stack([2 * px[keep], 2 * py[keep], np.ones(keep.sum())], axis=1)
        cx, cy, c = np.linalg.lstsq(a, px[keep] ** 2 + py[keep] ** 2, rcond=None)[0]
        fit_r = math.sqrt(max(c + cx * cx + cy * cy, 0.0))
        err = np.where(keep, np.abs(np.hypot(px - cx, py - cy) - fit_r), -1.0)
        worst = int(np.argmax(err))
        if err[worst] <= outlier:
            return Vec2(float(cx), float(cy)), fit_r
        keep[worst] = False
    return None, 0.0


@dataclass
class _Line:
    origin: Vec2
    direction: Vec2
    end: Vec2


class AimDetector:
    def __init__(self) -> None:
        self._balls = BallFinder()
        self._cached_balls: list[Vec2] = []
        self._cached_balls_cue: Vec2 | None = None
        self._frames_since_balls = 0

    def detect(self, rgb: np.ndarray, table: Table, previous: AimResult | None = None) -> AimResult | None:
        """``previous`` é o resultado do quadro anterior: procura primeiro perto dele (bem mais rápido)."""
        r = table.ball_radius
        if r < 3.0 or not table_visible(rgb, table):
            return None
        self._rgb = rgb
        self._h, self._w = rgb.shape[:2]
        self._table = table
        self._white = self._white_in_table(rgb, table)
        self._wbytes = self._white.tobytes()

        hint = math.degrees(math.atan2(previous.direction.y, previous.direction.x)) if previous else None
        aim = self._find_aim(previous.cue if previous else None, hint)
        if aim is None:
            return None

        guide, cue, ring, rough, final = aim
        direction = guide.direction
        guide_length = (guide.end - cue).dot(direction)
        if final:
            guide_length = (ring - cue).dot(direction)
        elif ring is not None:
            # Dentro do anel a linha se mistura com ele: refaz o ajuste só com o trecho limpo.
            ring_distance = (ring - cue).dot(direction)
            clean = self._collect_and_fit(cue, direction, 1.5 * r, self._min_guide_samples(), stop_at=ring_distance - 1.5 * r)
            if clean is not None:
                direction = clean.direction
                cue = self._cue_center_on_line(clean.origin, direction, r)
                ring = self._find_ghost(cue, direction, ring_distance, r) or ring
                # A bola fantasma fica na reta da mira, que aqui é mais precisa que o anel.
                center = self._ring_center(direction, ring, r)
                ring = cue + direction * (center - cue).dot(direction)
            else:
                # Anel quase encostado na branca: o traço é curto demais para dar a direção, que passa
                # a ser a do centro da branca ao centro do anel. A linha achada pode ser um raio torto
                # que também chega no anel, então o centro da branca sai do branco dela, não da linha.
                ring = self._ring_center(direction, ring, r)
                cue = self._cue_on_short_guide(rough, ring, r)
                direction = (ring - cue).normalized()
            guide_length = (ring - cue).dot(direction)
        ghost, obj, cushion = None, None, None
        if ring is not None:
            obj = self._find_object_ball(ring, direction)
            if self._inside_inner(ring, 0.6 * r):
                ghost = ring
            elif obj is not None and (obj.end - obj.origin).length > 0.5 * r:
                # Anel colado na tabela, mas com uma bola encostada nele: o jogo desenha a linha dela.
                ghost = ring
            else:
                # Anel colado na tabela é a branca batendo na tabela, não numa bola: é onde o jogo diz
                # que ela encosta, mais exato que a calibração.
                obj, cushion = None, ring

        balls = [
            b for b in self._other_balls(cue)
            if b.distance_to(cue) > CUE_EXCLUSION * r
            and (obj is None or b.distance_to(obj.origin) > 1.2 * r)
            and (ghost is None or b.distance_to(ghost) > 1.6 * r)
            and not self._on_cue_stick(b, cue, direction, r)
        ]
        return AimResult(
            cue, direction, guide_length, ghost,
            obj.origin if obj else None,
            (obj.end - obj.origin).dot(obj.direction) if obj else 0.0,
            balls, cushion,
        )

    # --- máscara e utilidades ---

    def _white_in_table(self, rgb: np.ndarray, t: Table) -> np.ndarray:
        white = np.zeros(rgb.shape[:2], dtype=np.uint8)
        x0, x1 = max(0, int(t.left) - 2), min(self._w, int(math.ceil(t.right)) + 3)
        y0, y1 = max(0, int(t.top) - 2), min(self._h, int(math.ceil(t.bottom)) + 3)
        if x1 > x0 and y1 > y0:
            white[y0:y1, x0:x1] = white_mask(rgb[y0:y1, x0:x1])
        return white

    def _is_white(self, x: float, y: float) -> bool:
        xi, yi = int(round(x)), int(round(y))
        return 0 <= xi < self._w and 0 <= yi < self._h and self._wbytes[yi * self._w + xi] == 1

    def _inside_table(self, x: float, y: float) -> bool:
        t = self._table
        return t.left <= x <= t.right and t.top <= y <= t.bottom

    def _inside_inner(self, p: Vec2, margin: float) -> bool:
        t = self._table
        return t.min_x + margin <= p.x <= t.max_x - margin and t.min_y + margin <= p.y <= t.max_y - margin

    def _min_guide_samples(self) -> int:
        return int(max(12.0, 1.5 * self._table.ball_radius))

    # --- branca ---

    def _find_aim(self, near: Vec2 | None, hint: float | None) -> tuple[_Line, Vec2, Vec2 | None, Vec2] | None:
        """A branca é a bola branca de onde sai a linha de mira: das listradas com o lado branco para
        cima não sai linha. Se mais de uma tiver linha (a da bola alvo volta pela mira numa tacada
        reta), vale a que termina num anel oco; um traço curto só vale se terminar nele. Se nenhuma
        linha achada acaba num anel, procura um anel quase encostado numa delas. ``near`` é a branca do
        quadro anterior (testada primeiro) e ``hint`` a direção da mira nele, em graus. Devolve a
        linha, o centro da branca nela, o anel e o centro da branca achado só pelo branco."""
        r = self._table.ball_radius
        candidates = [c for c in self._cue_candidates() if self._line_leaves(c, r)]
        if near is not None:
            candidates.sort(key=lambda c: c.distance_to(near) > 3 * r)
        fallback = None
        # Primeiro perto da direção do quadro anterior; se isso não der numa linha que acaba num anel
        # (a dica pode cair noutro traço branco), varre todas as direções.
        searches = (hint, None) if hint is not None else (None,)
        for rough in candidates:
            for around in searches:
                guide = self._find_guide(rough, around)
                if guide is None:
                    continue
                cue = self._cue_center_on_line(guide.origin, guide.direction, r)
                length = (guide.end - cue).dot(guide.direction)
                ring = self._find_ghost(cue, guide.direction, length, r)
                if ring is not None and self._white_fraction(ring, 0.5 * r) < HOLLOW_RING:
                    return guide, cue, ring, rough, False
                if length - 1.5 * r >= self._min_guide_samples():
                    fallback = fallback or (guide, cue, ring, rough, False)
        for rough in candidates:
            ring = self._find_close_ring(rough, r)
            if ring is not None:
                u = (ring - rough).normalized()
                return _Line(rough, u, ring), self._cue_center_on_line(rough, u, r), ring, rough, False
        # Branca quase encostada na bola alvo: o anel fica por cima dela e a linha da bola alvo pode
        # passar por uma linha de mira saindo de outra bola; por isso vem antes do ``fallback``.
        return self._find_overlap_aim(near) or fallback

    def _find_overlap_aim(self, near: Vec2 | None):
        """Anel por cima da própria branca (a menos de 2 raios dela): quase não sobra traço de mira e o
        miolo da branca fica coberto pelo anel. Procura o anel pelo traço dele (miolo claro entre
        contorno escuro, que aparece até em cima da bola) e confirma com a bola alvo encostada nele. O centro da branca sai da
        borda dela, e a direção, do centro da branca ao centro do anel."""
        r = self._table.ball_radius
        candidates = self._cue_candidates(OVERLAP_CUE_FILL, OVERLAP_CANDIDATES)
        if near is not None:
            candidates.sort(key=lambda c: c.distance_to(near) > 3 * r)
        for rough in candidates:
            # Saem linhas dali (a da branca depois do choque e a da bola alvo): teste barato antes da
            # busca do anel, que é cara.
            if not self._line_leaves(rough, r):
                continue
            ring = self._find_overlap_ring(rough, r)
            if ring is None:
                continue
            cue = self._cue_center_by_edge(rough, ring, r)
            if cue is not None:
                # De novo, já do centro certo (os raios cruzam a borda de frente e o lado do taco fica exato).
                cue = self._cue_center_by_edge(cue, ring, r) or cue
            else:
                # Anel quase todo em cima da borda da branca (a meio raio ou menos): sobra pouca borda
                # limpa. Fica o centro pelo branco; a bola alvo e a linha dela continuam exatas.
                cue = rough
            d = ring.distance_to(cue)
            if not OVERLAP_MIN * r <= d <= OVERLAP_MAX * r:
                continue
            ring = self._ring_center(Vec2(0.0, 0.0), ring, r, avoid=cue) or ring
            u = (ring - cue).normalized()
            # A bola alvo encostada no anel confirma (a linha dela pode sumir na borracha, se ela vai
            # direto para a tabela).
            if self._find_object_ball(ring, u) is None:
                continue
            return _Line(cue, u, ring), cue, ring, rough, True
        return None

    def _find_overlap_ring(self, c: Vec2, r: float) -> Vec2 | None:
        """O anel a ``OVERLAP_MIN``..``OVERLAP_MAX`` raios de ``c`` (o centro aproximado da branca, que
        pode estar uns pixels fora porque o anel cobre parte dela). Por cima da branca o miolo branco do
        anel some no branco da bola, mas o contorno escuro dos dois lados continua: o teste é miolo
        claro com escuro logo dentro e logo fora, que vale tanto no pano quanto em cima da bola."""
        thetas = np.radians(np.arange(0.0, 360.0, CLOSE_RING_STEP_DEG))
        rhos = np.arange(0.0, OVERLAP_MAX * r + 6, 1.0)
        cos_t, sin_t = np.cos(thetas), np.sin(thetas)
        gx = (c.x + cos_t[:, None] * rhos[None, :])[..., None]
        gy = (c.y + sin_t[:, None] * rhos[None, :])[..., None]
        band = RING_BAND * r
        core = self._whiteness(gx + _RING_COS * r, gy + _RING_SIN * r)
        dark = np.maximum(self._whiteness(gx + _RING_COS * (r - band), gy + _RING_SIN * (r - band)),
                          self._whiteness(gx + _RING_COS * (r + band), gy + _RING_SIN * (r + band)))
        scores = ((core - dark) > RING_CONTRAST).mean(axis=2)
        ia, ir = np.unravel_index(int(np.argmax(scores)), scores.shape)
        if scores[ia, ir] < OVERLAP_RING_SCORE:
            return None
        # O meio do platô (o anel tem uns pixels de largura).
        best = scores[ia, ir]
        sel = scores >= best - 0.03
        return Vec2(float(gx[..., 0][sel].mean()), float(gy[..., 0][sel].mean())) if sel.sum() < 40 else \
            Vec2(float(gx[ia, ir, 0]), float(gy[ia, ir, 0]))

    def _cue_center_by_edge(self, rough: Vec2, ring: Vec2, r: float) -> Vec2 | None:
        """Centro da branca pelo círculo da borda dela (onde o branco da bola encontra o contorno
        escuro). Os pontos de borda que caem no traço do anel ficam de fora (pela posição de cada um,
        não pelo centro aproximado, que pode estar uns pixels fora), e também o lado da ponta do taco,
        que fica logo atrás da branca e é clara."""
        u = (ring - rough).normalized()
        angles = np.radians(np.arange(0.0, 360.0, 5.0))
        ex, ey = np.cos(angles), np.sin(angles)
        keep = (ex * -u.x + ey * -u.y) < math.cos(math.radians(35))
        ex, ey = ex[keep], ey[keep]
        ss = np.arange(0.4 * r, 1.6 * r, 0.25)
        w = self._whiteness(rough.x + ex[:, None] * ss[None, :], rough.y + ey[:, None] * ss[None, :])
        px, py = [], []
        for k in range(len(w)):
            row = w[k]
            white = np.nonzero(row >= 170)[0]
            if len(white) == 0:
                continue
            # Primeira queda do branco da bola para o escuro andando para fora: a borda da bola (mais
            # longe, o miolo branco do anel pode aparecer de novo).
            below = np.nonzero(row[white[0]:] < EDGE_LEVEL)[0]
            if len(below) == 0:
                continue
            j = white[0] + below[0] - 1
            f = (row[j] - EDGE_LEVEL) / (row[j] - row[j + 1])
            s = ss[j] + f * (ss[j + 1] - ss[j])
            x, y = rough.x + ex[k] * s, rough.y + ey[k] * s
            if abs(math.hypot(x - ring.x, y - ring.y) - r) > RING_STROKE * r:
                px.append(x)
                py.append(y)
        if len(px) < 8:
            return None
        center, radius = _fit_circle(np.array(px), np.array(py))
        if center is None or abs(radius - r) > 0.25 * r or center.distance_to(rough) > 0.8 * r:
            return None
        return center

    def _line_leaves(self, c: Vec2, r: float) -> bool:
        """Teste barato antes da busca da linha: uma linha que sai da bola cruza pelo menos dois dos
        círculos de 1,5 a 2,4 raios em volta dela. Não todos: com o anel perto da branca, um deles
        pode cair inteiro no contorno escuro do anel."""
        crossed = 0
        for radius in (1.5 * r, 1.8 * r, 2.1 * r, 2.4 * r):
            n = int(2 * math.pi * radius) + 1
            a = np.arange(n) * (2 * math.pi / n)
            xs = np.rint(c.x + np.cos(a) * radius).astype(int)
            ys = np.rint(c.y + np.sin(a) * radius).astype(int)
            inb = (xs >= 0) & (xs < self._w) & (ys >= 0) & (ys < self._h)
            crossed += bool(self._white[ys[inb], xs[inb]].any())
        return crossed >= 2

    def _cue_candidates(self, min_fill: float = MIN_CUE_FILL, limit: int = MAX_CUE_CANDIDATES) -> list[Vec2]:
        """Lugares onde um quadrado do tamanho do miolo da bola fica cheio de branco, do mais cheio para
        o menos (até ``MAX_CUE_CANDIDATES``)."""
        t = self._table
        r = t.ball_radius
        half = max(2, int(round(0.6 * r)))
        x0, x1 = max(0, int(t.min_x)), min(self._w - 1, int(math.ceil(t.max_x)))
        y0, y1 = max(0, int(t.min_y)), min(self._h - 1, int(math.ceil(t.max_y)))
        if x1 <= x0 or y1 <= y0:
            return []
        ix0, iy0 = max(0, x0 - half), max(0, y0 - half)
        ix1, iy1 = min(self._w - 1, x1 + half), min(self._h - 1, y1 + half)
        region = self._white[iy0:iy1 + 1, ix0:ix1 + 1].astype(np.int32)
        integral = np.zeros((region.shape[0] + 1, region.shape[1] + 1), dtype=np.int32)
        integral[1:, 1:] = region.cumsum(0).cumsum(1)
        cys = np.arange(y0, y1 + 1, 2)
        cxs = np.arange(x0, x1 + 1, 2)
        h, w = region.shape
        top = np.clip(cys - half - iy0, 0, h)[:, None]
        bottom = np.clip(cys + half - iy0 + 1, 0, h)[:, None]
        left = np.clip(cxs - half - ix0, 0, w)[None, :]
        right = np.clip(cxs + half - ix0 + 1, 0, w)[None, :]
        sums = (integral[bottom, right] - integral[top, right] - integral[bottom, left] + integral[top, left]).ravel()
        side = 2 * half + 1
        full = np.nonzero(sums >= min_fill * side * side)[0]
        peaks: list[Vec2] = []
        for k in full[np.argsort(-sums[full], kind="stable")]:
            j, i = divmod(int(k), len(cxs))
            p = Vec2(float(cxs[i]), float(cys[j]))
            if all(p.distance_to(q) >= 1.5 * r for q in peaks):
                peaks.append(p)
                if len(peaks) == limit:
                    break
        centers = []
        for center in peaks:
            for _ in range(2):
                center = self._white_centroid(center, 0.85 * r) or center
            centers.append(center)
        return centers

    def _disc(self, c: Vec2, radius: float):
        xs = np.arange(int(math.floor(c.x - radius)), int(math.ceil(c.x + radius)) + 1)
        ys = np.arange(int(math.floor(c.y - radius)), int(math.ceil(c.y + radius)) + 1)
        gx, gy = np.meshgrid(xs, ys)
        inside = ((gx - c.x) ** 2 + (gy - c.y) ** 2 <= radius * radius)
        inside &= (gx >= 0) & (gx < self._w) & (gy >= 0) & (gy < self._h)
        return gx[inside], gy[inside]

    def _white_fraction(self, c: Vec2, radius: float) -> float:
        xs, ys = self._disc(c, radius)
        return float((self._white[ys, xs] == 1).mean()) if len(xs) else 0.0

    def _white_centroid(self, c: Vec2, radius: float) -> Vec2 | None:
        xs, ys = self._disc(c, radius)
        sel = self._white[ys, xs] == 1
        if not sel.any():
            return None
        return Vec2(float(xs[sel].mean()), float(ys[sel].mean()))

    def _cue_center_on_line(self, on_line: Vec2, d: Vec2, r: float) -> Vec2:
        """Acerta o centro da branca ao longo da linha: média dos pixels brancos numa janela um pouco
        maior que a bola, ignorando a faixa da linha de mira (que cobre metade da bola)."""
        n = Vec2(-d.y, d.x)
        center = on_line
        for _ in range(8):
            xs, ys = self._disc(center, 1.2 * r)
            dx, dy = xs - center.x, ys - center.y
            sel = (np.abs(dx * n.x + dy * n.y) >= 0.35 * r) & (self._white[ys, xs] == 1)
            if not sel.any():
                break
            shift = float((dx[sel] * d.x + dy[sel] * d.y).mean())
            center = center + d * shift
            if abs(shift) < 0.1:
                break
        return center

    # --- linha de mira ---

    def _thin_white_at(self, px: float, py: float, nx: float, ny: float, window: int, max_core: int) -> bool:
        for i in range(2 * window + 1):
            k = i // 2 if i % 2 == 0 else -(i + 1) // 2
            if not self._is_white(px + nx * k, py + ny * k):
                continue
            lo = k
            while k - lo <= max_core and self._is_white(px + nx * (lo - 1), py + ny * (lo - 1)):
                lo -= 1
            hi = k
            while hi - lo < max_core and self._is_white(px + nx * (hi + 1), py + ny * (hi + 1)):
                hi += 1
            return hi - lo + 1 <= max_core
        return False

    def _run_length(self, origin: Vec2, u: Vec2, start: float) -> float:
        """Até que distância o raio segue um traço branco fino sem interrupção."""
        r = self._table.ball_radius
        window = max(1, int(round(0.09 * r)))
        max_core = max(3, int(round(0.3 * r)))
        gap = max(8.0, 0.45 * r)
        last = -1.0
        d = start
        while True:
            px, py = origin.x + u.x * d, origin.y + u.y * d
            if not self._inside_table(px, py):
                break
            if self._thin_white_at(px, py, -u.y, u.x, window, max_core):
                last = d
            elif d - (start if last < 0 else last) > gap:
                break
            d += 2.0
        return 0.0 if last < 0 else last - start

    def _find_guide(self, cue: Vec2, around_degrees: float | None) -> _Line | None:
        """Os raios que seguem um traço branco fino por mais tempo a partir da branca (com raios
        paralelos deslocados, porque o centro achado pode estar fora da linha), do mais comprido para
        o menos: vale o primeiro que dá uma reta passando pela branca. Com o anel quase encostado na
        branca o traço é curto, e um raio torto que cruza o anel e a linha da tangente pode ir mais
        longe que ele."""
        r = self._table.ball_radius
        start = 1.5 * r
        if around_degrees is None:
            degrees = range(360)
            offsets = (0.0, -0.5 * r, 0.5 * r, -r, r)
        else:
            # Com a dica do quadro anterior a branca já está bem centrada na linha.
            c = int(round(around_degrees))
            degrees = range(c - HINT_DEGREES, c + HINT_DEGREES + 1)
            offsets = (0.0,)
        runs = []
        for deg in degrees:
            u = Vec2.of_angle(math.radians(deg))
            n = Vec2(-u.y, u.x)
            for offset in offsets:
                origin = cue + n * offset
                runs.append((self._run_length(origin, u, start), deg, origin))
        runs.sort(key=lambda run: -run[0])
        min_run = max(6.0, SHORT_GUIDE * r)
        tried: list[int] = []
        for run, deg, origin in runs:
            if run < min_run or len(tried) == GUIDE_TRIES:
                break
            if any(abs((deg - t + 180) % 360 - 180) <= GUIDE_TRY_SPREAD for t in tried):
                continue
            tried.append(deg)
            # Traço curto: o cruzamento com o anel nunca é estreito e não entra na conta.
            min_samples = min(self._min_guide_samples(), max(4.0, 0.3 * run))
            u = Vec2.of_angle(math.radians(deg))
            stop_at = start + max(run - r, 0.6 * run)
            wide = self._collect_and_fit(origin, u, start, min_samples, stop_at, tolerance=0.6 * r)
            if wide is None:
                continue
            line = self._collect_and_fit(wide.origin, wide.direction, start, min_samples) or wide
            # A linha do jogo sai do centro da branca (que a mão do "bola na mão" pode tirar até um
            # raio do lugar), e a reta ajustada tem de seguir o traço desde a borda dela (um ajuste
            # que escorregou para a linha da tangente não segue).
            off = cue - line.origin
            if (abs(off.x * line.direction.y - off.y * line.direction.x) <= 1.2 * r
                    and self._run_length(line.origin, line.direction, start) >= min_run):
                return line
        return None

    def _fit_line(self, origin: Vec2, approx_angle: float, start: float, min_samples: int) -> _Line | None:
        r = self._table.ball_radius
        gap = max(10.0, r)  # tolera buracos: uma linha da régua pode cruzar a do jogo
        wide = self._collect_and_fit(origin, Vec2.of_angle(approx_angle), start, min_samples, tolerance=1.2 * r, gap=gap)
        if wide is None:
            return None
        return self._collect_and_fit(wide.origin, wide.direction, start, min_samples, gap=gap) or wide

    def _collect_and_fit(
        self, origin: Vec2, u: Vec2, start: float, min_samples: int,
        stop_at: float = math.inf, tolerance: float | None = None, gap: float | None = None,
    ) -> _Line | None:
        """Anda pela reta (um pixel inteiro por vez no eixo em que ela mais avança) juntando o meio de
        cada corte estreito do traço até ele acabar, e ajusta uma reta por mínimos quadrados.
        ``min_samples`` é em pixels de comprimento do traço."""
        t = self._table
        r = t.ball_radius
        if tolerance is None:
            tolerance = max(2.0, 0.15 * r)
        if gap is None:
            gap = max(8.0, 0.45 * r)
        horizontal = abs(u.x) >= abs(u.y)
        major = u.x if horizontal else u.y
        if abs(major) < 1e-9:
            return None
        # Um corte por pixel do eixo principal: na diagonal são só ~0,7 cortes por pixel de traço.
        min_samples = max(3, int(math.ceil(min_samples * abs(major))))
        slope = u.y / u.x if horizontal else u.x / u.y
        o_major, o_minor = (origin.x, origin.y) if horizontal else (origin.y, origin.x)
        stretch = 1.0 / abs(major)
        tol = int(round(tolerance * stretch))
        max_core = int(round(max(3.0, 0.3 * r) * stretch))
        step = 1 if major > 0 else -1
        m0 = int(round(o_major + major * start))
        span = int((t.width + t.height) * 2) + 2
        ms = m0 + step * np.arange(span)
        d = (ms - o_major) / major
        minor = o_minor + (ms - o_major) * slope
        xs, ys = (ms, minor) if horizontal else (minor, ms)
        ok = (xs >= t.left) & (xs <= t.right) & (ys >= t.top) & (ys <= t.bottom) & (d <= stop_at)
        stop = int(np.argmin(ok)) if not ok.all() else len(ok)
        if stop == 0:
            return None
        ms, d, minor = ms[:stop], d[:stop], minor[:stop]
        c = np.rint(minor).astype(np.int64)

        reach = tol + max_core + 1
        ks = np.arange(-reach, reach + 1)
        rows = ms[:, None]
        cols = c[:, None] + ks[None, :]
        if horizontal:
            gx, gy = np.broadcast_to(rows, cols.shape), cols
        else:
            gx, gy = cols, np.broadcast_to(rows, cols.shape)
        inb = (gx >= 0) & (gx < self._w) & (gy >= 0) & (gy < self._h)
        win = np.zeros(cols.shape, dtype=bool)
        win[inb] = self._white[gy[inb], gx[inb]] == 1

        # Branco mais perto da reta (ordem 0, -1, 1, -2, 2...).
        order = [0]
        for k in range(1, tol + 1):
            order += [-k, k]
        order = np.array(order)
        hits = win[:, order + reach]
        has_hit = hits.any(axis=1)
        hit_k = order[np.argmax(hits, axis=1)]
        lo = hit_k.copy()
        hi = hit_k.copy()
        idx = np.arange(len(ms))
        for _ in range(max_core + 1):
            cand = lo - 1
            can = has_hit & (hit_k - lo <= max_core) & (cand >= -reach)
            can[can] &= win[idx[can], cand[can] + reach]
            if not can.any():
                break
            lo[can] -= 1
        for _ in range(max_core + 1):
            cand = hi + 1
            can = has_hit & (hi - lo < max_core) & (cand <= reach)
            can[can] &= win[idx[can], cand[can] + reach]
            if not can.any():
                break
            hi[can] += 1
        narrow = has_hit & (hi - lo + 1 <= max_core)

        # O traço acaba no primeiro buraco maior que ``gap``.
        last_hit = np.maximum.accumulate(np.where(has_hit, d, -1.0))
        ref = np.maximum(last_hit, start)
        breaks = np.nonzero(~has_hit & (d - ref > gap))[0]
        end = int(breaks[0]) if len(breaks) else len(d)
        if not has_hit[:end].any():
            return None
        last_white = float(d[:end][has_hit[:end]].max())
        sel = narrow[:end]
        centers = c[:end][sel] + (lo[:end][sel] + hi[:end][sel]) / 2.0
        majors = ms[:end][sel].astype(float)
        along = d[:end][sel]
        if len(along) < min_samples:
            return None
        px, py = (majors, centers) if horizontal else (centers, majors)

        cut = stop_at if math.isfinite(stop_at) else last_white - 1.3 * r
        keep = along <= cut
        if keep.sum() < min_samples:
            keep = np.ones(len(along), dtype=bool)
        mean = Vec2(0.0, 0.0)
        v = u
        for _ in range(3):
            if keep.sum() < min_samples:
                return None
            kx, ky = px[keep], py[keep]
            mx, my = float(kx.mean()), float(ky.mean())
            cxx = float(((kx - mx) ** 2).mean())
            cyy = float(((ky - my) ** 2).mean())
            cxy = float(((kx - mx) * (ky - my)).mean())
            mean = Vec2(mx, my)
            v = Vec2.of_angle(0.5 * math.atan2(2 * cxy, cxx - cyy))
            if v.dot(u) < 0:
                v = -v
            far = keep & (np.abs((px - mx) * v.y - (py - my) * v.x) > OUTLIER_PX)
            if not far.any():
                break
            keep &= ~far
        projected = mean + v * (origin - mean).dot(v)
        return _Line(projected, v, origin + u * last_white)

    # --- anel e bola alvo ---

    def _find_ghost(self, cue: Vec2, d: Vec2, guide_length: float, r: float) -> Vec2 | None:
        """O anel do ponto de choque fica perto do fim da linha: onde um círculo do raio da bola é
        mais branco."""
        start = max(2.0 * r, guide_length - 2.0 * r)
        stop = guide_length + 1.5 * r
        count = int(stop - start) + 1
        if count <= 0:
            return None
        ts = start + np.arange(count)
        cx = cue.x + d.x * ts
        cy = cue.y + d.y * ts
        use = (_RING_COS * -d.x + _RING_SIN * -d.y) <= RING_SKIP_COS  # por trás entra a própria linha
        hits = np.zeros((count, RING_SAMPLES), dtype=bool)
        for radius in (r - 2, r, r + 2):
            sx = np.rint(cx[:, None] + _RING_COS[None, :] * radius).astype(np.int64)
            sy = np.rint(cy[:, None] + _RING_SIN[None, :] * radius).astype(np.int64)
            inb = (sx >= 0) & (sx < self._w) & (sy >= 0) & (sy < self._h)
            val = np.zeros(sx.shape, dtype=bool)
            val[inb] = self._white[sy[inb], sx[inb]] == 1
            hits |= val
        scores = hits[:, use].mean(axis=1)
        best = float(scores.max())
        if best < MIN_RING_SCORE:
            return None
        middle = float(ts[scores >= best - 0.03].mean())
        return cue + d * middle

    def _find_close_ring(self, c: Vec2, r: float) -> Vec2 | None:
        """Anel quase encostado na branca, quando o traço até ele é curto demais para a busca pela
        linha: o círculo oco do tamanho da bola mais branco a até ``CLOSE_RING`` raios do centro
        ``c``, com a linha da mira chegando no centro dele."""
        thetas = np.radians(np.arange(0.0, 360.0, CLOSE_RING_STEP_DEG))
        rhos = np.arange(2.2 * r, CLOSE_RING * r, 1.0)
        cos_t, sin_t = np.cos(thetas), np.sin(thetas)
        gx = c.x + cos_t[:, None] * rhos[None, :]
        gy = c.y + sin_t[:, None] * rhos[None, :]
        hits = np.zeros(gx.shape + (RING_SAMPLES,), dtype=bool)
        for radius in (r - 2, r, r + 2):
            sx = np.rint(gx[..., None] + _RING_COS * radius).astype(np.int32)
            sy = np.rint(gy[..., None] + _RING_SIN * radius).astype(np.int32)
            inb = (sx >= 0) & (sx < self._w) & (sy >= 0) & (sy < self._h)
            hits[inb] |= self._white[sy[inb], sx[inb]] == 1
        # Sem o lado virado para a branca, por onde entra a linha.
        use = (_RING_COS[None, :] * -cos_t[:, None] + _RING_SIN[None, :] * -sin_t[:, None]) <= RING_SKIP_COS
        scores = (hits & use[:, None, :]).sum(axis=2) / use.sum(axis=1)[:, None]
        ia, ir = np.unravel_index(int(np.argmax(scores)), scores.shape)
        if scores[ia, ir] < MIN_RING_SCORE:
            return None
        ring = Vec2(float(gx[ia, ir]), float(gy[ia, ir]))
        if self._white_fraction(ring, 0.5 * r) >= HOLLOW_RING:
            return None
        ring = self._ring_center((ring - c).normalized(), ring, r)
        # Dentro do anel, a linha da mira vem da branca até o centro.
        u = (c - ring).normalized()
        window = max(1, int(round(0.15 * r)))
        max_core = max(3, int(round(0.3 * r)))
        ss = np.arange(0.25 * r, 0.75 * r, 1.0)
        on = sum(self._thin_white_at(ring.x + u.x * s, ring.y + u.y * s, -u.y, u.x, window, max_core) for s in ss)
        return ring if on >= 0.7 * len(ss) else None

    def _cue_on_short_guide(self, cue: Vec2, ring: Vec2, r: float) -> Vec2:
        """Centro da branca na reta do traço curto entre ela e o anel. O centro achado pelo branco
        sai um pouco do lugar (a linha e o contorno dela cobrem parte da bola) e, com o anel tão
        perto, esse desvio de lado vira graus na direção da mira."""
        u = (ring - cue).normalized()
        line = self._collect_and_fit(cue, u, 0.5 * r, 4, stop_at=(ring - cue).length - 0.25 * r, tolerance=0.3 * r)
        if line is None:
            return cue
        return self._cue_center_on_line(line.origin, line.direction, r)

    def _ring_center(self, d: Vec2, ghost: Vec2, r: float, avoid: Vec2 | None = None) -> Vec2:
        """Centro do anel com fração de pixel: círculo ajustado ao ponto mais branco de cada raio (sem
        o lado de onde vem a linha, que tem a direção ``d``, ou, com ``avoid``, sem os raios que passam
        pela branca centrada ali). A direção da bola alvo sai daqui, então meio pixel errado aqui vira
        ângulo errado lá. Se o ajuste não convencer, devolve ``ghost``."""
        if avoid is None:
            use = (_RING_COS * -d.x + _RING_SIN * -d.y) <= RING_SKIP_COS
        else:
            near = np.arange(0.6 * r, 1.5 * r, 1.0)
            px_ = ghost.x + _RING_COS[:, None] * near[None, :]
            py_ = ghost.y + _RING_SIN[:, None] * near[None, :]
            use = (np.hypot(px_ - avoid.x, py_ - avoid.y) > r + 3).all(axis=1)
        cos, sin = _RING_COS[use], _RING_SIN[use]
        ss = np.arange(0.7 * r, 1.4 * r, 0.25)
        xs = ghost.x + cos[:, None] * ss[None, :]
        ys = ghost.y + sin[:, None] * ss[None, :]
        w = self._whiteness(xs, ys)
        # Só em volta do pico de cada raio: as linhas que cruzam o anel são brancas no raio todo.
        w = np.where(np.abs(ss[None, :] - ss[np.argmax(w, axis=1)][:, None]) <= 1.5, np.clip(w - WHITE_BASE, 0, None), 0)
        total = w.sum(axis=1)
        ok = total > 0
        radius = (w[ok] * ss).sum(axis=1) / total[ok]
        px, py = ghost.x + cos[ok] * radius, ghost.y + sin[ok] * radius
        keep = np.ones(len(px), dtype=bool)
        for _ in range(3):
            if keep.sum() < RING_SAMPLES // 3:
                return ghost
            # Círculo por mínimos quadrados (Kåsa): x² + y² = 2ax + 2by + c.
            a = np.stack([2 * px[keep], 2 * py[keep], np.ones(keep.sum())], axis=1)
            cx, cy, c = np.linalg.lstsq(a, px[keep] ** 2 + py[keep] ** 2, rcond=None)[0]
            fit_r = math.sqrt(max(c + cx * cx + cy * cy, 0.0))
            far = np.abs(np.hypot(px - cx, py - cy) - fit_r) > RING_FIT_PX
            if not (far & keep).any():
                break
            keep &= ~far
        center = Vec2(float(cx), float(cy))
        if abs(fit_r - r) > 0.2 * r or center.distance_to(ghost) > 0.5 * r:
            return ghost
        return center

    def _whiteness(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """Brancura (o menor canal de cor) em pontos com fração de pixel, por interpolação bilinear."""
        x0 = np.clip(np.floor(xs).astype(np.int64), 0, self._w - 2)
        y0 = np.clip(np.floor(ys).astype(np.int64), 0, self._h - 2)
        fx, fy = np.clip(xs - x0, 0, 1), np.clip(ys - y0, 0, 1)

        def at(dx: int, dy: int) -> np.ndarray:
            return self._rgb[y0 + dy, x0 + dx].min(axis=-1).astype(np.float32)

        return (at(0, 0) * (1 - fx) * (1 - fy) + at(1, 0) * fx * (1 - fy)
                + at(0, 1) * (1 - fx) * fy + at(1, 1) * fx * fy)

    def _stroke_centers(self, origin: Vec2, d: Vec2, start: float, stop: float) -> np.ndarray:
        """Centro do traço branco com fração de pixel num corte perpendicular a cada pixel entre
        ``start`` e ``stop``: média da brancura no corte (a borda do traço é suavizada)."""
        s = np.arange(start, stop, 1.0)
        reach = max(3.0, 0.35 * self._table.ball_radius)
        ks = np.arange(-reach, reach + 1e-9, 0.25)
        cx, cy = origin.x + d.x * s, origin.y + d.y * s
        w = np.clip(self._whiteness(cx[:, None] - d.y * ks[None, :], cy[:, None] + d.x * ks[None, :]) - WHITE_BASE, 0, None)
        total = w.sum(axis=1)
        ok = total > 0
        k = (w[ok] * ks).sum(axis=1) / total[ok]
        return np.stack([cx[ok] - d.y * k, cy[ok] + d.x * k], axis=1)

    def _find_object_ball(self, ghost: Vec2, aim: Vec2) -> _Line | None:
        """A bola alvo encosta no anel, na frente da mira: acha o lado onde há uma bola e, se o jogo
        desenhou a linha que sai dela, usa essa linha como direção exata."""
        r = self._table.ball_radius
        cloth = self._cloth_color(ghost, r)
        aim_angle = math.atan2(aim.y, aim.x)
        angles = aim_angle + np.radians(OBJECT_STEP_DEG * (np.arange(OBJECT_CANDIDATES) - OBJECT_CANDIDATES // 2))
        centers_x = ghost.x + np.cos(angles) * 2 * r
        centers_y = ghost.y + np.sin(angles) * 2 * r
        rad = 0.6 * r
        k = int(math.ceil(rad)) + 1
        ox, oy = np.meshgrid(np.arange(-k, k + 1), np.arange(-k, k + 1))
        # Todos os candidatos de uma vez: (candidato, ponto do disco).
        px = np.floor(centers_x).astype(int)[:, None] + ox.ravel()[None, :]
        py = np.floor(centers_y).astype(int)[:, None] + oy.ravel()[None, :]
        inside = (px - centers_x[:, None]) ** 2 + (py - centers_y[:, None]) ** 2 <= rad * rad
        inb = inside & (px >= 0) & (px < self._w) & (py >= 0) & (py < self._h)
        far = np.zeros(px.shape, dtype=bool)
        pixels = self._rgb[py[inb], px[inb]].astype(np.int32)
        far[inb] = np.abs(pixels - cloth).sum(axis=1) > BALL_COLOR_DISTANCE
        scores = far.sum(axis=1) / np.maximum(inside.sum(axis=1), 1)
        # O centro de uma bola nunca sai do retângulo por onde ele anda; perto da tabela a borracha,
        # bem diferente do pano, pareceria uma bola.
        t = self._table
        out = ((centers_x < t.min_x - 1.5) | (centers_x > t.max_x + 1.5)
               | (centers_y < t.min_y - 1.5) | (centers_y > t.max_y + 1.5))
        scores[out] = 0.0
        i_best = int(np.argmax(scores))
        best = scores[i_best]
        if best < MIN_BALL_SCORE:
            return None
        lo = i_best
        while lo > 0 and scores[lo - 1] >= best * 0.95:
            lo -= 1
        hi = i_best
        while hi < OBJECT_CANDIDATES - 1 and scores[hi + 1] >= best * 0.95:
            hi += 1
        coarse = aim_angle + math.radians(OBJECT_STEP_DEG * ((lo + hi) / 2.0 - OBJECT_CANDIDATES // 2))
        # A linha da bola alvo é colinear com o anel: começa no centro da bola (2 raios do anel).
        line = self._fit_line(ghost, coarse, 3.15 * r, max(6, int(0.6 * r)))
        direction = line.direction if line else Vec2.of_angle(coarse)
        if line is not None:
            # Reta passando pelo centro do anel: o braço até o traço é bem mais comprido que o próprio
            # traço (curto nos cortes finos e com um leve tremido de quando o jogo é redimensionado).
            start = ghost + direction * (2 * r)
            pts = self._stroke_centers(start, direction, 1.2 * r, (line.end - start).dot(direction) - 2)
            if len(pts) >= max(6, int(0.6 * r)):
                q = pts - np.array([ghost.x, ghost.y])
                vecs = np.linalg.eigh(q.T @ q)[1]
                refined = Vec2(float(vecs[0, 1]), float(vecs[1, 1]))
                direction = refined if refined.dot(direction) > 0 else -refined
        else:
            # Sem a linha (bola colada na tabela: ela entra direto na borracha), a direção sai do
            # centro da bola pelos pixels dela.
            direction = self._object_center_direction(ghost, direction, r, cloth)
        center = ghost + direction * (2 * r)
        return _Line(center, direction, line.end if line else center)

    def _object_center_direction(self, ghost: Vec2, direction: Vec2, r: float, cloth: np.ndarray) -> Vec2:
        """Direção do anel ao centro da bola alvo: centro de massa do que não é pano num disco do tamanho
        da bola (dentro da mesa, sem o próprio anel), repetido até parar."""
        t = self._table
        center = ghost + direction * (2 * r)
        for _ in range(4):
            xs, ys = self._disc(center, r)
            keep = (xs >= t.left) & (xs <= t.right) & (ys >= t.top) & (ys <= t.bottom)
            keep &= np.hypot(xs - ghost.x, ys - ghost.y) > r + 0.25 * r
            xs, ys = xs[keep], ys[keep]
            pixels = self._rgb[ys, xs].astype(np.float32)
            ball = np.abs(pixels - cloth).sum(axis=1) > BALL_COLOR_DISTANCE
            # Sombra da bola no pano: o mesmo pano mais escuro (os três canais caem na mesma proporção).
            k = pixels / np.maximum(cloth.astype(np.float32), 1.0)
            ball &= ~((k.max(axis=1) - k.min(axis=1) < SHADOW_RATIO_SPREAD) & (k.mean(axis=1) < 0.97))
            if ball.sum() < 10:
                break
            mean = Vec2(float(xs[ball].mean()), float(ys[ball].mean()))
            new = (mean - ghost).normalized()
            moved = (ghost + new * (2 * r)).distance_to(center)
            direction, center = new, ghost + new * (2 * r)
            if moved < 0.2:
                break
        return direction

    def _cloth_color(self, c: Vec2, r: float) -> np.ndarray:
        """Cor do pano em volta do anel: mediana de um anel de amostras."""
        xs, ys = [], []
        for radius in (1.3 * r, 1.5 * r, 1.7 * r):
            xs.append(np.rint(c.x + _RING_COS * radius))
            ys.append(np.rint(c.y + _RING_SIN * radius))
        xs = np.concatenate(xs).astype(int)
        ys = np.concatenate(ys).astype(int)
        t = self._table  # só o pano: perto da tabela, a borracha puxaria a mediana
        inb = (xs >= max(0, t.left)) & (xs < min(self._w, t.right)) & (ys >= max(0, t.top)) & (ys < min(self._h, t.bottom))
        if not inb.any():
            return np.zeros(3, dtype=np.int32)
        return np.median(self._rgb[ys[inb], xs[inb]], axis=0).astype(np.int32)

    # --- outras bolas ---

    def _other_balls(self, cue: Vec2) -> list[Vec2]:
        """As bolas só mudam de lugar depois de uma tacada: com a branca parada, reaproveita a busca."""
        last = self._cached_balls_cue
        if last is not None and last.distance_to(cue) < 3.0 and self._frames_since_balls < BALL_REFRESH_FRAMES:
            self._frames_since_balls += 1
            return self._cached_balls
        self._cached_balls = self._balls.find(self._rgb, self._table)
        self._cached_balls_cue = cue
        self._frames_since_balls = 0
        return self._cached_balls

    @staticmethod
    def _on_cue_stick(b: Vec2, cue: Vec2, direction: Vec2, r: float) -> bool:
        """O taco fica atrás da branca; tacos enfeitados têm pedaços do tamanho de uma bola."""
        d = b - cue
        behind = -d.dot(direction)
        across = abs(d.x * direction.y - d.y * direction.x)
        return behind > 0 and across < 1.3 * r
