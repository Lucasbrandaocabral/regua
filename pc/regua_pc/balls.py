"""Acha as bolas paradas na mesa. Porte de ``vision/BallFinder.kt`` do app Android.

Primeiro estima a cor do pano em cada ponto (degradê radial + sombra de cada tabela + correção por
blocos) e depois procura discos do tamanho da bola com cor bem diferente dele.
"""

from __future__ import annotations

import math

import numpy as np

from .geometry import Table, Vec2

OBJECT_DIFFERENCE = 70.0  # diferença de cor a partir da qual o pixel não é pano
CLOTH_DIFFERENCE = 45.0  # até essa diferença o pixel ainda conta como pano ao corrigir os blocos
CHROMA_WEIGHT = 1.5
MIN_FILL = 0.75  # fração do miolo que precisa ser "não pano" para contar como bola
MAX_BALLS = 24
CLOTH_REFRESH = 30  # de quantas em quantas buscas reestima o pano
ROUND_SAMPLES = 16
ROUND_MIN_HITS = 13
BINS = 24
MAX_RHO = math.sqrt(2.0)


def color_difference(pixels: np.ndarray, expected: np.ndarray) -> np.ndarray:
    """Diferença que pesa mais o tom do que o brilho: o pano muda de brilho (degradê, sombras) mas
    quase não muda de tom, e uma bola azul no pano azul muda pouco no brilho."""
    d = pixels.astype(np.float32) - expected
    dy = 0.299 * d[..., 0] + 0.587 * d[..., 1] + 0.114 * d[..., 2]
    return np.abs(dy) + CHROMA_WEIGHT * (np.abs(d[..., 2] - dy) + np.abs(d[..., 0] - dy))


class _Radial:
    """Degradê do pano em função da distância ao centro da mesa: mediana de cada canal em anéis,
    longe das tabelas (cuja sombra é medida à parte)."""

    def __init__(self, rgb: np.ndarray, t: Table):
        self.cx, self.cy = (t.left + t.right) / 2, (t.top + t.bottom) / 2
        self.hw, self.hh = t.width / 2, t.height / 2
        h, w = rgb.shape[:2]
        step = max(2, int(t.ball_radius / 2))
        inset = 2 * t.ball_radius
        xs = np.arange(int(t.left + inset), int(t.right - inset) + 1, step)
        ys = np.arange(int(t.top + inset), int(t.bottom - inset) + 1, step)
        gx, gy = np.meshgrid(xs, ys)
        gx, gy = gx.ravel(), gy.ravel()
        ok = (gx >= 0) & (gx < w) & (gy >= 0) & (gy < h)
        gx, gy = gx[ok], gy[ok]
        pixels = rgb[gy, gx].astype(np.float32)
        bins = np.clip(np.rint(self._rho(gx, gy) / MAX_RHO * (BINS - 1)), 0, BINS - 1).astype(int)
        profile = np.full((BINS, 3), np.nan, dtype=np.float32)
        for b in range(BINS):
            sel = bins == b
            if sel.sum() >= 10:
                profile[b] = np.median(pixels[sel], axis=0)
        filled = np.nonzero(~np.isnan(profile[:, 0]))[0]
        if len(filled) == 0:
            raise ValueError("pano não encontrado")
        for b in range(BINS):
            if np.isnan(profile[b, 0]):
                profile[b] = profile[filled[np.argmin(np.abs(filled - b))]]
        self.profile = profile

    def _rho(self, x, y):
        return np.hypot((x - self.cx) / self.hw, (y - self.cy) / self.hh)

    def color(self, x, y) -> np.ndarray:
        f = np.clip(self._rho(np.asarray(x, dtype=np.float64), np.asarray(y, dtype=np.float64)) / MAX_RHO * (BINS - 1), 0, BINS - 1)
        i = np.minimum(f.astype(int), BINS - 2)
        t = (f - i)[..., None]
        return self.profile[i] * (1 - t) + self.profile[i + 1] * t


