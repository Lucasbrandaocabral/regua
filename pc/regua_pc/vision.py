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

MIN_CUE_FILL = 0.6  # fração do quadrado central que precisa ser branca para ser a branca
HINT_DEGREES = 15  # quanto a linha pode ter girado desde o quadro anterior
OUTLIER_PX = 1.5  # distância máxima de um ponto até a reta para continuar no ajuste
MIN_RING_SCORE = 0.55
RING_SAMPLES = 48
RING_SKIP_COS = math.cos(math.radians(25.0))
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

    def scaled(self, k: float) -> "AimResult":
        return AimResult(
            self.cue * k, self.direction, self.guide_length * k,
            self.ghost * k if self.ghost else None,
            self.object_ball * k if self.object_ball else None,
            self.object_guide_length * k, [b * k for b in self.balls],
        )

    def shifted(self, offset: Vec2) -> "AimResult":
        return AimResult(
            self.cue + offset, self.direction, self.guide_length,
            self.ghost + offset if self.ghost else None,
            self.object_ball + offset if self.object_ball else None,
            self.object_guide_length, [b + offset for b in self.balls],
        )


def white_mask(rgb: np.ndarray) -> np.ndarray:
    """Branco "de jogo": claro e sem cor (bola branca e linhas de mira, mas não o pano)."""
    # Canal a canal: reduzir no eixo das cores com min/max do numpy é bem mais lento.
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mn = np.minimum(np.minimum(r, g), b)
    mx = np.maximum(np.maximum(r, g), b)
    return (mn >= 185) & ((mx - mn) <= 70)


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
        if r < 3.0:
            return None
        self._rgb = rgb
        self._h, self._w = rgb.shape[:2]
        self._table = table
        self._white = self._white_in_table(rgb, table)
        self._wbytes = self._white.tobytes()

        rough = None
        if previous is not None:
            rough = self._find_cue_ball(previous.cue)
        if rough is None:
            rough = self._find_cue_ball(None)
        if rough is None:
            return None
        guide = None
        if previous is not None:
            guide = self._find_guide(rough, math.degrees(math.atan2(previous.direction.y, previous.direction.x)))
        if guide is None:
            guide = self._find_guide(rough, None)
        if guide is None:
            return None

        direction = guide.direction
        cue = self._cue_center_on_line(guide.origin, direction, r)
        guide_length = (guide.end - cue).dot(direction)
        ring = self._find_ghost(cue, direction, guide_length, r)
        if ring is not None:
            # Dentro do anel a linha se mistura com ele: refaz o ajuste só com o trecho limpo.
            ring_distance = (ring - cue).dot(direction)
            clean = self._collect_and_fit(cue, direction, 1.5 * r, self._min_guide_samples(), stop_at=ring_distance - 1.5 * r)
            if clean is not None:
                direction = clean.direction
                cue = self._cue_center_on_line(clean.origin, direction, r)
                ring = self._find_ghost(cue, direction, ring_distance, r) or ring
            guide_length = (ring - cue).dot(direction)
        # Anel colado na tabela é a branca batendo na tabela, não numa bola.
        ghost = ring if ring is not None and self._inside_inner(ring, 0.6 * r) else None
        obj = self._find_object_ball(ghost, direction) if ghost is not None else None

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
            balls,
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

    def _find_cue_ball(self, near: Vec2 | None) -> Vec2 | None:
        """A branca é onde um quadrado do tamanho do miolo da bola fica mais cheio de branco."""
        t = self._table
        r = t.ball_radius
        half = max(2, int(round(0.6 * r)))
        reach = 3 * r
        x0 = max(0, int(max(t.min_x, (near.x if near else t.min_x) - reach)))
        x1 = min(self._w - 1, int(math.ceil(min(t.max_x, (near.x if near else t.max_x) + reach))))
        y0 = max(0, int(max(t.min_y, (near.y if near else t.min_y) - reach)))
        y1 = min(self._h - 1, int(math.ceil(min(t.max_y, (near.y if near else t.max_y) + reach))))
        if x1 <= x0 or y1 <= y0:
            return None
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
        sums = integral[bottom, right] - integral[top, right] - integral[bottom, left] + integral[top, left]
        j, i = np.unravel_index(int(np.argmax(sums)), sums.shape)
        side = 2 * half + 1
        if sums[j, i] < MIN_CUE_FILL * side * side:
            return None
        center = Vec2(float(cxs[i]), float(cys[j]))
        for _ in range(2):
            center = self._white_centroid(center, 0.85 * r) or center
        return center

    def _disc(self, c: Vec2, radius: float):
        xs = np.arange(int(math.floor(c.x - radius)), int(math.ceil(c.x + radius)) + 1)
        ys = np.arange(int(math.floor(c.y - radius)), int(math.ceil(c.y + radius)) + 1)
        gx, gy = np.meshgrid(xs, ys)
        inside = ((gx - c.x) ** 2 + (gy - c.y) ** 2 <= radius * radius)
        inside &= (gx >= 0) & (gx < self._w) & (gy >= 0) & (gy < self._h)
        return gx[inside], gy[inside]

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
        """O raio que segue um traço branco fino por mais tempo a partir da branca (com raios
        paralelos deslocados, porque o centro achado pode estar fora da linha)."""
        r = self._table.ball_radius
        start = 1.5 * r
        min_samples = self._min_guide_samples()
        if around_degrees is None:
            degrees = range(360)
            offsets = (0.0, -0.5 * r, 0.5 * r, -r, r)
        else:
            # Com a dica do quadro anterior a branca já está bem centrada na linha.
            c = int(round(around_degrees))
            degrees = range(c - HINT_DEGREES, c + HINT_DEGREES + 1)
            offsets = (0.0,)
        best_run, best_origin, best_angle = 0.0, cue, 0.0
        for deg in degrees:
            angle = math.radians(deg)
            u = Vec2.of_angle(angle)
            n = Vec2(-u.y, u.x)
            for offset in offsets:
                origin = cue + n * offset
                run = self._run_length(origin, u, start)
                if run > best_run:
                    best_run, best_origin, best_angle = run, origin, angle
        if best_run < min_samples:
            return None
        stop_at = start + max(best_run - r, 0.6 * best_run)
        wide = self._collect_and_fit(best_origin, Vec2.of_angle(best_angle), start, min_samples, stop_at, tolerance=0.6 * r)
        if wide is None:
            return None
        return self._collect_and_fit(wide.origin, wide.direction, start, min_samples) or wide

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
        cada corte estreito do traço até ele acabar, e ajusta uma reta por mínimos quadrados."""
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
        center = ghost + direction * (2 * r)
        return _Line(center, direction, line.end if line else center)

    def _cloth_color(self, c: Vec2, r: float) -> np.ndarray:
        """Cor do pano em volta do anel: mediana de um anel de amostras."""
        xs, ys = [], []
        for radius in (1.3 * r, 1.5 * r, 1.7 * r):
            xs.append(np.rint(c.x + _RING_COS * radius))
            ys.append(np.rint(c.y + _RING_SIN * radius))
        xs = np.concatenate(xs).astype(int)
        ys = np.concatenate(ys).astype(int)
        inb = (xs >= 0) & (xs < self._w) & (ys >= 0) & (ys < self._h)
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
