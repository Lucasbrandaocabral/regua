"""Desenha quadros parecidos com os do 8 Ball Pool para testar a visão: pano com degradê, tabelas
com sombra, bolas, taco e as linhas de mira brancas com contorno escuro."""

from __future__ import annotations

import math

import numpy as np

from regua_pc.geometry import Table, Vec2


class SyntheticTable:
    def __init__(self, width: int = 1200, height: int = 560, table: Table | None = None):
        self.width, self.height = width, height
        self.table = table or Table(100.0, 30.0, 1100.0, 530.0, 15.0)
        t = self.table
        self.r = t.ball_radius
        self.line_half = 0.09 * self.r
        self.outline = 0.11 * self.r
        yy, xx = np.mgrid[0:height, 0:width].astype(np.float32)
        self._xx, self._yy = xx, yy
        cx, cy = (t.left + t.right) / 2, (t.top + t.bottom) / 2
        rho = np.minimum(1.0, np.hypot((xx - cx) / (t.width / 2), (yy - cy) / (t.height / 2)) / 1.2)
        img = np.zeros((height, width, 3), dtype=np.float32)
        center, edge = np.array([127, 198, 218]), np.array([34, 126, 165])
        img[:] = center + (edge - center) * rho[..., None]
        # Sombra das tabelas no pano (mais escuro perto da borda).
        dist = np.minimum.reduce([xx - t.left, t.right - xx, yy - t.top, t.bottom - yy])
        shade = np.clip(1 - dist / (1.5 * self.r), 0, 1)[..., None]
        img = img * (1 - 0.45 * shade)
        # Tabelas: faixa de borracha clara por fora e madeira mais longe.
        outside = (xx < t.left) | (xx > t.right) | (yy < t.top) | (yy > t.bottom)
        img[outside] = (70, 170, 215)
        rail = (xx < t.left - 2 * self.r) | (xx > t.right + 2 * self.r) | (yy < t.top - 2 * self.r) | (yy > t.bottom + 2 * self.r)
        img[rail] = (150, 30, 20)
        self.pixels = img

    def frame(self) -> np.ndarray:
        return np.clip(self.pixels, 0, 255).astype(np.uint8)

    def _disc(self, c: Vec2, radius: float):
        return (self._xx - c.x) ** 2 + (self._yy - c.y) ** 2 <= radius * radius

    def ball(self, c: Vec2, color, striped: bool = False):
        r = self.r
        self.pixels[self._disc(c, r + 1)] = (20, 20, 20)
        inside = self._disc(c, r)
        shade = 1.0 - 0.35 * np.maximum(0.0, ((self._xx - c.x) + (self._yy - c.y)) / (2 * r))
        col = np.array(color, dtype=np.float32)[None, None, :] * shade[..., None]
        if striped:
            stripe = np.abs(self._yy - c.y) > 0.5 * r
            col = np.where(stripe[..., None], np.array([240, 238, 230])[None, None, :] * shade[..., None], col)
        self.pixels[inside] = col[inside]
        self.pixels[self._disc(c, 0.45 * r)] = (245, 245, 240)
        self.pixels[self._disc(c, 0.15 * r)] = (10, 10, 10)

    def cue_ball(self, c: Vec2):
        r = self.r
        self.pixels[self._disc(c, r + 1)] = (30, 30, 30)
        inside = self._disc(c, r)
        t = np.maximum(0.0, ((self._xx - c.x) + (self._yy - c.y)) / (2 * r))[..., None]
        col = np.array([252, 244, 225]) + (np.array([200, 206, 216]) - np.array([252, 244, 225])) * t
        self.pixels[inside] = col[inside]

    def stroke(self, a: Vec2, b: Vec2, half: float, color):
        ab = b - a
        len2 = ab.dot(ab)
        if len2 == 0:
            d = np.hypot(self._xx - a.x, self._yy - a.y)
        else:
            t = np.clip(((self._xx - a.x) * ab.x + (self._yy - a.y) * ab.y) / len2, 0, 1)
            d = np.hypot(self._xx - (a.x + ab.x * t), self._yy - (a.y + ab.y * t))
        self.pixels[d <= half] = color

    def guide(self, a: Vec2, b: Vec2):
        self.stroke(a, b, self.line_half + self.outline, (20, 30, 35))
        self.stroke(a, b, self.line_half, (255, 255, 255))

    def ring(self, c: Vec2):
        d = np.abs(np.hypot(self._xx - c.x, self._yy - c.y) - self.r)
        self.pixels[d <= self.line_half + self.outline] = (20, 30, 35)
        self.pixels[d <= self.line_half] = (255, 255, 255)

    def cue(self, c: Vec2, direction: Vec2, shaft=(140, 70, 30)):
        tip = c - direction * (self.r + 2)
        self.stroke(tip, tip - direction * (30 * self.r), 0.3 * self.r, shaft)
        self.stroke(tip, tip - direction * (0.6 * self.r), 0.3 * self.r, (240, 240, 235))

    def aim_at(self, cue: Vec2, target: Vec2 | None, direction: Vec2):
        """Tudo o que o jogo mostra quando a branca mira em ``direction`` e acerta ``target``."""
        self.cue(cue, direction)
        self.cue_ball(cue)
        if target is None:
            self.guide(cue, cue + direction * (12 * self.r))
            return
        m = cue - target
        b = m.dot(direction)
        c = m.dot(m) - 4 * self.r * self.r
        t = -b - math.sqrt(b * b - c)
        ghost = cue + direction * t
        n = (target - ghost).normalized()
        tangent = (direction - n * direction.dot(n)).normalized()
        self.guide(cue, ghost)
        self.ring(ghost)
        if tangent.length > 0:
            self.guide(ghost, ghost + tangent * (2.3 * self.r))
        self.guide(target, target + n * (6 * self.r))
