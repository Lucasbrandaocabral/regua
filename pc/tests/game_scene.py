"""Mesas parecidas com as do 8 Ball Pool, com a resposta certa junto, para testar a régua em massa.

Diferente de ``synthetic.py`` (cenas simples e pequenas dos testes de unidade), aqui a mesa tem o
tamanho e o visual do jogo num celular 2408x1080: pano com degradê e textura, borracha e madeira das
tabelas com os pontinhos claros, caçapas, bolas lisas e listradas com número, brilho e sombra, taco
decorado e as linhas de mira do jogo (traço branco com contorno escuro, anel do ponto de choque, linha
da branca depois do choque e linha da bola alvo). Usado pelo ``simulador.py``.
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, field

import numpy as np

from regua_pc.geometry import Table, Vec2

# Mesa medida num print 2408x1080 do jogo (a mesma dos valores padrão da régua).
WIDTH, HEIGHT = 2408, 1080
TABLE = Table(474.5, 255.5, 1932.0, 984.5, 22.0)

CLOTH_CENTER = np.array([127, 198, 218], dtype=np.float32)
CLOTH_EDGE = np.array([34, 126, 165], dtype=np.float32)
RUBBER = (70, 170, 215)
RAIL = (150, 30, 20)
BACKGROUND = (22, 32, 58)
OUTLINE = (20, 30, 35)
BALL_COLORS = [
    (240, 200, 30), (30, 70, 200), (220, 30, 30), (110, 40, 150), (240, 110, 20),
    (30, 140, 60), (120, 30, 20),
]


@dataclass
class Truth:
    """O que a régua deveria achar na cena."""
    cue: Vec2
    direction: Vec2
    ghost: Vec2 | None  # anel encostado numa bola (None quando a branca bate primeiro na tabela)
    object_ball: Vec2 | None
    ring: Vec2  # anel que o jogo desenha (na bola alvo ou na tabela)
    kind: str
    balls: list[Vec2] = field(default_factory=list)


class GameScene:
    def __init__(self, table: Table = TABLE, width: int = WIDTH, height: int = HEIGHT, seed: int = 0):
        self.t = table
        self.r = table.ball_radius
        self.w, self.h = width, height
        self.rng = np.random.default_rng(seed)
        self.img = self._background()
        self.line_half = 0.09 * self.r
        self.outline = 0.11 * self.r

    # --- desenho básico (só dentro da caixa de cada coisa, para ser rápido) ---

    def _box(self, x0, y0, x1, y1):
        x0, y0 = max(0, int(math.floor(x0))), max(0, int(math.floor(y0)))
        x1, y1 = min(self.w, int(math.ceil(x1)) + 1), min(self.h, int(math.ceil(y1)) + 1)
        if x1 <= x0 or y1 <= y0:
            return None
        yy, xx = np.mgrid[y0:y1, x0:x1].astype(np.float32)
        return (slice(y0, y1), slice(x0, x1)), xx, yy

    def _blend(self, sl, alpha, color):
        a = np.clip(alpha, 0, 1)[..., None]
        self.img[sl] = self.img[sl] * (1 - a) + np.asarray(color, dtype=np.float32) * a

    def disc(self, c: Vec2, radius: float, color, soft: float = 0.7):
        b = self._box(c.x - radius - 1, c.y - radius - 1, c.x + radius + 1, c.y + radius + 1)
        if b is None:
            return
        sl, xx, yy = b
        d = np.hypot(xx - c.x, yy - c.y)
        self._blend(sl, (radius + soft - d) / (2 * soft), color)

    def stroke(self, a: Vec2, b: Vec2, half: float, color):
        pad = half + 1
        box = self._box(min(a.x, b.x) - pad, min(a.y, b.y) - pad, max(a.x, b.x) + pad, max(a.y, b.y) + pad)
        if box is None:
            return
        sl, xx, yy = box
        ab = b - a
        len2 = ab.dot(ab)
        if len2 == 0:
            d = np.hypot(xx - a.x, yy - a.y)
        else:
            s = np.clip(((xx - a.x) * ab.x + (yy - a.y) * ab.y) / len2, 0, 1)
            d = np.hypot(xx - (a.x + ab.x * s), yy - (a.y + ab.y * s))
        self._blend(sl, half + 0.5 - d, color)

    def circle(self, c: Vec2, radius: float, half: float, color):
        box = self._box(c.x - radius - half - 1, c.y - radius - half - 1, c.x + radius + half + 1, c.y + radius + half + 1)
        if box is None:
            return
        sl, xx, yy = box
        d = np.abs(np.hypot(xx - c.x, yy - c.y) - radius)
        self._blend(sl, half + 0.5 - d, color)

    # --- mesa ---

    def _background(self) -> np.ndarray:
        t, r = self.t, self.r
        yy, xx = np.mgrid[0:self.h, 0:self.w].astype(np.float32)
        img = np.empty((self.h, self.w, 3), dtype=np.float32)
        img[:] = BACKGROUND
        rail = (xx > t.left - 3.2 * r) & (xx < t.right + 3.2 * r) & (yy > t.top - 3.2 * r) & (yy < t.bottom + 3.2 * r)
        img[rail] = RAIL
        rubber = (xx > t.left - 1.9 * r) & (xx < t.right + 1.9 * r) & (yy > t.top - 1.9 * r) & (yy < t.bottom + 1.9 * r)
        img[rubber] = RUBBER
        inside = (xx >= t.left) & (xx <= t.right) & (yy >= t.top) & (yy <= t.bottom)
        cx, cy = (t.left + t.right) / 2, (t.top + t.bottom) / 2
        rho = np.minimum(1.0, np.hypot((xx - cx) / (t.width / 2), (yy - cy) / (t.height / 2)) / 1.2)
        cloth = CLOTH_CENTER + (CLOTH_EDGE - CLOTH_CENTER) * rho[..., None]
        dist = np.minimum.reduce([xx - t.left, t.right - xx, yy - t.top, t.bottom - yy])
        shade = np.clip(1 - dist / (1.5 * r), 0, 1)[..., None]
        cloth = cloth * (1 - 0.45 * shade)
        img[inside] = cloth[inside]
        # Textura do pano (o jogo tem um granulado leve).
        img += self.rng.normal(0, 3.0, img.shape[:2])[..., None] * inside[..., None]
        # Linha de cabeceira, bem fraca.
        head = inside & (np.abs(xx - (t.left + 0.25 * t.width)) < 1.0)
        img[head] = img[head] * 0.85 + 255 * 0.15
        self.img = img
        # Caçapas e pontinhos claros nas tabelas.
        for p in t.pockets:
            self.disc(p.center, p.radius, (8, 8, 10))
        for i in range(1, 8):
            if i == 4:
                continue
            x = t.left + t.width * i / 8
            self.disc(Vec2(x, t.top - 2.6 * r), 0.18 * r, (215, 215, 230))
            self.disc(Vec2(x, t.bottom + 2.6 * r), 0.18 * r, (215, 215, 230))
        for i in range(1, 4):
            y = t.top + t.height * i / 4
            self.disc(Vec2(t.left - 2.6 * r, y), 0.18 * r, (215, 215, 230))
            self.disc(Vec2(t.right + 2.6 * r, y), 0.18 * r, (215, 215, 230))
        return self.img

    # --- bolas e taco ---

    def shadow(self, c: Vec2):
        box = self._box(c.x - 1.4 * self.r, c.y - 1.4 * self.r, c.x + 1.6 * self.r, c.y + 1.6 * self.r)
        if box is None:
            return
        sl, xx, yy = box
        d = np.hypot(xx - c.x - 0.15 * self.r, yy - c.y - 0.2 * self.r)
        a = np.clip((1.15 * self.r - d) / (0.3 * self.r), 0, 1) * 0.45
        self.img[sl] = self.img[sl] * (1 - a[..., None])

    def ball(self, c: Vec2, color, striped: bool = False, white_up: bool = False):
        r = self.r
        self.shadow(c)
        self.disc(c, r + 1, OUTLINE)
        box = self._box(c.x - r, c.y - r, c.x + r, c.y + r)
        sl, xx, yy = box
        dx, dy = xx - c.x, yy - c.y
        shade = (1.0 - 0.35 * np.clip((dx + dy) / (2 * r), 0, 1))[..., None]
        col = np.broadcast_to(np.asarray(color, dtype=np.float32), dx.shape + (3,)).copy()
        if striped:
            white = (np.abs(dy) > 0.45 * r) if not white_up else (np.hypot(dx, dy) < 0.75 * r)
            col[white] = (242, 240, 232)
        col = col * shade
        inside = np.hypot(dx, dy) <= r
        a = np.clip(r + 0.7 - np.hypot(dx, dy), 0, 1)[..., None]
        self.img[sl] = self.img[sl] * (1 - a) + col * a
        if not white_up:
            self.disc(c, 0.42 * r, (245, 245, 240))
            self.disc(c + Vec2(0.05 * r, 0.02 * r), 0.16 * r, (15, 15, 15))
        # Brilho do lado de cima.
        self.disc(c + Vec2(-0.38 * r, -0.4 * r), 0.2 * r, (255, 255, 255), soft=1.5)

    def cue_ball(self, c: Vec2):
        r = self.r
        self.shadow(c)
        self.disc(c, r + 1, (30, 30, 30))
        box = self._box(c.x - r, c.y - r, c.x + r, c.y + r)
        sl, xx, yy = box
        dx, dy = xx - c.x, yy - c.y
        s = np.clip((dx + dy) / (2 * r), 0, 1)[..., None]
        col = np.array([252, 244, 225]) + (np.array([196, 200, 210]) - np.array([252, 244, 225])) * s
        a = np.clip(r + 0.7 - np.hypot(dx, dy), 0, 1)[..., None]
        self.img[sl] = self.img[sl] * (1 - a) + col * a

    def cue_stick(self, c: Vec2, u: Vec2, gap: float = 3.0):
        r = self.r
        tip = c - u * (r + gap)
        butt = tip - u * (34 * r)
        self.stroke(tip, butt, 0.34 * r, (20, 14, 10))
        self.stroke(tip, butt, 0.26 * r, (150, 40, 30))
        self.stroke(tip, tip - u * (0.5 * r), 0.26 * r, (238, 236, 228))  # ponteira clara
        self.stroke(tip - u * (0.5 * r), tip - u * (0.8 * r), 0.26 * r, (40, 30, 25))
        for k in range(3, 34, 2):  # enfeites dourados
            a = tip - u * (k * r)
            self.stroke(a, a - u * (0.35 * r), 0.3 * r, (205, 165, 60))

    # --- linhas do jogo ---

    def guide(self, a: Vec2, b: Vec2):
        self.stroke(a, b, self.line_half + self.outline, OUTLINE)
        self.stroke(a, b, self.line_half, (255, 255, 255))

    def ring(self, c: Vec2):
        self.circle(c, self.r, self.line_half + self.outline, OUTLINE)
        self.circle(c, self.r, self.line_half, (255, 255, 255))

    def frame(self) -> np.ndarray:
        return np.clip(self.img, 0, 255).astype(np.uint8)


# --- sorteio de cenas ---

KINDS = ("longe", "perto", "colado", "tabela", "bola_na_tabela")


def _inside(t: Table, p: Vec2, margin: float) -> bool:
    return t.min_x + margin <= p.x <= t.max_x - margin and t.min_y + margin <= p.y <= t.max_y - margin


def _segment_distance(p: Vec2, a: Vec2, b: Vec2) -> float:
    ab = b - a
    l2 = ab.dot(ab)
    s = 0.0 if l2 == 0 else min(1.0, max(0.0, (p - a).dot(ab) / l2))
    return p.distance_to(a + ab * s)


def _first_wall(t: Table, c: Vec2, u: Vec2) -> tuple[Vec2, float]:
    tx = ((t.max_x - c.x) / u.x) if u.x > 1e-9 else ((t.min_x - c.x) / u.x) if u.x < -1e-9 else math.inf
    ty = ((t.max_y - c.y) / u.y) if u.y > 1e-9 else ((t.min_y - c.y) / u.y) if u.y < -1e-9 else math.inf
    s = min(tx, ty)
    return c + u * s, s


def random_scene(seed: int, kind: str | None = None) -> tuple[np.ndarray, Truth]:
    """Uma cena sorteada (imagem RGB e a resposta certa). ``kind`` escolhe o tipo de tacada."""
    rnd = random.Random(seed)
    t, r = TABLE, TABLE.ball_radius
    kind = kind or rnd.choice(KINDS)
    for _ in range(500):
        cue = Vec2(rnd.uniform(t.min_x + r, t.max_x - r), rnd.uniform(t.min_y + r, t.max_y - r))
        u = Vec2.of_angle(rnd.uniform(0, 2 * math.pi))
        wall, wall_dist = _first_wall(t, cue, u)
        if kind == "tabela":
            if wall_dist < 3 * r:
                continue
            ghost, obj, ring = None, None, wall
        else:
            if kind == "longe":
                d = rnd.uniform(4 * r, 20 * r)
            elif kind == "perto":
                d = rnd.uniform(2.3 * r, 4 * r)
            elif kind == "colado":
                d = rnd.uniform(0.35 * r, 2.3 * r)
            else:
                d = rnd.uniform(3 * r, 18 * r)
            ghost = cue + u * d
            cut = math.radians(rnd.uniform(-70, 70))
            n = Vec2(u.x * math.cos(cut) - u.y * math.sin(cut), u.x * math.sin(cut) + u.y * math.cos(cut))
            obj = ghost + n * (2 * r)
            if kind == "bola_na_tabela":
                if not (_inside(t, ghost, 0) and _inside(t, obj, 0)):
                    continue
                near = min(obj.x - t.min_x, t.max_x - obj.x, obj.y - t.min_y, t.max_y - obj.y)
                ghost_near = min(ghost.x - t.min_x, t.max_x - ghost.x, ghost.y - t.min_y, t.max_y - ghost.y)
                if min(near, ghost_near) > 0.6 * r:
                    continue
            elif not (_inside(t, ghost, 0.8 * r) and _inside(t, obj, 0.2 * r)):
                continue
            if d >= wall_dist:
                continue
            ring = ghost
        break
    else:
        raise RuntimeError("não deu para sortear a cena")

    # Outras bolas, longe do caminho da branca, da bola alvo e umas das outras.
    balls: list[Vec2] = []
    taken = [cue] + ([obj] if obj else [])
    for _ in range(rnd.randint(5, 12)):
        for _ in range(100):
            p = Vec2(rnd.uniform(t.min_x, t.max_x), rnd.uniform(t.min_y, t.max_y))
            if any(p.distance_to(q) < 2.1 * r for q in taken + balls):
                continue
            if _segment_distance(p, cue, ring) < 2.3 * r or p.distance_to(ring) < 3.2 * r:
                continue
            if obj and _segment_distance(p, obj, obj + (obj - ring).normalized() * 6 * r) < 1.4 * r:
                continue
            if _segment_distance(p, cue, cue - u * 34 * r) < 1.6 * r:  # embaixo do taco
                continue
            balls.append(p)
            break

    scene = GameScene(seed=seed)
    for p in balls:
        striped = rnd.random() < 0.5
        scene.ball(p, rnd.choice(BALL_COLORS), striped=striped, white_up=striped and rnd.random() < 0.25)
    if obj:
        scene.ball(obj, rnd.choice(BALL_COLORS), striped=rnd.random() < 0.4)
    scene.cue_ball(cue)
    scene.cue_stick(cue, u)
    scene.guide(cue, ring)
    scene.ring(ring)
    if obj:
        n = (obj - ring).normalized()
        cos_cut = max(0.0, u.dot(n))
        tangent = u - n * u.dot(n)
        if tangent.length > 1e-6:
            scene.guide(ring, ring + tangent.normalized() * ((1 + 3 * math.sqrt(1 - cos_cut ** 2)) * r))
        scene.guide(obj, obj + n * ((1.5 + 4.5 * cos_cut) * r))
    return scene.frame(), Truth(cue, u, ghost, obj, ring, kind, balls)