class Cloth:
    """Cor esperada do pano numa grade sobre a mesa."""

    def __init__(self, rgb: np.ndarray, t: Table):
        h, w = rgb.shape[:2]
        r = t.ball_radius
        self.table = t
        radial = _Radial(rgb, t)
        self._radial = radial
        self._edges = self._edge_shadows(rgb, t, radial)

        # Correção por blocos: média do resíduo nos pixels que parecem pano.
        block = 3 * r
        bcols = max(1, math.ceil(t.width / block))
        brows = max(1, math.ceil(t.height / block))
        sstep = max(1, int(block / 12))
        xs = np.arange(max(0, int(t.left)), min(w, math.ceil(t.right)), sstep)
        ys = np.arange(max(0, int(t.top)), min(h, math.ceil(t.bottom)), sstep)
        gx, gy = np.meshgrid(xs, ys)
        gx, gy = gx.ravel(), gy.ravel()
        base = self._base(gx, gy)
        pixels = rgb[gy, gx].astype(np.float32)
        is_cloth = color_difference(pixels, base) <= CLOTH_DIFFERENCE
        bi = np.clip(((gx - t.left) / block).astype(int), 0, bcols - 1)
        bj = np.clip(((gy - t.top) / block).astype(int), 0, brows - 1)
        bidx = bj * bcols + bi
        total = np.bincount(bidx, minlength=bcols * brows)
        count = np.bincount(bidx[is_cloth], minlength=bcols * brows)
        correction = np.zeros((bcols * brows, 3), dtype=np.float32)
        for c in range(3):
            s = np.bincount(bidx[is_cloth], weights=(pixels[is_cloth, c] - base[is_cloth, c]), minlength=bcols * brows)
            good = (total > 0) & (count >= 0.3 * total)
            correction[good, c] = s[good] / count[good]
        self._block, self._bcols, self._brows = block, bcols, brows
        self._correction = correction.reshape(brows, bcols, 3)

        # Grade com a cor esperada (bolas grandes: um nó a cada poucos pixels basta).
        self.step = max(1, int(r / 7))
        self.x0, self.y0 = max(0, int(math.floor(t.left))), max(0, int(math.floor(t.top)))
        x1, y1 = min(w - 1, math.ceil(t.right)), min(h - 1, math.ceil(t.bottom))
        self.columns = (x1 - self.x0) // self.step + 1
        self.rows = (y1 - self.y0) // self.step + 1
        gx, gy = np.meshgrid(self.x0 + np.arange(self.columns) * self.step, self.y0 + np.arange(self.rows) * self.step)
        self.background = self._expected(gx, gy).astype(np.float32)

    def _base(self, x, y) -> np.ndarray:
        """Degradê + sombra da tabela mais próxima."""
        t = self.table
        out = self._radial.color(x, y).astype(np.float32)
        dists = np.stack([x - t.left, t.right - x, y - t.top, t.bottom - y], axis=-1)
        side = np.argmin(dists, axis=-1)
        d = np.take_along_axis(dists, side[..., None], axis=-1)[..., 0].astype(int)
        depth = self._edges.shape[1]
        ok = (d >= 0) & (d < depth)
        out[ok] += self._edges[side[ok], d[ok]]
        return out

    def _expected(self, x, y) -> np.ndarray:
        t = self.table
        out = self._base(x, y)
        fx = np.clip((x - t.left) / self._block - 0.5, 0, self._bcols - 1)
        fy = np.clip((y - t.top) / self._block - 0.5, 0, self._brows - 1)
        i = np.clip(np.minimum(fx.astype(int), self._bcols - 2), 0, None)
        j = np.clip(np.minimum(fy.astype(int), self._brows - 2), 0, None)
        tx = (fx - i)[..., None] if self._bcols > 1 else 0.0
        ty = (fy - j)[..., None] if self._brows > 1 else 0.0
        i1 = np.minimum(i + 1, self._bcols - 1)
        j1 = np.minimum(j + 1, self._brows - 1)
        c = self._correction
        top = c[j, i] * (1 - tx) + c[j, i1] * tx
        bottom = c[j1, i] * (1 - tx) + c[j1, i1] * tx
        return out + top * (1 - ty) + bottom * ty

    @staticmethod
    def _edge_shadows(rgb: np.ndarray, t: Table, radial: _Radial) -> np.ndarray:
        """Para cada tabela (esquerda, direita, cima, baixo), a diferença mediana entre o pano e o
        degradê a cada pixel de distância dela, até 3 raios. Pula as bocas das caçapas."""
        h, w = rgb.shape[:2]
        r = t.ball_radius
        depth = max(1, int(3 * r))
        step = max(2.0, r / 2)
        margin = 3 * r
        edges = np.zeros((4, depth, 3), dtype=np.float32)
        ds = np.arange(depth)
        for side in range(4):
            vertical = side < 2
            lo, hi = (t.top, t.bottom) if vertical else (t.left, t.right)
            along = np.arange(lo + margin, hi - margin + 1e-9, step)
            if not vertical:
                along = along[np.abs(along - t.center_x) > 2.5 * r]
            if len(along) == 0:
                continue
            a, dd = np.meshgrid(along, ds)  # (depth, along)
            if side == 0:
                x, y = t.left + dd, a
            elif side == 1:
                x, y = t.right - dd, a
            elif side == 2:
                x, y = a, t.top + dd
            else:
                x, y = a, t.bottom - dd
            xi, yi = np.rint(x).astype(int), np.rint(y).astype(int)
            ok = (xi >= 0) & (xi < w) & (yi >= 0) & (yi < h)
            residual = np.full(xi.shape + (3,), np.nan, dtype=np.float32)
            residual[ok] = rgb[yi[ok], xi[ok]].astype(np.float32) - radial.color(xi[ok], yi[ok])
            with np.errstate(all="ignore"):
                med = np.nanmedian(residual, axis=1)
            edges[side] = np.nan_to_num(med)
        return edges

    def is_object_grid(self, rgb: np.ndarray) -> np.ndarray:
        gx = self.x0 + np.arange(self.columns) * self.step
        gy = self.y0 + np.arange(self.rows) * self.step
        pixels = rgb[gy[:, None], gx[None, :]]
        return color_difference(pixels, self.background) > OBJECT_DIFFERENCE

    def is_object(self, rgb: np.ndarray, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        """Compara cada pixel com a cor esperada no nó mais próximo da grade."""
        gx = np.clip(np.rint((xs - self.x0) / self.step).astype(int), 0, self.columns - 1)
        gy = np.clip(np.rint((ys - self.y0) / self.step).astype(int), 0, self.rows - 1)
        return color_difference(rgb[ys, xs], self.background[gy, gx]) > OBJECT_DIFFERENCE


class BallFinder:
    def __init__(self) -> None:
        self._cloth: Cloth | None = None
        self._cloth_table: Table | None = None
        self._finds = 0

    def _cloth_for(self, rgb: np.ndarray, t: Table) -> Cloth | None:
        if self._cloth is not None and t == self._cloth_table and self._finds < CLOTH_REFRESH:
            self._finds += 1
            return self._cloth
        try:
            self._cloth = Cloth(rgb, t)
        except ValueError:
            self._cloth = None
        self._cloth_table = t
        self._finds = 0
        return self._cloth

    def find(self, rgb: np.ndarray, t: Table) -> list[Vec2]:
        r = t.ball_radius
        if r < 3.0:
            return []
        cloth = self._cloth_for(rgb, t)
        if cloth is None:
            return []
        h, w = rgb.shape[:2]
        step = cloth.step
        mask = cloth.is_object_grid(rgb).astype(np.int32)
        integral = np.zeros((mask.shape[0] + 1, mask.shape[1] + 1), dtype=np.int32)
        integral[1:, 1:] = mask.cumsum(0).cumsum(1)
        half = max(1, int(round(0.6 * r / step)))
        area = (2 * half + 1) ** 2
        gx0 = max(half, math.ceil((t.min_x - cloth.x0) / step))
        gx1 = min(cloth.columns - 1 - half, math.floor((t.max_x - cloth.x0) / step))
        gy0 = max(half, math.ceil((t.min_y - cloth.y0) / step))
        gy1 = min(cloth.rows - 1 - half, math.floor((t.max_y - cloth.y0) / step))
        if gx1 < gx0 or gy1 < gy0:
            return []
        gys = np.arange(gy0, gy1 + 1)[:, None]
        gxs = np.arange(gx0, gx1 + 1)[None, :]
        sums = (integral[gys + half + 1, gxs + half + 1] - integral[gys - half, gxs + half + 1]
                - integral[gys + half + 1, gxs - half] + integral[gys - half, gxs - half])
        # Só máximos locais com o miolo cheio.
        padded = np.pad(sums, 1, constant_values=-1)
        neighbors = np.max(np.stack([padded[1 + dy:padded.shape[0] - 1 + dy, 1 + dx:padded.shape[1] - 1 + dx]
                                     for dy in (-1, 0, 1) for dx in (-1, 0, 1)]), axis=0)
        cand = (sums >= MIN_FILL * area) & (sums >= neighbors)
        jj, ii = np.nonzero(cand)
        order = np.argsort(-sums[jj, ii], kind="stable")
        min_distance = 1.6 * r
        centers: list[Vec2] = []
        for k in order:
            p = Vec2(float(cloth.x0 + (ii[k] + gx0) * step), float(cloth.y0 + (jj[k] + gy0) * step))
            if all(c.distance_to(p) >= min_distance for c in centers):
                centers.append(p)
                if len(centers) >= MAX_BALLS:
                    break
        refined: list[Vec2] = []
        for c in centers:
            p = self._refine(rgb, cloth, c, r, w, h)
            if all(q.distance_to(p) >= min_distance for q in refined) and self._is_round(rgb, cloth, p, r, w, h):
                refined.append(p)
        return refined

    @staticmethod
    def _refine(rgb, cloth: Cloth, start: Vec2, r: float, w: int, h: int) -> Vec2:
        """Centro de massa do que não é pano numa janela do tamanho da bola, repetido até parar."""
        step = max(1, cloth.step // 2)
        c = start
        for _ in range(6):
            xs = np.arange(int(math.floor(c.x - r)), int(math.ceil(c.x + r)) + 1, step)
            ys = np.arange(int(math.floor(c.y - r)), int(math.ceil(c.y + r)) + 1, step)
            gx, gy = np.meshgrid(xs, ys)
            sel = ((gx - c.x) ** 2 + (gy - c.y) ** 2 <= r * r) & (gx >= 0) & (gx < w) & (gy >= 0) & (gy < h)
            gx, gy = gx[sel], gy[sel]
            obj = cloth.is_object(rgb, gx, gy)
            if not obj.any():
                break
            nxt = Vec2(float(gx[obj].mean()), float(gy[obj].mean()))
            moved = nxt.distance_to(c)
            c = nxt
            if moved < 0.2:
                break
        return c

    @staticmethod
    def _is_round(rgb, cloth: Cloth, c: Vec2, r: float, w: int, h: int) -> bool:
        """Uma bola cobre todo o círculo de 70% do raio; o taco só cobre os pontos na direção dele."""
        a = np.arange(ROUND_SAMPLES) * 2 * math.pi / ROUND_SAMPLES
        xs = np.rint(c.x + np.cos(a) * 0.7 * r).astype(int)
        ys = np.rint(c.y + np.sin(a) * 0.7 * r).astype(int)
        ok = (xs >= 0) & (xs < w) & (ys >= 0) & (ys < h)
        return int(cloth.is_object(rgb, xs[ok], ys[ok]).sum()) >= ROUND_MIN_HITS
